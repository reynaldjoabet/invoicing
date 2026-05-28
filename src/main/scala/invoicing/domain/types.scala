package invoicing.domain

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

import java.util.UUID

// ---------- Identifiers ----------

type UserId = UserId.T
object UserId extends RefinedType[UUID, Pure] {}

type BusinessId = BusinessId.T
object BusinessId extends RefinedType[UUID, Pure] {}

type MembershipId = MembershipId.T
object MembershipId extends RefinedType[UUID, Pure] {}

type BankAccountId = BankAccountId.T
object BankAccountId extends RefinedType[UUID, Pure] {}

type ServiceId = ServiceId.T
object ServiceId extends RefinedType[UUID, Pure] {}

type AgreementId = AgreementId.T
object AgreementId extends RefinedType[UUID, Pure] {}

type InvoiceId = InvoiceId.T
object InvoiceId extends RefinedType[UUID, Pure] {}

type InvoiceLineId = InvoiceLineId.T
object InvoiceLineId extends RefinedType[UUID, Pure] {}

type PaymentId = PaymentId.T
object PaymentId extends RefinedType[UUID, Pure] {}

// ---------- Contact ----------

type EmailConstraint = Not[Blank] & MaxLength[254] & Match["""^[^@\s]+@[^@\s]+\.[^@\s]+$"""]
type Email = Email.T
object Email extends RefinedType[String, EmailConstraint] {}

type FullName = FullName.T
object FullName extends RefinedType[String, Not[Blank] & MaxLength[200]] {}

type PasswordHash = PasswordHash.T
object PasswordHash extends RefinedType[String, Not[Blank] & MaxLength[120]] {}

// ---------- Business identifiers ----------

/** Legal business name. */
type BusinessName = BusinessName.T
object BusinessName extends RefinedType[String, Not[Blank] & MaxLength[200]] {}

/** EU VAT number: country prefix + 8..12 alphanumerics. Real validators check country-specific length; we accept the
  * loose superset and let the KYB provider reject obviously-bad ones.
  */
type VatNumber = VatNumber.T
object VatNumber extends RefinedType[String, Match["^[A-Z]{2}[A-Z0-9]{8,12}$"]] {}

/** US EIN: 9 digits with optional dash (XX-XXXXXXX). Stored without the dash. */
type EinNumber = EinNumber.T
object EinNumber extends RefinedType[String, FixedLength[9] & Match["^[0-9]{9}$"]] {}

/** Country code, ISO-3166-1 alpha-2. */
type CountryCode = CountryCode.T
object CountryCode extends RefinedType[String, Match["^[A-Z]{2}$"]] {
  val US: CountryCode = CountryCode.applyUnsafe("US")
  val GB: CountryCode = CountryCode.applyUnsafe("GB")
  val DE: CountryCode = CountryCode.applyUnsafe("DE")
}

// ---------- Currency & money ----------

/** ISO-4217 currency code. */
type CurrencyCode = CurrencyCode.T
object CurrencyCode extends RefinedType[String, Match["^[A-Z]{3}$"]] {
  val USD: CurrencyCode = CurrencyCode.applyUnsafe("USD")
  val EUR: CurrencyCode = CurrencyCode.applyUnsafe("EUR")
  val GBP: CurrencyCode = CurrencyCode.applyUnsafe("GBP")
}

/** Money in minor units. Strictly positive — invoice/line/payment amounts never carry sign; direction is implicit in
  * the entity.
  */
type AmountMinor = AmountMinor.T
object AmountMinor extends RefinedType[Long, Positive] {}

/** Money in minor units, *zero or positive*. Used for tax amounts where a 0% rate is legitimate (e.g. cross-border B2B
  * reverse charge).
  */
type TaxMinor = TaxMinor.T
object TaxMinor extends RefinedType[Long, GreaterEqual[0]] {}

/** Quantity for an invoice line: positive decimal (3 hours, 1.5 widgets, ...). */
type LineQty = LineQty.T
object LineQty extends RefinedType[BigDecimal, Pure] {
  def positive(b: BigDecimal): Either[String, LineQty] =
    if (b > 0) Right(LineQty.assume(b)) else Left(s"qty must be > 0: $b")
}

/** Tax rate in basis points (0..10_000 = 0%..100%). */
type TaxBps = TaxBps.T
object TaxBps extends RefinedType[Int, GreaterEqual[0] & LessEqual[10_000]] {}

// ---------- Banking ----------

/** IBAN: 2 letters + 2 check digits + up to 30 alphanumerics. Validated by shape here; the mod-97 check digit
  * verification happens at the boundary.
  */
type Iban = Iban.T
object Iban extends RefinedType[String, MinLength[15] & MaxLength[34] & Match["^[A-Z]{2}[0-9]{2}[A-Z0-9]+$"]] {}

/** US routing number: 9 digits. */
type RoutingNumber = RoutingNumber.T
object RoutingNumber extends RefinedType[String, FixedLength[9] & Match["^[0-9]{9}$"]] {}

/** Account number — opaque, just digits/letters, capped length. */
type AccountNumber = AccountNumber.T
object AccountNumber extends RefinedType[String, MinLength[6] & MaxLength[34] & Match["^[A-Z0-9]+$"]] {}

/** SWIFT / BIC: 8 or 11 alphanumerics. */
type Bic = Bic.T
object Bic extends RefinedType[String, (MinLength[8] & MaxLength[11]) & Match["^[A-Z0-9]{8}([A-Z0-9]{3})?$"]] {}

// ---------- Document numbers & text ----------

/** Invoice number visible to clients. Per-biller monotonically increasing string; we allow alphanumerics, dashes, and
  * slashes (common in EU formats).
  */
type InvoiceNumber = InvoiceNumber.T
object InvoiceNumber extends RefinedType[String, MinLength[1] & MaxLength[40] & Match["^[A-Z0-9][A-Z0-9/-]{0,39}$"]] {}

type Title = Title.T
object Title extends RefinedType[String, Not[Blank] & MaxLength[200]] {}

type Body = Body.T
object Body extends RefinedType[String, Not[Blank] & MaxLength[8000]] {}

// ---------- Enums ----------

enum BusinessRole {
  case Owner, Admin, Member
}
object BusinessRole {
  def parse(s: String): Either[String, BusinessRole] = s.toLowerCase match {
    case "owner"  => Right(Owner)
    case "admin"  => Right(Admin)
    case "member" => Right(Member)
    case o        => Left(s"unknown role: $o")
  }
  def render(r: BusinessRole): String = r.toString.toLowerCase
}

enum KybStatus {
  case NotStarted, Pending, Approved, Rejected
}

enum ServiceKind {
  case OneTime, Recurring
}

enum RecurringInterval {
  case Weekly, Monthly, Quarterly, Annual
}

enum AgreementStatus {
  case Draft, Sent, Signed, Rejected, Terminated
}

enum InvoiceStatus {
  case Draft, Sent, Paid, PartiallyPaid, Cancelled, Overdue
}

enum InvoiceDeliveryMode {
  case Manual, Auto
}

/** Mode the *payer* configures for incoming invoices. */
enum AutoPaymentMode {
  case ManualApproval, AutoDebit
}

enum PaymentStatus {
  case Initiated, Authorised, Captured, Failed, Refunded
}

enum BankAccountType {
  case Iban, UsAch, Other
}
