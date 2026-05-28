package invoicing.db

import invoicing.domain.*

import skunk.*
import skunk.codec.all.*
import org.typelevel.twiddles.syntax.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.skunk.*
import java.time.{Instant, LocalDate, ZoneOffset}

object Codecs {

  // ---- identifier codecs ----
  val userId: Codec[UserId] = uuid.imap(UserId.assume)(_.value)
  val businessId: Codec[BusinessId] = uuid.imap(BusinessId.assume)(_.value)
  val membershipId: Codec[MembershipId] = uuid.imap(MembershipId.assume)(_.value)
  val bankAccountId: Codec[BankAccountId] = uuid.imap(BankAccountId.assume)(_.value)
  val serviceId: Codec[ServiceId] = uuid.imap(ServiceId.assume)(_.value)
  val agreementId: Codec[AgreementId] = uuid.imap(AgreementId.assume)(_.value)
  val invoiceId: Codec[InvoiceId] = uuid.imap(InvoiceId.assume)(_.value)
  val invoiceLineId: Codec[InvoiceLineId] = uuid.imap(InvoiceLineId.assume)(_.value)
  val paymentId: Codec[PaymentId] = uuid.imap(PaymentId.assume)(_.value)

  // ---- scalars ----
  val email: Codec[Email] = varchar(254).refined[EmailConstraint].imap(Email.assume)(_.value)
  val passwordHash: Codec[PasswordHash] = varchar(120).imap(PasswordHash.assume)(_.value)
  val fullName: Codec[FullName] = varchar(200).refined[Not[Blank] & MaxLength[200]].imap(FullName.assume)(_.value)

  val businessName: Codec[BusinessName] =
    varchar(200).refined[Not[Blank] & MaxLength[200]].imap(BusinessName.assume)(_.value)
  val countryCode: Codec[CountryCode] = bpchar(2).refined[Match["^[A-Z]{2}$"]].imap(CountryCode.assume)(_.value)
  val vatNumber: Codec[VatNumber] =
    varchar(16).refined[Match["^[A-Z]{2}[A-Z0-9]{8,12}$"]].imap(VatNumber.assume)(_.value)
  val einNumber: Codec[EinNumber] =
    bpchar(9).refined[FixedLength[9] & Match["^[0-9]{9}$"]].imap(EinNumber.assume)(_.value)

  val currency: Codec[CurrencyCode] = bpchar(3).refined[Match["^[A-Z]{3}$"]].imap(CurrencyCode.assume)(_.value)
  val amountMinor: Codec[AmountMinor] = int8.refined[Positive].imap(AmountMinor.assume)(_.value)
  val taxMinor: Codec[TaxMinor] = int8.refined[GreaterEqual[0]].imap(TaxMinor.assume)(_.value)
  val lineQty: Codec[LineQty] = numeric.eimap[LineQty](LineQty.positive)(_.value)
  val taxBps: Codec[TaxBps] = int4.refined[GreaterEqual[0] & LessEqual[10_000]].imap(TaxBps.assume)(_.value)

  val iban: Codec[Iban] =
    varchar(34).refined[MinLength[15] & MaxLength[34] & Match["^[A-Z]{2}[0-9]{2}[A-Z0-9]+$"]].imap(Iban.assume)(_.value)
  val bic: Codec[Bic] =
    varchar(11).refined[(MinLength[8] & MaxLength[11]) & Match["^[A-Z0-9]{8}([A-Z0-9]{3})?$"]].imap(Bic.assume)(_.value)
  val routingNumber: Codec[RoutingNumber] =
    bpchar(9).refined[FixedLength[9] & Match["^[0-9]{9}$"]].imap(RoutingNumber.assume)(_.value)
  val accountNumber: Codec[AccountNumber] =
    varchar(34).refined[MinLength[6] & MaxLength[34] & Match["^[A-Z0-9]+$"]].imap(AccountNumber.assume)(_.value)

  val invoiceNumber: Codec[InvoiceNumber] =
    varchar(40)
      .refined[MinLength[1] & MaxLength[40] & Match["^[A-Z0-9][A-Z0-9/-]{0,39}$"]]
      .imap(InvoiceNumber.assume)(_.value)

  val title: Codec[Title] = varchar(200).refined[Not[Blank] & MaxLength[200]].imap(Title.assume)(_.value)
  val body: Codec[Body] = text.refined[Not[Blank] & MaxLength[8000]].imap(Body.assume)(_.value)

  // ---- enums ----
  val businessRole: Codec[BusinessRole] = varchar(16).eimap(BusinessRole.parse)(BusinessRole.render)

  val kybStatus: Codec[KybStatus] = varchar(16).eimap[KybStatus] {
    case "not_started" => Right(KybStatus.NotStarted)
    case "pending"     => Right(KybStatus.Pending)
    case "approved"    => Right(KybStatus.Approved)
    case "rejected"    => Right(KybStatus.Rejected)
    case o             => Left(s"unknown kyb status: $o")
  } {
    case KybStatus.NotStarted => "not_started"
    case KybStatus.Pending    => "pending"
    case KybStatus.Approved   => "approved"
    case KybStatus.Rejected   => "rejected"
  }

  val serviceKind: Codec[ServiceKind] = varchar(16).eimap[ServiceKind] {
    case "one_time"  => Right(ServiceKind.OneTime)
    case "recurring" => Right(ServiceKind.Recurring)
    case o           => Left(s"unknown service kind: $o")
  } {
    case ServiceKind.OneTime   => "one_time"
    case ServiceKind.Recurring => "recurring"
  }

  val recurringInterval: Codec[RecurringInterval] = varchar(16).eimap[RecurringInterval] {
    case "weekly"    => Right(RecurringInterval.Weekly)
    case "monthly"   => Right(RecurringInterval.Monthly)
    case "quarterly" => Right(RecurringInterval.Quarterly)
    case "annual"    => Right(RecurringInterval.Annual)
    case o           => Left(s"unknown interval: $o")
  } { _.toString.toLowerCase }

  val agreementStatus: Codec[AgreementStatus] = varchar(16).eimap[AgreementStatus] {
    case "draft"      => Right(AgreementStatus.Draft)
    case "sent"       => Right(AgreementStatus.Sent)
    case "signed"     => Right(AgreementStatus.Signed)
    case "rejected"   => Right(AgreementStatus.Rejected)
    case "terminated" => Right(AgreementStatus.Terminated)
    case o            => Left(s"unknown agreement status: $o")
  } { _.toString.toLowerCase }

  val invoiceStatus: Codec[InvoiceStatus] = varchar(20).eimap[InvoiceStatus] {
    case "draft"          => Right(InvoiceStatus.Draft)
    case "sent"           => Right(InvoiceStatus.Sent)
    case "paid"           => Right(InvoiceStatus.Paid)
    case "partially_paid" => Right(InvoiceStatus.PartiallyPaid)
    case "cancelled"      => Right(InvoiceStatus.Cancelled)
    case "overdue"        => Right(InvoiceStatus.Overdue)
    case o                => Left(s"unknown invoice status: $o")
  } {
    case InvoiceStatus.Draft         => "draft"
    case InvoiceStatus.Sent          => "sent"
    case InvoiceStatus.Paid          => "paid"
    case InvoiceStatus.PartiallyPaid => "partially_paid"
    case InvoiceStatus.Cancelled     => "cancelled"
    case InvoiceStatus.Overdue       => "overdue"
  }

  val invoiceDeliveryMode: Codec[InvoiceDeliveryMode] = varchar(8).eimap[InvoiceDeliveryMode] {
    case "manual" => Right(InvoiceDeliveryMode.Manual)
    case "auto"   => Right(InvoiceDeliveryMode.Auto)
    case o        => Left(s"unknown delivery mode: $o")
  } { _.toString.toLowerCase }

  val autoPaymentMode: Codec[AutoPaymentMode] = varchar(16).eimap[AutoPaymentMode] {
    case "manual_approval" => Right(AutoPaymentMode.ManualApproval)
    case "auto_debit"      => Right(AutoPaymentMode.AutoDebit)
    case o                 => Left(s"unknown payment mode: $o")
  } {
    case AutoPaymentMode.ManualApproval => "manual_approval"
    case AutoPaymentMode.AutoDebit      => "auto_debit"
  }

  val paymentStatus: Codec[PaymentStatus] = varchar(16).eimap[PaymentStatus] {
    case "initiated"  => Right(PaymentStatus.Initiated)
    case "authorised" => Right(PaymentStatus.Authorised)
    case "captured"   => Right(PaymentStatus.Captured)
    case "failed"     => Right(PaymentStatus.Failed)
    case "refunded"   => Right(PaymentStatus.Refunded)
    case o            => Left(s"unknown payment status: $o")
  } { _.toString.toLowerCase }

  val bankAccountType: Codec[BankAccountType] = varchar(16).eimap[BankAccountType] {
    case "iban"   => Right(BankAccountType.Iban)
    case "us_ach" => Right(BankAccountType.UsAch)
    case "other"  => Right(BankAccountType.Other)
    case o        => Left(s"unknown bank account type: $o")
  } {
    case BankAccountType.Iban  => "iban"
    case BankAccountType.UsAch => "us_ach"
    case BankAccountType.Other => "other"
  }

  private val instant: Codec[java.time.Instant] =
    timestamptz.imap(_.toInstant)(_.atOffset(ZoneOffset.UTC))

  // ---- aggregates ----
  // skunk 1.x's `*:` chain produces right-nested Tuple2 pairs `(A, (B, (C, ...)))`.
  // Case class Mirrors are flat tuples `(A, B, C, ...)`, so `.to[T]` cannot derive
  // an Iso for >2 fields. We bridge with explicit `imap` and nested destructuring.

  val user: Codec[User] =
    (userId *: email *: passwordHash *: fullName *: instant).imap { case (id, (em, (pw, (fn, ca)))) =>
      User(id, em, pw, fn, ca)
    }(u => (u.id, (u.email, (u.passwordHash, (u.fullName, u.createdAt)))))

  val business: Codec[Business] =
    (businessId *: businessName *: countryCode *: vatNumber.opt *: einNumber.opt *: currency *: kybStatus *: instant)
      .imap { case (id, (nm, (co, (vt, (en, (cu, (ks, ca))))))) =>
        Business(id, nm, co, vt, en, cu, ks, ca)
      }(b => (b.id, (b.name, (b.country, (b.vat, (b.ein, (b.defaultCurrency, (b.kybStatus, b.createdAt))))))))

  val membership: Codec[Membership] =
    (membershipId *: userId *: businessId *: businessRole *: instant *: instant.opt).imap {
      case (id, (uid, (bid, (r, (ia, aa))))) => Membership(id, uid, bid, r, ia, aa)
    }(m => (m.id, (m.userId, (m.businessId, (m.role, (m.invitedAt, m.acceptedAt))))))

  val bankAccount: Codec[BankAccount] =
    (bankAccountId *: businessId *: bankAccountType *: fullName *:
      iban.opt *: bic.opt *: routingNumber.opt *: accountNumber.opt *:
      currency *: bool *: instant).imap { case (id, (bid, (at, (hn, (ib, (bi, (rn, (an, (cu, (df, ca)))))))))) =>
      BankAccount(id, bid, at, hn, ib, bi, rn, an, cu, df, ca)
    }(a =>
      (
        a.id,
        (
          a.businessId,
          (
            a.accountType,
            (
              a.holderName,
              (a.iban, (a.bic, (a.routingNumber, (a.accountNumber, (a.currency, (a.isDefault, a.createdAt))))))
            )
          )
        )
      )
    )

  val service: Codec[Service] =
    (serviceId *: businessId *: title *: body *: serviceKind *: recurringInterval.opt *:
      amountMinor *: currency *: taxBps *: bool *: instant).imap {
      case (id, (bid, (t, (d, (k, (iv, (up, (cu, (tb, (ar, ca)))))))))) =>
        Service(id, bid, t, d, k, iv, up, cu, tb, ar, ca)
    }(s =>
      (
        s.id,
        (
          s.billerId,
          (
            s.title,
            (
              s.description,
              (s.kind, (s.interval, (s.unitPriceMinor, (s.currency, (s.taxBps, (s.archived, s.createdAt))))))
            )
          )
        )
      )
    )

  // Agreement is stored normalised with its services in `agreement_services`;
  // we hydrate the `serviceIds` list in the repository layer rather than in
  // the codec.
  val agreementCore: Codec[
    (
        AgreementId,
        BusinessId,
        BusinessId,
        Title,
        Body,
        CurrencyCode,
        AgreementStatus,
        Option[java.time.Instant],
        Option[java.time.Instant],
        Option[java.time.Instant],
        java.time.Instant
    )
  ] =
    (agreementId *: businessId *: businessId *: title *: body *: currency *: agreementStatus *:
      instant.opt *: instant.opt *: instant.opt *: instant).imap {
      case (id, (bid, (pid, (t, (b, (cu, (st, (sa, (sgn, (ta, ca)))))))))) =>
        (id, bid, pid, t, b, cu, st, sa, sgn, ta, ca)
    } { case (id, bid, pid, t, b, cu, st, sa, sgn, ta, ca) =>
      (id, (bid, (pid, (t, (b, (cu, (st, (sa, (sgn, (ta, ca))))))))))
    }

  val invoice: Codec[Invoice] =
    (invoiceId *: invoiceNumber *: businessId *: businessId *: agreementId.opt *: currency *:
      amountMinor *: taxMinor *: amountMinor *: date *: date *:
      invoiceStatus *: invoiceDeliveryMode *: body.opt *: instant *: instant.opt *: instant.opt).imap {
      case (id, (num, (bid, (pid, (aid, (cu, (nm, (tm, (tot, (io, (du, (st, (dm, (nt, (ca, (sa, pa)))))))))))))))) =>
        Invoice(id, num, bid, pid, aid, cu, nm, tm, tot, io, du, st, dm, nt, ca, sa, pa)
    }(i =>
      (
        i.id,
        (
          i.number,
          (
            i.billerId,
            (
              i.payerId,
              (
                i.agreementId,
                (
                  i.currency,
                  (
                    i.netMinor,
                    (
                      i.taxMinor,
                      (
                        i.totalMinor,
                        (
                          i.issuedOn,
                          (i.dueOn, (i.status, (i.deliveryMode, (i.notes, (i.createdAt, (i.sentAt, i.paidAt))))))
                        )
                      )
                    )
                  )
                )
              )
            )
          )
        )
      )
    )

  val invoiceLine: Codec[InvoiceLine] =
    (invoiceLineId *: invoiceId *: serviceId.opt *: title *: lineQty *: amountMinor *: taxBps *:
      amountMinor *: taxMinor *: amountMinor).imap { case (id, (iid, (sid, (de, (qt, (up, (tb, (nm, (tm, tot))))))))) =>
      InvoiceLine(id, iid, sid, de, qt, up, tb, nm, tm, tot)
    }(l =>
      (
        l.id,
        (
          l.invoiceId,
          (
            l.serviceId,
            (l.description, (l.quantity, (l.unitPriceMinor, (l.taxBps, (l.netMinor, (l.taxMinor, l.totalMinor))))))
          )
        )
      )
    )

  val payment: Codec[Payment] =
    (paymentId *: invoiceId *: bankAccountId *: amountMinor *: currency *:
      numeric.opt *: paymentStatus *: varchar(128).opt *: varchar(512).opt *:
      instant *: instant.opt).imap { case (id, (iid, (bid, (am, (cu, (fx, (st, (rr, (fr, (ia, ca)))))))))) =>
      Payment(id, iid, bid, am, cu, fx, st, rr, fr, ia, ca)
    }(p =>
      (
        p.id,
        (
          p.invoiceId,
          (
            p.bankAccountId,
            (
              p.amountMinor,
              (
                p.currency,
                (p.fxRateApplied, (p.status, (p.railRef, (p.failureReason, (p.initiatedAt, p.completedAt)))))
              )
            )
          )
        )
      )
    )
}
