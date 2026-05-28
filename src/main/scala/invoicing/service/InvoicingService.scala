package invoicing.service

import invoicing.domain.*
import invoicing.db.*
import invoicing.external.Notifications

import cats.effect.*
import cats.syntax.all.*

import java.time.{Instant, LocalDate}
import java.util.UUID

/** Issuing and sending invoices.
  *
  * `issue` builds an invoice from a list of (service, qty) pairs (or raw lines) applying tax per line, computing header
  * totals, and assigning a per-biller monotonically-increasing invoice number. If `deliveryMode = Auto` the invoice is
  * sent immediately and the notification is dispatched.
  */
trait InvoicingService[F[_]] {

  def issue(
      billerId: BusinessId,
      payerId: BusinessId,
      agreementId: Option[AgreementId],
      currency: CurrencyCode,
      lines: List[InvoicingService.LineInput],
      issuedOn: LocalDate,
      dueOn: LocalDate,
      deliveryMode: InvoiceDeliveryMode,
      notes: Option[Body]
  ): F[Either[InvoicingService.Error, Invoice]]

  def send(invoiceId: InvoiceId): F[Either[InvoicingService.Error, Invoice]]
  def cancel(invoiceId: InvoiceId): F[Unit]
}

object InvoicingService {

  /** A single line as posted by the biller. */
  final case class LineInput(
      serviceId: Option[ServiceId],
      description: Title,
      quantity: LineQty,
      unitPriceMinor: AmountMinor,
      taxBps: TaxBps
  )

  sealed trait Error
  object Error {
    case object BillerNotFound extends Error
    case object PayerNotFound extends Error
    case object KybNotApproved extends Error
    case object NoLines extends Error
    case object InvalidDueDate extends Error
    case object NotFound extends Error
    case object AlreadySent extends Error
    case object CurrencyMismatch extends Error
  }

  def make[F[_]: Sync](
      businesses: Businesses[F],
      invoices: Invoices[F],
      notifications: Notifications[F],
      lookupContact: BusinessId => F[Option[Email]]
  ): InvoicingService[F] = new InvoicingService[F] {

    def issue(
        billerId: BusinessId,
        payerId: BusinessId,
        agreementId: Option[AgreementId],
        currency: CurrencyCode,
        lines: List[LineInput],
        issuedOn: LocalDate,
        dueOn: LocalDate,
        deliveryMode: InvoiceDeliveryMode,
        notes: Option[Body]
    ): F[Either[Error, Invoice]] = {

      val precheck: F[Either[Error, Unit]] =
        for {
          b <- businesses.find(billerId)
          p <- businesses.find(payerId)
        } yield (b, p) match {
          case (None, _)                                         => Left(Error.BillerNotFound)
          case (_, None)                                         => Left(Error.PayerNotFound)
          case (Some(b), _) if b.kybStatus != KybStatus.Approved => Left(Error.KybNotApproved)
          case _ if lines.isEmpty                                => Left(Error.NoLines)
          case _ if dueOn.isBefore(issuedOn)                     => Left(Error.InvalidDueDate)
          case _                                                 => Right(())
        }

      precheck.flatMap {
        case Left(err) => Sync[F].pure(Left(err))
        case Right(_)  =>
          for {
            seq <- invoices.nextNumber(billerId)
            number = InvoiceNumber.assume(f"INV-${seq}%06d")
            now <- Sync[F].delay(Instant.now())
            invId = InvoiceId.assume(UUID.randomUUID())
            built = lines.map(buildLine(invId, _))
            netSum = built.map(_.netMinor.value).sum
            taxSum = built.map(_.taxMinor.value).sum
            totSum = built.map(_.totalMinor.value).sum
            inv = Invoice(
              id = invId,
              number = number,
              billerId = billerId,
              payerId = payerId,
              agreementId = agreementId,
              currency = currency,
              netMinor = AmountMinor.applyUnsafe(netSum),
              taxMinor = TaxMinor.applyUnsafe(taxSum),
              totalMinor = AmountMinor.applyUnsafe(totSum),
              issuedOn = issuedOn,
              dueOn = dueOn,
              status = if (deliveryMode == InvoiceDeliveryMode.Auto) InvoiceStatus.Sent else InvoiceStatus.Draft,
              deliveryMode = deliveryMode,
              notes = notes,
              createdAt = now,
              sentAt = if (deliveryMode == InvoiceDeliveryMode.Auto) Some(now) else None,
              paidAt = None
            )
            saved <- invoices.insert(inv, built)
            _ <- if (deliveryMode == InvoiceDeliveryMode.Auto) notify(saved) else Sync[F].unit
          } yield Right(saved)
      }
    }

    def send(invoiceId: InvoiceId): F[Either[Error, Invoice]] =
      invoices.find(invoiceId).flatMap {
        case None                                           => Sync[F].pure(Left(Error.NotFound))
        case Some(inv) if inv.status != InvoiceStatus.Draft => Sync[F].pure(Left(Error.AlreadySent))
        case Some(inv)                                      =>
          for {
            now <- Sync[F].delay(Instant.now())
            _ <- invoices.markSent(invoiceId, now)
            updated = inv.copy(status = InvoiceStatus.Sent, sentAt = Some(now))
            _ <- notify(updated)
          } yield Right(updated)
      }

    def cancel(invoiceId: InvoiceId): F[Unit] =
      invoices.updateStatus(invoiceId, InvoiceStatus.Cancelled)

    private def notify(inv: Invoice): F[Unit] =
      lookupContact(inv.payerId).flatMap(_.traverse_(notifications.emailInvoiceSent(_, inv)))
  }

  /** Compute the four numbers a line carries: net, tax, total. */
  private def buildLine(invoiceId: InvoiceId, in: LineInput): InvoiceLine = {
    val netBd = in.quantity.value * BigDecimal(in.unitPriceMinor.value)
    val netL = netBd.toLong.max(1L) // Iron AmountMinor > 0
    val taxL = (netBd * in.taxBps.value / 10_000L).toLong.max(0L)
    val totL = netL + taxL
    InvoiceLine(
      id = InvoiceLineId.assume(UUID.randomUUID()),
      invoiceId = invoiceId,
      serviceId = in.serviceId,
      description = in.description,
      quantity = in.quantity,
      unitPriceMinor = in.unitPriceMinor,
      taxBps = in.taxBps,
      netMinor = AmountMinor.applyUnsafe(netL),
      taxMinor = TaxMinor.applyUnsafe(taxL),
      totalMinor = AmountMinor.applyUnsafe(totL)
    )
  }
}
