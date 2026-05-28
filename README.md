# B2B Invoicing
A Scala 3 backend for the B2B invoicing + billing platform from the brief.
Models the biller / payer / admin segmentation, multi-currency settlement,
recurring services, agreements, KYB-gated onboarding, and per-pair payment
preferences (auto-debit vs manual approval).

## Stack

- Scala 3.3.6 — **braces-only** (`-no-indent`)
- [Iron](https://github.com/Iltotore/iron) + `iron-skunk` + `iron-circe`
- [Skunk](https://typelevel.org/skunk/) on Postgres
- http4s (Ember) + Circe + Cats Effect 3
- bcrypt, log4cats / logback

## Iron earning its keep

```scala
type Iban           = String :| (MinLength[15] & MaxLength[34] & Match["^[A-Z]{2}[0-9]{2}[A-Z0-9]+$"])
type RoutingNumber  = String :| (FixedLength[9] & Match["^[0-9]{9}$"])
type VatNumber      = String :| Match["^[A-Z]{2}[A-Z0-9]{8,12}$"]
type EinNumber      = String :| (FixedLength[9] & Match["^[0-9]{9}$"])
type CurrencyCode   = String :| Match["^[A-Z]{3}$"]
type InvoiceNumber  = String :| (MinLength[1] & MaxLength[40] & Match["^[A-Z0-9][A-Z0-9/-]{0,39}$"])
type AmountMinor    = Long   :| Positive          // invoice net/total, line amounts, payment amounts
type TaxMinor       = Long   :| GreaterEqual[0]   // tax fields (zero is legal: reverse-charge B2B)
type TaxBps         = Int    :| (GreaterEqual[0] & LessEqual[10_000])
```

A `BankAccount.iban` field literally cannot store `"oops"`; a `Payment.amount`
cannot be `0` or negative; the schema CHECK constraints repeat the same rules
so the DB rejects whatever Iron somehow lets through (defence in depth across
two boundaries).

The split between `AmountMinor` (strictly > 0) and `TaxMinor` (≥ 0) is exactly
the kind of thing Iron makes you face up to: tax can be zero, *amounts* cannot.

## How the "mode switch" works

The brief: *"users can switch between bill payer / biller mode"*. We model this
as a header rather than two user records:

- `X-User-Id` — the authenticated principal
- `X-Business-Id` — which Business the user is acting on behalf of

A `Membership(user, business)` join table makes the user → business
relationship many-to-many. Every business-scoped route looks up the membership
and rejects (403) if the principal isn't a member. Switching modes is just
switching the `X-Business-Id` header — the same User can be biller in one
request and payer in the next.

## Multi-currency settlement

`PaymentService.runRail` compares the payer's selected bank account currency
to the invoice currency. If they differ it asks the rail for an FX rate and
records it on the `payments` row (`fx_rate_applied`). The biller always lands
funds in the invoice currency — the FX leg sits between the payer's debit and
the biller's credit, exactly where a real rail would do it. See


## API

### Auth

```
POST /auth/signup       { email, password, fullName }
POST /auth/login
```

### Onboarding

```
POST /businesses                       { name, country, vat?, ein?, defaultCurrency }
POST /businesses/{id}/kyb              # triggers KYB; status flips Pending -> Approved/Rejected
POST /businesses/{id}/members          { email, role }
POST /memberships/{id}/accept
```

### Banking

```
POST /bank-accounts
GET  /bank-accounts
```

### Biller

```
POST /biller/services                  one-time or recurring
GET  /biller/services
POST /biller/services/{id}/archive
POST /biller/agreements                draft + serviceIds[]
POST /biller/agreements/{id}/send
GET  /biller/agreements
POST /biller/invoices                  lines, dates, deliveryMode: manual|auto
POST /biller/invoices/{id}/send
POST /biller/invoices/{id}/cancel
GET  /biller/invoices
```

### Payer

```
GET  /payer/agreements
POST /payer/agreements/{id}/sign
POST /payer/agreements/{id}/reject
GET  /payer/invoices
POST /payer/invoices/{id}/pay          { bankAccountId }
GET  /payer/invoices/{id}/payments
```

### Admin

```
GET  /admin/users/{userId}
```

## Running

```bash
createdb invoicing
psql invoicing < src/main/resources/db/schema.sql

export DB_USER=postgres
export DB_PASSWORD=postgres
export DB_NAME=invoicing
sbt run
```

All integrations (KYB, payment rail, notifications, chatbot) wire to sandbox
implementations in `Main.scala`. Replace each one when you bring real
credentials online — each is a single trait.

## Intentional seams

- **JWT auth middleware**: the routes trust `X-User-Id` + `X-Business-Id`.
  Wrap with a middleware that validates a bearer token in production.
- **Webhook ingress**: Cloudpayments / Stripe / Modulr / Veratad-style webhook
  routes are not yet wired. Inbound callbacks should update payment / KYB
  status and bridge to the existing services.
- **Recurring invoice job**: `RecurringInvoiceJob` has the shape but no SQL
  query and no scheduler binding. Drop in a cron/fs2.Scheduler trigger.
- **Per-pair payment preferences**: the table is modelled and the `payAuto`
  path on `PaymentService` is implemented; what's missing is the trigger that
  reads the pref when an invoice is `Sent` (likely from `InvoicingService.send`
  or a queue worker).
- **Reminder cadence + overdue sweep**: the `overdue` invoice status is in the
  enum and the schema check; a sweeper that flips `sent` → `overdue` when
  past the due date is TODO.
- **Real KYB / payment-rail clients**: sandbox impls return synthetic refs
  and auto-approve. Plug in the real Veratad / Stripe / Modulr clients.
