package invoicing.http

import invoicing.domain.*
import io.circe.*
import io.circe.generic.semiauto.*
import io.github.iltotore.iron.circe.given

object Json {

  // ---- enums ----
  given Encoder[KybStatus] = Encoder.encodeString.contramap(_.toString.toLowerCase)
  given Encoder[BusinessRole] = Encoder.encodeString.contramap(BusinessRole.render)
  given Decoder[BusinessRole] = Decoder.decodeString.emap(BusinessRole.parse)
  given Encoder[ServiceKind] = Encoder.encodeString.contramap {
    case ServiceKind.OneTime   => "one_time"
    case ServiceKind.Recurring => "recurring"
  }
  given Decoder[ServiceKind] = Decoder.decodeString.emap {
    case "one_time"  => Right(ServiceKind.OneTime)
    case "recurring" => Right(ServiceKind.Recurring)
    case o           => Left(s"unknown service kind: $o")
  }
  given Encoder[RecurringInterval] = Encoder.encodeString.contramap(_.toString.toLowerCase)
  given Decoder[RecurringInterval] = Decoder.decodeString.emap {
    case "weekly"    => Right(RecurringInterval.Weekly)
    case "monthly"   => Right(RecurringInterval.Monthly)
    case "quarterly" => Right(RecurringInterval.Quarterly)
    case "annual"    => Right(RecurringInterval.Annual)
    case o           => Left(s"unknown interval: $o")
  }
  given Encoder[AgreementStatus] = Encoder.encodeString.contramap(_.toString.toLowerCase)
  given Encoder[InvoiceStatus] = Encoder.encodeString.contramap {
    case InvoiceStatus.Draft         => "draft"
    case InvoiceStatus.Sent          => "sent"
    case InvoiceStatus.Paid          => "paid"
    case InvoiceStatus.PartiallyPaid => "partially_paid"
    case InvoiceStatus.Cancelled     => "cancelled"
    case InvoiceStatus.Overdue       => "overdue"
  }
  given Encoder[InvoiceDeliveryMode] = Encoder.encodeString.contramap(_.toString.toLowerCase)
  given Decoder[InvoiceDeliveryMode] = Decoder.decodeString.emap {
    case "manual" => Right(InvoiceDeliveryMode.Manual)
    case "auto"   => Right(InvoiceDeliveryMode.Auto)
    case o        => Left(s"unknown delivery mode: $o")
  }
  given Encoder[AutoPaymentMode] = Encoder.encodeString.contramap {
    case AutoPaymentMode.ManualApproval => "manual_approval"
    case AutoPaymentMode.AutoDebit      => "auto_debit"
  }
  given Encoder[PaymentStatus] = Encoder.encodeString.contramap(_.toString.toLowerCase)
  given Encoder[BankAccountType] = Encoder.encodeString.contramap {
    case BankAccountType.Iban  => "iban"
    case BankAccountType.UsAch => "us_ach"
    case BankAccountType.Other => "other"
  }
  given Decoder[BankAccountType] = Decoder.decodeString.emap {
    case "iban"   => Right(BankAccountType.Iban)
    case "us_ach" => Right(BankAccountType.UsAch)
    case "other"  => Right(BankAccountType.Other)
    case o        => Left(s"unknown bank account type: $o")
  }

  // ---- aggregate encoders (never serialise password hashes) ----

  given Encoder[User] = Encoder.instance(u =>
    io.circe.Json.obj(
      "id" -> Encoder.encodeString.apply(u.id.value.toString),
      "email" -> Encoder.encodeString.apply(u.email.value),
      "fullName" -> Encoder.encodeString.apply(u.fullName.value),
      "createdAt" -> Encoder[java.time.Instant].apply(u.createdAt)
    )
  )
  val userEncoder: Encoder[User] = summon[Encoder[User]]

  given Encoder[Business] = deriveEncoder
  given Encoder[Membership] = deriveEncoder
  given Encoder[BankAccount] = deriveEncoder
  given Encoder[Service] = deriveEncoder
  given Encoder[Agreement] = deriveEncoder
  given Encoder[InvoiceLine] = deriveEncoder
  given Encoder[Invoice] = deriveEncoder
  given Encoder[Payment] = deriveEncoder

  // ---- request bodies ----

  final case class SignupBody(email: Email, password: String, fullName: FullName)
  given Decoder[SignupBody] = deriveDecoder

  final case class LoginBody(email: Email, password: String)
  given Decoder[LoginBody] = deriveDecoder

  final case class CreateBusinessBody(
      name: BusinessName,
      country: CountryCode,
      vat: Option[VatNumber],
      ein: Option[EinNumber],
      defaultCurrency: CurrencyCode
  )
  given Decoder[CreateBusinessBody] = deriveDecoder

  final case class InviteMemberBody(email: Email, role: BusinessRole)
  given Decoder[InviteMemberBody] = deriveDecoder

  final case class CreateBankAccountBody(
      accountType: BankAccountType,
      holderName: FullName,
      iban: Option[Iban],
      bic: Option[Bic],
      routingNumber: Option[RoutingNumber],
      accountNumber: Option[AccountNumber],
      currency: CurrencyCode,
      isDefault: Boolean
  )
  given Decoder[CreateBankAccountBody] = deriveDecoder

  final case class CreateServiceBody(
      title: Title,
      description: Body,
      kind: ServiceKind,
      interval: Option[RecurringInterval],
      unitPriceMinor: AmountMinor,
      currency: CurrencyCode,
      taxBps: TaxBps
  )
  given Decoder[CreateServiceBody] = deriveDecoder

  final case class CreateAgreementBody(
      payerId: BusinessId,
      title: Title,
      body: Body,
      currency: CurrencyCode,
      serviceIds: List[ServiceId]
  )
  given Decoder[CreateAgreementBody] = deriveDecoder

  final case class InvoiceLineBody(
      serviceId: Option[ServiceId],
      description: Title,
      quantity: LineQty,
      unitPriceMinor: AmountMinor,
      taxBps: TaxBps
  )
  given Decoder[InvoiceLineBody] = deriveDecoder

  final case class IssueInvoiceBody(
      payerId: BusinessId,
      agreementId: Option[AgreementId],
      currency: CurrencyCode,
      lines: List[InvoiceLineBody],
      issuedOn: java.time.LocalDate,
      dueOn: java.time.LocalDate,
      deliveryMode: InvoiceDeliveryMode,
      notes: Option[Body]
  )
  given Decoder[IssueInvoiceBody] = deriveDecoder

  final case class PayInvoiceBody(bankAccountId: BankAccountId)
  given Decoder[PayInvoiceBody] = deriveDecoder
}
