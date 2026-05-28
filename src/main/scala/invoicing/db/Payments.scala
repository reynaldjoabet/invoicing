package invoicing.db

import invoicing.domain.*

import cats.effect.*
import cats.syntax.all.*
import skunk.*
import skunk.implicits.*
import skunk.codec.all.*

import java.time.{Instant, ZoneOffset}

trait Payments[F[_]] {
  def insert(p: Payment): F[Payment]
  def find(id: PaymentId): F[Option[Payment]]
  def listFor(invoiceId: InvoiceId): F[List[Payment]]
  def updateStatus(
      id: PaymentId,
      status: PaymentStatus,
      railRef: Option[String],
      failure: Option[String],
      completed: Option[Instant]
  ): F[Unit]
}

object Payments {

  import Codecs.{payment as paymentC, paymentId as paymentIdC, invoiceId as invoiceIdC, paymentStatus as statusC}

  def make[F[_]: Concurrent](pool: Resource[F, Session[F]]): Payments[F] = new Payments[F] {

    def insert(p: Payment): F[Payment] =
      pool.use(_.prepare(Q.insert).flatMap(_.unique(p)))

    def find(id: PaymentId): F[Option[Payment]] =
      pool.use(_.prepare(Q.byId).flatMap(_.option(id)))

    def listFor(invoiceId: InvoiceId): F[List[Payment]] =
      pool.use(_.prepare(Q.listFor).flatMap(_.stream(invoiceId, 16).compile.toList))

    def updateStatus(
        id: PaymentId,
        status: PaymentStatus,
        railRef: Option[String],
        failure: Option[String],
        completed: Option[Instant]
    ): F[Unit] =
      pool
        .use(
          _.prepare(Q.setStatus).flatMap(
            _.execute(
              (
                status,
                railRef,
                failure,
                completed.map(_.atOffset(ZoneOffset.UTC)),
                id
              )
            )
          )
        )
        .void
  }

  private object Q {

    val insert: Query[Payment, Payment] =
      sql"""INSERT INTO payments (id, invoice_id, bank_account_id, amount_minor, currency,
                                   fx_rate_applied, status, rail_ref, failure_reason,
                                   initiated_at, completed_at)
            VALUES $paymentC
            RETURNING id, invoice_id, bank_account_id, amount_minor, currency,
                      fx_rate_applied, status, rail_ref, failure_reason,
                      initiated_at, completed_at""".query(paymentC)

    val byId: Query[PaymentId, Payment] =
      sql"""SELECT id, invoice_id, bank_account_id, amount_minor, currency,
                   fx_rate_applied, status, rail_ref, failure_reason,
                   initiated_at, completed_at
            FROM payments WHERE id = $paymentIdC""".query(paymentC)

    val listFor: Query[InvoiceId, Payment] =
      sql"""SELECT id, invoice_id, bank_account_id, amount_minor, currency,
                   fx_rate_applied, status, rail_ref, failure_reason,
                   initiated_at, completed_at
            FROM payments WHERE invoice_id = $invoiceIdC ORDER BY initiated_at""".query(paymentC)

    val setStatus
        : Command[(PaymentStatus, Option[String], Option[String], Option[java.time.OffsetDateTime], PaymentId)] =
      sql"""UPDATE payments
            SET status = $statusC, rail_ref = ${varchar(128).opt}, failure_reason = ${varchar(512).opt},
                completed_at = ${timestamptz.opt}
            WHERE id = $paymentIdC""".command
  }
}
