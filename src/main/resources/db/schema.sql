-- Invoicing / billing schema (PostgreSQL). Idempotent.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------- People & businesses ----------

CREATE TABLE IF NOT EXISTS users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(120) NOT NULL,
    full_name     VARCHAR(200) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS businesses (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(200) NOT NULL,
    country           CHAR(2)      NOT NULL,
    vat               VARCHAR(16),
    ein               CHAR(9),
    default_currency  CHAR(3)      NOT NULL,
    kyb_status        VARCHAR(16)  NOT NULL DEFAULT 'not_started'
                      CHECK (kyb_status IN ('not_started','pending','approved','rejected')),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK ((vat IS NOT NULL) OR (ein IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS memberships (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    business_id  UUID         NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    role         VARCHAR(16)  NOT NULL CHECK (role IN ('owner','admin','member')),
    invited_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    accepted_at  TIMESTAMPTZ,
    UNIQUE (user_id, business_id)
);

CREATE INDEX IF NOT EXISTS memberships_user_idx     ON memberships(user_id);
CREATE INDEX IF NOT EXISTS memberships_business_idx ON memberships(business_id);

-- ---------- Bank accounts ----------

CREATE TABLE IF NOT EXISTS bank_accounts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id     UUID         NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    account_type    VARCHAR(16)  NOT NULL CHECK (account_type IN ('iban','us_ach','other')),
    holder_name     VARCHAR(200) NOT NULL,
    iban            VARCHAR(34),
    bic             VARCHAR(11),
    routing_number  CHAR(9),
    account_number  VARCHAR(34),
    currency        CHAR(3)      NOT NULL,
    is_default      BOOLEAN      NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (
      (account_type = 'iban'   AND iban IS NOT NULL) OR
      (account_type = 'us_ach' AND routing_number IS NOT NULL AND account_number IS NOT NULL) OR
      (account_type = 'other'  AND account_number IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS bank_accounts_business_idx ON bank_accounts(business_id);

-- ---------- Services ----------

CREATE TABLE IF NOT EXISTS services (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    biller_id        UUID         NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    title            VARCHAR(200) NOT NULL,
    description      TEXT         NOT NULL,
    kind             VARCHAR(16)  NOT NULL CHECK (kind IN ('one_time','recurring')),
    interval         VARCHAR(16)  CHECK (interval IN ('weekly','monthly','quarterly','annual')),
    unit_price_minor BIGINT       NOT NULL CHECK (unit_price_minor > 0),
    currency         CHAR(3)      NOT NULL,
    tax_bps          INT          NOT NULL CHECK (tax_bps BETWEEN 0 AND 10000),
    archived         BOOLEAN      NOT NULL DEFAULT false,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK ((kind = 'recurring' AND interval IS NOT NULL) OR (kind = 'one_time' AND interval IS NULL))
);

-- ---------- Agreements ----------

CREATE TABLE IF NOT EXISTS agreements (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    biller_id       UUID         NOT NULL REFERENCES businesses(id) ON DELETE RESTRICT,
    payer_id        UUID         NOT NULL REFERENCES businesses(id) ON DELETE RESTRICT,
    title           VARCHAR(200) NOT NULL,
    body            TEXT         NOT NULL,
    currency        CHAR(3)      NOT NULL,
    status          VARCHAR(16)  NOT NULL CHECK (status IN ('draft','sent','signed','rejected','terminated')),
    sent_at         TIMESTAMPTZ,
    signed_at       TIMESTAMPTZ,
    terminated_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS agreements_payer_idx  ON agreements(payer_id, status);
CREATE INDEX IF NOT EXISTS agreements_biller_idx ON agreements(biller_id, status);

CREATE TABLE IF NOT EXISTS agreement_services (
    agreement_id  UUID NOT NULL REFERENCES agreements(id) ON DELETE CASCADE,
    service_id    UUID NOT NULL REFERENCES services(id)   ON DELETE RESTRICT,
    PRIMARY KEY (agreement_id, service_id)
);

-- ---------- Invoices & lines ----------

CREATE TABLE IF NOT EXISTS invoices (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    number          VARCHAR(40)  NOT NULL,
    biller_id       UUID         NOT NULL REFERENCES businesses(id) ON DELETE RESTRICT,
    payer_id        UUID         NOT NULL REFERENCES businesses(id) ON DELETE RESTRICT,
    agreement_id    UUID REFERENCES agreements(id) ON DELETE SET NULL,
    currency        CHAR(3)      NOT NULL,
    net_minor       BIGINT       NOT NULL CHECK (net_minor > 0),
    tax_minor       BIGINT       NOT NULL CHECK (tax_minor >= 0),
    total_minor     BIGINT       NOT NULL CHECK (total_minor > 0),
    issued_on       DATE         NOT NULL,
    due_on          DATE         NOT NULL,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('draft','sent','paid','partially_paid','cancelled','overdue')),
    delivery_mode   VARCHAR(8)   NOT NULL CHECK (delivery_mode IN ('manual','auto')),
    notes           TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    paid_at         TIMESTAMPTZ,
    UNIQUE (biller_id, number)
);

CREATE INDEX IF NOT EXISTS invoices_payer_idx  ON invoices(payer_id, status);
CREATE INDEX IF NOT EXISTS invoices_biller_idx ON invoices(biller_id, status);

CREATE TABLE IF NOT EXISTS invoice_lines (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id       UUID         NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    service_id       UUID REFERENCES services(id) ON DELETE SET NULL,
    description      VARCHAR(200) NOT NULL,
    quantity         NUMERIC(38, 18) NOT NULL CHECK (quantity > 0),
    unit_price_minor BIGINT       NOT NULL CHECK (unit_price_minor > 0),
    tax_bps          INT          NOT NULL CHECK (tax_bps BETWEEN 0 AND 10000),
    net_minor        BIGINT       NOT NULL CHECK (net_minor > 0),
    tax_minor        BIGINT       NOT NULL CHECK (tax_minor >= 0),
    total_minor      BIGINT       NOT NULL CHECK (total_minor > 0)
);

CREATE INDEX IF NOT EXISTS invoice_lines_invoice_idx ON invoice_lines(invoice_id);

-- ---------- Payments ----------

CREATE TABLE IF NOT EXISTS payments (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id       UUID         NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    bank_account_id  UUID         NOT NULL REFERENCES bank_accounts(id) ON DELETE RESTRICT,
    amount_minor     BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency         CHAR(3)      NOT NULL,
    fx_rate_applied  NUMERIC(20, 10),
    status           VARCHAR(16)  NOT NULL CHECK (status IN ('initiated','authorised','captured','failed','refunded')),
    rail_ref         VARCHAR(128),
    failure_reason   VARCHAR(512),
    initiated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS payments_invoice_idx ON payments(invoice_id);

-- ---------- Payment preferences (payer-side, per biller) ----------

CREATE TABLE IF NOT EXISTS payment_preferences (
    payer_id                 UUID         NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    biller_id                UUID         NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    mode                     VARCHAR(16)  NOT NULL CHECK (mode IN ('manual_approval','auto_debit')),
    default_bank_account_id  UUID REFERENCES bank_accounts(id) ON DELETE SET NULL,
    PRIMARY KEY (payer_id, biller_id)
);

-- ---------- Per-biller invoice number sequence ----------

-- Production: use one Postgres sequence per biller, or a transactional
-- row-level counter table to keep gap-free numbering. Sketched here as a
-- simple table the InvoicingService increments inside a transaction.
CREATE TABLE IF NOT EXISTS invoice_counters (
    biller_id  UUID PRIMARY KEY REFERENCES businesses(id) ON DELETE CASCADE,
    next_seq   BIGINT NOT NULL DEFAULT 1
);
