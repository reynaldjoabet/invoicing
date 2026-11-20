-- Invoicing / billing schema. Targets PostgreSQL 19 (needs 18+ for uuidv7()).
--
-- Idempotent: safe to run against an empty database or re-run against an
-- existing one. Tables use CREATE ... IF NOT EXISTS, so new CHECK constraints
-- only land on fresh databases; indexes and storage parameters are applied to
-- existing databases too.
--
-- Column types deliberately match the Skunk codecs in invoicing.db.Codecs
-- (varchar(n), bpchar(n), int8, ...). Skunk checks result/parameter types at
-- prepare time, so changing e.g. VARCHAR -> TEXT here breaks the app.
--
-- Design notes
--   * Primary keys are UUIDv7. The app mints them (UuidGen); the column default
--     uses the built-in uuidv7() so ad-hoc inserts get the same time-ordered,
--     right-edge-of-B-tree behaviour. pgcrypto is no longer needed.
--   * Every foreign-key column is the leading column of some index. Without one,
--     ON DELETE CASCADE / SET NULL / RESTRICT on the parent seq-scans the child.
--   * List indexes match the app's WHERE + ORDER BY, so the planner can read a
--     listing straight from the index in order (or sort only the few matching rows).
--   * Columns that change after insert (status, sent_at, paid_at, completed_at,
--     archived, accepted_at, ...) are kept out of indexes where possible, and
--     update-heavy tables leave free space per page (fillfactor). Together this
--     lets status transitions be HOT updates: no index writes, less bloat.
--   * TOAST compression: PostgreSQL 19 defaults to lz4, so no per-column
--     COMPRESSION clauses are needed for the TEXT columns.

BEGIN;

-- Keep a misbehaving bootstrap from blocking live traffic for long.
SET LOCAL lock_timeout = '5s';
SET LOCAL client_min_messages = warning;  -- hide "already exists, skipping" noise

-- ---------- People & businesses ----------

CREATE TABLE IF NOT EXISTS users (
    id            UUID         PRIMARY KEY DEFAULT uuidv7(),
    email         VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(120) NOT NULL,
    full_name     VARCHAR(200) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS businesses (
    id                UUID         PRIMARY KEY DEFAULT uuidv7(),
    name              VARCHAR(200) NOT NULL,
    country           CHAR(2)      NOT NULL,
    vat               VARCHAR(16),
    ein               CHAR(9),
    default_currency  CHAR(3)      NOT NULL,
    kyb_status        VARCHAR(16)  NOT NULL DEFAULT 'not_started'
                      CHECK (kyb_status IN ('not_started','pending','approved','rejected')),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT businesses_tax_id_present CHECK (vat IS NOT NULL OR ein IS NOT NULL)
);

CREATE TABLE IF NOT EXISTS memberships (
    id           UUID         PRIMARY KEY DEFAULT uuidv7(),
    user_id      UUID         NOT NULL REFERENCES users(id)      ON DELETE CASCADE,
    business_id  UUID         NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    role         VARCHAR(16)  NOT NULL CHECK (role IN ('owner','admin','member')),
    invited_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    accepted_at  TIMESTAMPTZ,
    -- Also serves "memberships of user X" lookups and the user_id FK.
    UNIQUE (user_id, business_id)
);

-- Redundant: the (user_id, business_id) unique index already covers user_id.
DROP INDEX IF EXISTS memberships_user_idx;
CREATE INDEX IF NOT EXISTS memberships_business_idx ON memberships (business_id);

-- ---------- Bank accounts ----------

CREATE TABLE IF NOT EXISTS bank_accounts (
    id              UUID         PRIMARY KEY DEFAULT uuidv7(),
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
    CONSTRAINT bank_accounts_details_match_type CHECK (
      (account_type = 'iban'   AND iban IS NOT NULL) OR
      (account_type = 'us_ach' AND routing_number IS NOT NULL AND account_number IS NOT NULL) OR
      (account_type = 'other'  AND account_number IS NOT NULL)
    )
);

-- BankAccounts.listFor: WHERE business_id = ? ORDER BY is_default DESC, created_at
DROP INDEX IF EXISTS bank_accounts_business_idx;
CREATE INDEX IF NOT EXISTS bank_accounts_business_list_idx
    ON bank_accounts (business_id, is_default DESC, created_at);

-- ---------- Services ----------

CREATE TABLE IF NOT EXISTS services (
    id               UUID         PRIMARY KEY DEFAULT uuidv7(),
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
    CONSTRAINT services_interval_matches_kind CHECK (
      (kind = 'recurring' AND interval IS NOT NULL) OR
      (kind = 'one_time'  AND interval IS NULL)
    )
);

-- Services.listFor: WHERE biller_id = ? AND (? OR NOT archived) ORDER BY archived, title
-- Previously unindexed: the biller_id FK had no supporting index at all.
CREATE INDEX IF NOT EXISTS services_biller_list_idx
    ON services (biller_id, archived, title);

-- ---------- Agreements ----------

CREATE TABLE IF NOT EXISTS agreements (
    id              UUID         PRIMARY KEY DEFAULT uuidv7(),
    biller_id       UUID         NOT NULL REFERENCES businesses(id) ON DELETE RESTRICT,
    payer_id        UUID         NOT NULL REFERENCES businesses(id) ON DELETE RESTRICT,
    title           VARCHAR(200) NOT NULL,
    body            TEXT         NOT NULL,
    currency        CHAR(3)      NOT NULL,
    status          VARCHAR(16)  NOT NULL
                    CHECK (status IN ('draft','sent','signed','rejected','terminated')),
    sent_at         TIMESTAMPTZ,
    signed_at       TIMESTAMPTZ,
    terminated_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Agreements.listForBiller / listForPayer: WHERE x_id = ? ORDER BY created_at DESC
DROP INDEX IF EXISTS agreements_payer_idx;
DROP INDEX IF EXISTS agreements_biller_idx;
CREATE INDEX IF NOT EXISTS agreements_biller_created_idx ON agreements (biller_id, created_at DESC);
CREATE INDEX IF NOT EXISTS agreements_payer_created_idx  ON agreements (payer_id,  created_at DESC);

-- Agreements.listSigned (RecurringInvoiceJob): WHERE status = 'signed' ORDER BY created_at
-- Partial index: only the rows the job reads, already in the order it wants.
CREATE INDEX IF NOT EXISTS agreements_signed_created_idx
    ON agreements (created_at) WHERE status = 'signed';

CREATE TABLE IF NOT EXISTS agreement_services (
    agreement_id  UUID NOT NULL REFERENCES agreements(id) ON DELETE CASCADE,
    service_id    UUID NOT NULL REFERENCES services(id)   ON DELETE RESTRICT,
    PRIMARY KEY (agreement_id, service_id)
);

-- FK support: deleting a service must find referencing rows without a seq scan.
CREATE INDEX IF NOT EXISTS agreement_services_service_idx ON agreement_services (service_id);

-- ---------- Invoices & lines ----------

CREATE TABLE IF NOT EXISTS invoices (
    id              UUID         PRIMARY KEY DEFAULT uuidv7(),
    number          VARCHAR(40)  NOT NULL,
    biller_id       UUID         NOT NULL REFERENCES businesses(id) ON DELETE RESTRICT,
    payer_id        UUID         NOT NULL REFERENCES businesses(id) ON DELETE RESTRICT,
    agreement_id    UUID                  REFERENCES agreements(id) ON DELETE SET NULL,
    currency        CHAR(3)      NOT NULL,
    net_minor       BIGINT       NOT NULL CHECK (net_minor > 0),
    tax_minor       BIGINT       NOT NULL CHECK (tax_minor >= 0),
    total_minor     BIGINT       NOT NULL CHECK (total_minor > 0),
    issued_on       DATE         NOT NULL,
    due_on          DATE         NOT NULL,
    status          VARCHAR(20)  NOT NULL
                    CHECK (status IN ('draft','sent','paid','partially_paid','cancelled','overdue')),
    delivery_mode   VARCHAR(8)   NOT NULL CHECK (delivery_mode IN ('manual','auto')),
    notes           TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    paid_at         TIMESTAMPTZ,
    -- Also serves the biller_id FK.
    UNIQUE (biller_id, number),
    -- Mirrors InvoicingService: totals are sums of lines, due date validated.
    CONSTRAINT invoices_total_is_net_plus_tax CHECK (total_minor = net_minor + tax_minor),
    CONSTRAINT invoices_due_not_before_issue  CHECK (due_on >= issued_on)
);

-- Invoices.listForBiller / listForPayer: WHERE x_id = ? ORDER BY created_at DESC.
-- status is intentionally not indexed, so markSent/markPaid/updateStatus stay HOT.
DROP INDEX IF EXISTS invoices_payer_idx;
DROP INDEX IF EXISTS invoices_biller_idx;
CREATE INDEX IF NOT EXISTS invoices_biller_created_idx ON invoices (biller_id, created_at DESC);
CREATE INDEX IF NOT EXISTS invoices_payer_created_idx  ON invoices (payer_id,  created_at DESC);

-- FK support for ON DELETE SET NULL; most invoices have no agreement, so partial.
CREATE INDEX IF NOT EXISTS invoices_agreement_idx
    ON invoices (agreement_id) WHERE agreement_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS invoice_lines (
    id               UUID            PRIMARY KEY DEFAULT uuidv7(),
    invoice_id       UUID            NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    service_id       UUID                     REFERENCES services(id) ON DELETE SET NULL,
    description      VARCHAR(200)    NOT NULL,
    quantity         NUMERIC(38, 18) NOT NULL CHECK (quantity > 0),
    unit_price_minor BIGINT          NOT NULL CHECK (unit_price_minor > 0),
    tax_bps          INT             NOT NULL CHECK (tax_bps BETWEEN 0 AND 10000),
    net_minor        BIGINT          NOT NULL CHECK (net_minor > 0),
    tax_minor        BIGINT          NOT NULL CHECK (tax_minor >= 0),
    total_minor      BIGINT          NOT NULL CHECK (total_minor > 0),
    CONSTRAINT invoice_lines_total_is_net_plus_tax CHECK (total_minor = net_minor + tax_minor)
);

-- Invoices.linesFor: WHERE invoice_id = ? ORDER BY id (UUIDv7, so insertion order).
DROP INDEX IF EXISTS invoice_lines_invoice_idx;
CREATE INDEX IF NOT EXISTS invoice_lines_invoice_list_idx ON invoice_lines (invoice_id, id);

-- FK support for ON DELETE SET NULL from services.
CREATE INDEX IF NOT EXISTS invoice_lines_service_idx
    ON invoice_lines (service_id) WHERE service_id IS NOT NULL;

-- ---------- Payments ----------

CREATE TABLE IF NOT EXISTS payments (
    id               UUID          PRIMARY KEY DEFAULT uuidv7(),
    invoice_id       UUID          NOT NULL REFERENCES invoices(id)      ON DELETE CASCADE,
    bank_account_id  UUID          NOT NULL REFERENCES bank_accounts(id) ON DELETE RESTRICT,
    amount_minor     BIGINT        NOT NULL CHECK (amount_minor > 0),
    currency         CHAR(3)       NOT NULL,
    fx_rate_applied  NUMERIC(20, 10) CHECK (fx_rate_applied > 0),
    status           VARCHAR(16)   NOT NULL
                     CHECK (status IN ('initiated','authorised','captured','failed','refunded')),
    rail_ref         VARCHAR(128),
    failure_reason   VARCHAR(512),
    initiated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ
);

-- Payments.listFor: WHERE invoice_id = ? ORDER BY initiated_at
DROP INDEX IF EXISTS payments_invoice_idx;
CREATE INDEX IF NOT EXISTS payments_invoice_list_idx ON payments (invoice_id, initiated_at);

-- FK support for ON DELETE RESTRICT from bank_accounts.
CREATE INDEX IF NOT EXISTS payments_bank_account_idx ON payments (bank_account_id);

-- ---------- Payment preferences (payer-side, per biller) ----------

CREATE TABLE IF NOT EXISTS payment_preferences (
    payer_id                 UUID        NOT NULL REFERENCES businesses(id)    ON DELETE CASCADE,
    biller_id                UUID        NOT NULL REFERENCES businesses(id)    ON DELETE CASCADE,
    mode                     VARCHAR(16) NOT NULL CHECK (mode IN ('manual_approval','auto_debit')),
    default_bank_account_id  UUID                 REFERENCES bank_accounts(id) ON DELETE SET NULL,
    PRIMARY KEY (payer_id, biller_id)
);

-- FK support: the PK covers payer_id only.
CREATE INDEX IF NOT EXISTS payment_preferences_biller_idx ON payment_preferences (biller_id);
CREATE INDEX IF NOT EXISTS payment_preferences_bank_account_idx
    ON payment_preferences (default_bank_account_id) WHERE default_bank_account_id IS NOT NULL;

-- ---------- Per-biller invoice number counter ----------

-- Invoices.nextNumber bumps this with a single
--   INSERT ... ON CONFLICT (biller_id) DO UPDATE SET next_seq = next_seq + 1 RETURNING ...
-- The row lock serialises concurrent issuers per biller. Numbers are gap-free
-- only if the bump runs in the same transaction as the invoice insert; today
-- they run in separate transactions, so a failed insert leaves a gap.
-- (A Postgres SEQUENCE would never be gap-free and is the wrong tool here.)
CREATE TABLE IF NOT EXISTS invoice_counters (
    biller_id  UUID   PRIMARY KEY REFERENCES businesses(id) ON DELETE CASCADE,
    next_seq   BIGINT NOT NULL DEFAULT 1 CHECK (next_seq >= 1)
);

-- ---------- Storage and autovacuum tuning ----------
-- Applied with ALTER so existing databases pick it up too. These only affect
-- pages written from now on; existing pages are rewritten by REPACK (below).

-- One row per biller, updated on every invoice: small and very hot. Lots of
-- free space keeps every bump a HOT update; vacuum it eagerly.
ALTER TABLE invoice_counters SET (
    fillfactor = 50,
    autovacuum_vacuum_scale_factor = 0.0,
    autovacuum_vacuum_threshold    = 200
);

-- Rows move through status changes after insert (draft -> sent -> paid, and
-- initiated -> authorised -> captured). Spare room per page keeps those HOT.
-- autovacuum_parallel_workers (PostgreSQL 19) lets autovacuum process these
-- multi-index tables' indexes in parallel; capped server-wide by
-- autovacuum_max_parallel_workers.
ALTER TABLE invoices SET (
    fillfactor = 85,
    autovacuum_vacuum_scale_factor = 0.05,
    autovacuum_analyze_scale_factor = 0.02,
    autovacuum_parallel_workers = 2
);

ALTER TABLE payments SET (
    fillfactor = 85,
    autovacuum_vacuum_scale_factor = 0.05,
    autovacuum_analyze_scale_factor = 0.02,
    autovacuum_parallel_workers = 2
);

ALTER TABLE agreements  SET (fillfactor = 90);
ALTER TABLE memberships SET (fillfactor = 90);

COMMIT;

-- ---------- Operations (not run by this script) ----------
--
-- Reclaim bloat / apply new fillfactor to existing pages without blocking
-- reads or writes (PostgreSQL 19; replaces VACUUM FULL / CLUSTER):
--     REPACK (CONCURRENTLY) invoices;
--     REPACK (CONCURRENTLY) payments;
-- Progress: SELECT * FROM pg_stat_progress_repack;
--
-- On a large live database, build the new indexes above with
-- CREATE INDEX CONCURRENTLY (outside a transaction) before running this script.
--
-- See what autovacuum will pick next and why:
--     SELECT relname, score, do_vacuum, do_analyze FROM pg_stat_autovacuum_scores ORDER BY score DESC;
--
-- Check that the list queries are index-only/sort-free, with async I/O stats:
--     EXPLAIN (ANALYZE, BUFFERS, IO)
--     SELECT * FROM invoices WHERE biller_id = $1 ORDER BY created_at DESC;
