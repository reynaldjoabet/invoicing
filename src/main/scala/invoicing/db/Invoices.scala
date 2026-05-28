package invoicing.db

import invoicing.domain.*

import cats.effect.*
import cats.syntax.all.*
import skunk.*
import skunk.implicits.*
import skunk.codec.all.*

import java.time.{Instant, ZoneOffset}

trait Invoices[F[_]] {
  def insert(invoice: Invoice, lines: List[InvoiceLine]): F[Invoice]
  def find(id: InvoiceId): F[Option[Invoice]]
  def linesFor(invoiceId: InvoiceId): F[List[InvoiceLine]]
  def listForBiller(billerId: BusinessId): F[List[Invoice]]
  def listForPayer(payerId: BusinessId): F[List[Invoice]]
  def markSent(id: InvoiceId, at: Instant): F[Unit]
  def markPaid(id: InvoiceId, at: Instant): F[Unit]
  def updateStatus(id: InvoiceId, status: InvoiceStatus): F[Unit]
  def nextNumber(billerId: BusinessId): F[Long]
}

object Invoices {

  import Codecs.{
    invoice as invoiceC,
    invoiceLine as lineC,
    invoiceId as invoiceIdC,
    businessId as businessIdC,
    invoiceStatus as statusC
  }

  def make[F[_]: Concurrent](pool: Resource[F, Session[F]]): Invoices[F] = new Invoices[F] {

    def insert(inv: Invoice, lines: List[InvoiceLine]): F[Invoice] =
      pool.use { s =>
        s.transaction.use { _ =>
          for {
            saved <- s.prepare(Q.insertInv).flatMap(_.unique(inv))
            _ <- s.prepare(Q.insertLine).flatMap(pc => lines.traverse_(pc.execute))
          } yield saved
        }
      }

    def find(id: InvoiceId): F[Option[Invoice]] =
      pool.use(_.prepare(Q.byId).flatMap(_.option(id)))

    def linesFor(invoiceId: InvoiceId): F[List[InvoiceLine]] =
      pool.use(_.prepare(Q.linesFor).flatMap(_.stream(invoiceId, 64).compile.toList))

    def listForBiller(billerId: BusinessId): F[List[Invoice]] =
      pool.use(_.prepare(Q.byBiller).flatMap(_.stream(billerId, 64).compile.toList))

    def listForPayer(payerId: BusinessId): F[List[Invoice]] =
      pool.use(_.prepare(Q.byPayer).flatMap(_.stream(payerId, 64).compile.toList))

    def markSent(id: InvoiceId, at: Instant): F[Unit] =
      pool.use(_.prepare(Q.markSent).flatMap(_.execute((at.atOffset(ZoneOffset.UTC), id)))).void

    def markPaid(id: InvoiceId, at: Instant): F[Unit] =
      pool.use(_.prepare(Q.markPaid).flatMap(_.execute((at.atOffset(ZoneOffset.UTC), id)))).void

    def updateStatus(id: InvoiceId, status: InvoiceStatus): F[Unit] =
      pool.use(_.prepare(Q.setStatus).flatMap(_.execute((status, id)))).void

    /** Per-biller monotonically-increasing counter. Runs inside an UPSERT so the row is created on first call. The
      * transaction is held by the invoicing service when issuing an invoice.
      */
    def nextNumber(billerId: BusinessId): F[Long] =
      pool.use(_.prepare(Q.bumpCounter).flatMap(_.unique(billerId)))
  }

  private object Q {

    val insertInv: Query[Invoice, Invoice] =
      sql"""INSERT INTO invoices (id, number, biller_id, payer_id, agreement_id, currency,
                                   net_minor, tax_minor, total_minor, issued_on, due_on,
                                   status, delivery_mode, notes, created_at, sent_at, paid_at)
            VALUES $invoiceC
            RETURNING id, number, biller_id, payer_id, agreement_id, currency,
                      net_minor, tax_minor, total_minor, issued_on, due_on,
                      status, delivery_mode, notes, created_at, sent_at, paid_at""".query(invoiceC)

    val insertLine: Command[InvoiceLine] =
      sql"""INSERT INTO invoice_lines (id, invoice_id, service_id, description, quantity,
                                        unit_price_minor, tax_bps, net_minor, tax_minor, total_minor)
            VALUES $lineC""".command

    val byId: Query[InvoiceId, Invoice] =
      sql"""SELECT id, number, biller_id, payer_id, agreement_id, currency,
                   net_minor, tax_minor, total_minor, issued_on, due_on,
                   status, delivery_mode, notes, created_at, sent_at, paid_at
            FROM invoices WHERE id = $invoiceIdC""".query(invoiceC)

    val linesFor: Query[InvoiceId, InvoiceLine] =
      sql"""SELECT id, invoice_id, service_id, description, quantity,
                   unit_price_minor, tax_bps, net_minor, tax_minor, total_minor
            FROM invoice_lines WHERE invoice_id = $invoiceIdC ORDER BY id""".query(lineC)

    val byBiller: Query[BusinessId, Invoice] =
      sql"""SELECT id, number, biller_id, payer_id, agreement_id, currency,
                   net_minor, tax_minor, total_minor, issued_on, due_on,
                   status, delivery_mode, notes, created_at, sent_at, paid_at
            FROM invoices WHERE biller_id = $businessIdC ORDER BY created_at DESC""".query(invoiceC)

    val byPayer: Query[BusinessId, Invoice] =
      sql"""SELECT id, number, biller_id, payer_id, agreement_id, currency,
                   net_minor, tax_minor, total_minor, issued_on, due_on,
                   status, delivery_mode, notes, created_at, sent_at, paid_at
            FROM invoices WHERE payer_id = $businessIdC ORDER BY created_at DESC""".query(invoiceC)

    val markSent: Command[(java.time.OffsetDateTime, InvoiceId)] =
      sql"UPDATE invoices SET status = 'sent', sent_at = $timestamptz WHERE id = $invoiceIdC".command

    val markPaid: Command[(java.time.OffsetDateTime, InvoiceId)] =
      sql"UPDATE invoices SET status = 'paid', paid_at = $timestamptz WHERE id = $invoiceIdC".command

    val setStatus: Command[(InvoiceStatus, InvoiceId)] =
      sql"UPDATE invoices SET status = $statusC WHERE id = $invoiceIdC".command

    val bumpCounter: Query[BusinessId, Long] =
      sql"""INSERT INTO invoice_counters (biller_id, next_seq) VALUES ($businessIdC, 2)
            ON CONFLICT (biller_id) DO UPDATE SET next_seq = invoice_counters.next_seq + 1
            RETURNING next_seq - 1""".query(int8)
  }
}
