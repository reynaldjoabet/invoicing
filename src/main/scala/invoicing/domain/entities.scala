package invoicing.domain

import java.time.{Instant, LocalDate}

// ---------- People & companies ----------

final case class User(
    id: UserId,
    email: Email,
    passwordHash: PasswordHash,
    fullName: FullName,
    createdAt: Instant
)

final case class Business(
    id: BusinessId,
    name: BusinessName,
    country: CountryCode,
    /** EU VAT for European businesses, US EIN otherwise. Exactly one is set. */
    vat: Option[VatNumber],
    ein: Option[EinNumber],
    defaultCurrency: CurrencyCode,
    kybStatus: KybStatus,
    createdAt: Instant
)

/** Many-to-many: a User can represent multiple Businesses. */
final case class Membership(
    id: MembershipId,
    userId: UserId,
    businessId: BusinessId,
    role: BusinessRole,
    invitedAt: Instant,
    acceptedAt: Option[Instant]
)

// ---------- Bank accounts ----------

final case class BankAccount(
    id: BankAccountId,
    businessId: BusinessId,
    accountType: BankAccountType,
    holderName: FullName,
    /** Populated for IBAN-style accounts (EU + many others). */
    iban: Option[Iban],
    bic: Option[Bic],
    /** Populated for US-style ACH. */
    routingNumber: Option[RoutingNumber],
    accountNumber: Option[AccountNumber],
    currency: CurrencyCode,
    isDefault: Boolean,
    createdAt: Instant
)

// ---------- Services & agreements ----------

final case class Service(
    id: ServiceId,
    billerId: BusinessId,
    title: Title,
    description: Body,
    kind: ServiceKind,
    /** Set when `kind` is `Recurring`. */
    interval: Option[RecurringInterval],
    /** Unit price in minor units of `currency`. */
    unitPriceMinor: AmountMinor,
    currency: CurrencyCode,
    /** Default tax rate for invoice lines (overridable per line). */
    taxBps: TaxBps,
    archived: Boolean,
    createdAt: Instant
)

final case class Agreement(
    id: AgreementId,
    billerId: BusinessId,
    payerId: BusinessId,
    title: Title,
    body: Body,
    currency: CurrencyCode,
    status: AgreementStatus,
    /** Services covered by this agreement. */
    serviceIds: List[ServiceId],
    sentAt: Option[Instant],
    signedAt: Option[Instant],
    terminatedAt: Option[Instant],
    createdAt: Instant
)

// ---------- Invoices ----------

final case class InvoiceLine(
    id: InvoiceLineId,
    invoiceId: InvoiceId,
    /** Optional reference to the service that produced this line. */
    serviceId: Option[ServiceId],
    description: Title,
    quantity: LineQty,
    unitPriceMinor: AmountMinor,
    taxBps: TaxBps,
    /** Convenience denormalisation: net = qty * unitPriceMinor, tax = net * taxBps/10_000. */
    netMinor: AmountMinor,
    taxMinor: TaxMinor,
    totalMinor: AmountMinor
)

final case class Invoice(
    id: InvoiceId,
    number: InvoiceNumber,
    billerId: BusinessId,
    payerId: BusinessId,
    agreementId: Option[AgreementId],
    currency: CurrencyCode,
    /** Header totals computed across all lines. */
    netMinor: AmountMinor,
    taxMinor: TaxMinor,
    totalMinor: AmountMinor,
    issuedOn: LocalDate,
    dueOn: LocalDate,
    status: InvoiceStatus,
    deliveryMode: InvoiceDeliveryMode,
    notes: Option[Body],
    createdAt: Instant,
    sentAt: Option[Instant],
    paidAt: Option[Instant]
)

// ---------- Payments ----------

final case class Payment(
    id: PaymentId,
    invoiceId: InvoiceId,
    /** Which payer bank account was used. */
    bankAccountId: BankAccountId,
    amountMinor: AmountMinor,
    currency: CurrencyCode,
    /** Multi-currency settlement: rate at which we converted *if* the payer's account currency differs from the invoice
      * currency.
      */
    fxRateApplied: Option[BigDecimal],
    status: PaymentStatus,
    /** Upstream rail reference (e.g. SEPA end-to-end id, ACH trace number). */
    railRef: Option[String],
    failureReason: Option[String],
    initiatedAt: Instant,
    completedAt: Option[Instant]
)

// ---------- Payer-side preferences ----------

/** One row per (payer, biller) pair: how the payer wants to handle invoices from this specific biller.
  */
final case class PaymentPreference(
    payerId: BusinessId,
    billerId: BusinessId,
    mode: AutoPaymentMode,
    defaultBankAccountId: Option[BankAccountId]
)

// ---------- Cash flow ----------

final case class CashflowEntry(
    businessId: BusinessId,
    period: java.time.YearMonth,
    inflowMinor: Long,
    outflowMinor: Long,
    currency: CurrencyCode
) {
  def netMinor: Long = inflowMinor - outflowMinor
}
