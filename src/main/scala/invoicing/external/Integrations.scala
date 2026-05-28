package invoicing.external

import invoicing.domain.*

import cats.effect.*

// ---------- KYB (Know Your Business) ----------

trait Kyb[F[_]] {
  def submit(business: Business): F[Kyb.Decision]
  def status(ref: String): F[Kyb.Decision]
}

object Kyb {
  enum Decision { case Pending(ref: String); case Approved; case Rejected(reason: String) }

  def sandbox[F[_]: Sync]: F[Kyb[F]] = Sync[F].pure(new Kyb[F] {
    def submit(business: Business): F[Decision] = Sync[F].pure(Decision.Approved)
    def status(ref: String): F[Decision] = Sync[F].pure(Decision.Approved)
  })
}

// ---------- Multi-currency payment rail ----------

/** A single trait covers SEPA / ACH / wire / card debit. The concrete provider (Stripe / Adyen / Modulr / a federation
  * of rails) is chosen per (payer account currency, biller account currency) by the [[PaymentService]].
  */
trait PaymentRail[F[_]] {

  /** Debit the payer's account, credit the biller's account. */
  def transfer(
      from: BankAccount,
      to: BankAccount,
      amount: AmountMinor,
      currency: CurrencyCode,
      reference: String
  ): F[PaymentRail.Receipt]

  def refund(railRef: String, amount: AmountMinor): F[Unit]

  /** Live FX rate for cross-currency settlement. Strictly positive. */
  def fxRate(from: CurrencyCode, to: CurrencyCode): F[BigDecimal]
}

object PaymentRail {
  final case class Receipt(railRef: String, status: PaymentStatus)

  def sandbox[F[_]: Sync]: F[PaymentRail[F]] = Sync[F].pure(new PaymentRail[F] {
    def transfer(
        from: BankAccount,
        to: BankAccount,
        amount: AmountMinor,
        currency: CurrencyCode,
        reference: String
    ): F[Receipt] =
      Sync[F].delay(Receipt("rail_" + java.util.UUID.randomUUID().toString.take(20), PaymentStatus.Captured))
    def refund(railRef: String, amount: AmountMinor): F[Unit] = Sync[F].unit
    def fxRate(from: CurrencyCode, to: CurrencyCode): F[BigDecimal] = Sync[F].pure(BigDecimal(1))
  })
}

// ---------- Notifications (email + push) ----------

trait Notifications[F[_]] {
  def emailInvoiceSent(to: Email, invoice: Invoice): F[Unit]
  def emailInvoicePaid(to: Email, invoice: Invoice): F[Unit]
  def emailAgreementSent(to: Email, agreement: Agreement): F[Unit]
  def emailKybUpdate(to: Email, business: Business): F[Unit]
}

object Notifications {
  def sandbox[F[_]: Sync](log: String => F[Unit]): F[Notifications[F]] = Sync[F].pure(new Notifications[F] {
    def emailInvoiceSent(to: Email, invoice: Invoice): F[Unit] =
      log(s"invoice.sent ${invoice.number.value} -> ${to.value}")
    def emailInvoicePaid(to: Email, invoice: Invoice): F[Unit] =
      log(s"invoice.paid ${invoice.number.value} -> ${to.value}")
    def emailAgreementSent(to: Email, ag: Agreement): F[Unit] = log(s"agreement.sent ${ag.id.value} -> ${to.value}")
    def emailKybUpdate(to: Email, business: Business): F[Unit] =
      log(s"kyb ${business.id.value} -> ${to.value} (${business.kybStatus})")
  })
}

// ---------- Chatbot / support center ----------

trait Chatbot[F[_]] {
  def ask(userId: UserId, question: String): F[String]
}

object Chatbot {
  def sandbox[F[_]: Sync]: F[Chatbot[F]] = Sync[F].pure(new Chatbot[F] {
    def ask(userId: UserId, question: String): F[String] =
      Sync[F].pure("Thanks — a teammate will follow up. (sandbox bot)")
  })
}
