package invoicing.service

import invoicing.domain.*
import invoicing.db.*
import invoicing.external.{Notifications, PaymentRail}

import cats.effect.*
import cats.syntax.all.*

import java.time.Instant
import java.util.UUID

/** Settling an invoice.
  *
  * Cross-currency rule: if the payer's selected bank account is not in the invoice's currency, we ask the payment rail
  * for an FX rate and debit the payer's account in *its* currency. The receipt records the rate used so the payer's
  * accountants can reconcile against their statements.
  *
  * `payAuto` is the entry point the InvoicingService calls when the payer's mode for this biller is `AutoDebit`.
  */
trait PaymentService[F[_]] {

  def pay(invoiceId: InvoiceId, bankAccountId: BankAccountId): F[Either[PaymentService.Error, Payment]]

  /** Used by the auto-debit flow: looks up payer prefs + default account and pays. */
  def payAuto(
      invoice: Invoice,
      prefs: PaymentPreference,
      payerAccounts: List[BankAccount]
  ): F[Either[PaymentService.Error, Payment]]
}

object PaymentService {

  sealed trait Error
  object Error {
    case object InvoiceNotFound extends Error
    case object NotPayable extends Error
    case object BankAccountNotFound extends Error
    case object BankAccountMismatch extends Error
    case object BillerNotApproved extends Error
    final case class RailFailure(msg: String) extends Error
  }

  def make[F[_]: Sync](
      invoices: Invoices[F],
      payments: Payments[F],
      bankAccounts: BankAccounts[F],
      businesses: Businesses[F],
      rail: PaymentRail[F],
      notifications: Notifications[F],
      payerContact: BusinessId => F[Option[Email]],
      billerContact: BusinessId => F[Option[Email]]
  ): PaymentService[F] = new PaymentService[F] {

    def pay(invoiceId: InvoiceId, bankAccountId: BankAccountId): F[Either[Error, Payment]] = {
      val gate: F[Either[Error, (Invoice, BankAccount)]] =
        for {
          inv <- invoices.find(invoiceId)
          acct <- bankAccounts.find(bankAccountId)
        } yield (inv, acct) match {
          case (None, _)                                       => Left(Error.InvoiceNotFound)
          case (_, None)                                       => Left(Error.BankAccountNotFound)
          case (Some(i), _) if !payable(i.status)              => Left(Error.NotPayable)
          case (Some(i), Some(a)) if a.businessId != i.payerId => Left(Error.BankAccountMismatch)
          case (Some(i), Some(a))                              => Right((i, a))
        }

      gate.flatMap {
        case Left(err)            => Sync[F].pure(Left(err))
        case Right((inv, payerA)) => runRail(inv, payerA)
      }
    }

    def payAuto(
        invoice: Invoice,
        prefs: PaymentPreference,
        payerAccounts: List[BankAccount]
    ): F[Either[Error, Payment]] = {
      val preferred = prefs.defaultBankAccountId.flatMap(id => payerAccounts.find(_.id == id))
      val fallback = payerAccounts.find(_.isDefault).orElse(payerAccounts.headOption)
      preferred.orElse(fallback) match {
        case None    => Sync[F].pure(Left(Error.BankAccountNotFound))
        case Some(a) => runRail(invoice, a)
      }
    }

    private def runRail(inv: Invoice, payerAcc: BankAccount): F[Either[Error, Payment]] = {
      // Gate the rail on biller KYB — never settle to an unverified payee.
      val billerGate: F[Either[Error, Unit]] =
        businesses.find(inv.billerId).map {
          case Some(b) if b.kybStatus == KybStatus.Approved => Right(())
          case _                                            => Left(Error.BillerNotApproved)
        }

      val workflow: F[Either[Error, Payment]] =
        for {
          // Resolve the biller's default account (where funds land).
          billerAccs <- bankAccounts.listFor(inv.billerId)
          billerAcc <- billerAccs
            .find(a => a.isDefault && a.currency == inv.currency)
            .orElse(billerAccs.headOption) match {
            case None    => Sync[F].raiseError[BankAccount](new RuntimeException("biller has no payout account"))
            case Some(a) => Sync[F].pure(a)
          }

          // FX if payer's account currency differs from invoice currency.
          fxOpt <-
            if (payerAcc.currency == inv.currency) Sync[F].pure(Option.empty[BigDecimal])
            else rail.fxRate(payerAcc.currency, inv.currency).map(Some(_))

          // We always settle in the invoice currency; debit the payer's account
          // for the converted amount. The rail computes settlement; here we just
          // pass the invoice currency and amount.
          paymentRow = Payment(
            id = PaymentId.assume(UUID.randomUUID()),
            invoiceId = inv.id,
            bankAccountId = payerAcc.id,
            amountMinor = inv.totalMinor,
            currency = inv.currency,
            fxRateApplied = fxOpt,
            status = PaymentStatus.Initiated,
            railRef = None,
            failureReason = None,
            initiatedAt = Instant.now(),
            completedAt = None
          )
          saved <- payments.insert(paymentRow)
          receipt <- rail
            .transfer(payerAcc, billerAcc, inv.totalMinor, inv.currency, s"inv:${inv.number.value}")
            .attempt
          out <- receipt match {
            case Right(r) =>
              for {
                _ <- payments.updateStatus(saved.id, r.status, Some(r.railRef), None, Some(Instant.now()))
                _ <- if (r.status == PaymentStatus.Captured) markPaidAndNotify(inv) else Sync[F].unit
              } yield Right[Error, Payment](saved.copy(status = r.status, railRef = Some(r.railRef)))
            case Left(err) =>
              val msg = Option(err.getMessage).getOrElse(err.getClass.getSimpleName)
              payments
                .updateStatus(saved.id, PaymentStatus.Failed, None, Some(msg), Some(Instant.now()))
                .as(Left[Error, Payment](Error.RailFailure(msg)))
          }
        } yield out

      billerGate.flatMap {
        case Left(err) => Sync[F].pure(Left(err))
        case Right(_)  => workflow
      }
    }

    private def markPaidAndNotify(inv: Invoice): F[Unit] =
      for {
        now <- Sync[F].delay(Instant.now())
        _ <- invoices.markPaid(inv.id, now)
        _ <- billerContact(inv.billerId).flatMap(_.traverse_(notifications.emailInvoicePaid(_, inv)))
        _ <- payerContact(inv.payerId).flatMap(_.traverse_(notifications.emailInvoicePaid(_, inv)))
      } yield ()

    private def payable(s: InvoiceStatus): Boolean = s match {
      case InvoiceStatus.Sent | InvoiceStatus.Overdue | InvoiceStatus.PartiallyPaid => true
      case _                                                                        => false
    }
  }
}
