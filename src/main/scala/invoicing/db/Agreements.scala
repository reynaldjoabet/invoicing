package invoicing.db

import invoicing.domain.*

import cats.effect.*
import cats.syntax.all.*
import skunk.*
import skunk.implicits.*

import java.time.{Instant, ZoneOffset}

trait Agreements[F[_]] {
  def create(a: Agreement): F[Agreement]
  def find(id: AgreementId): F[Option[Agreement]]
  def listForBiller(id: BusinessId): F[List[Agreement]]
  def listForPayer(id: BusinessId): F[List[Agreement]]
  def listSigned: F[List[Agreement]]
  def updateStatus(id: AgreementId, status: AgreementStatus, at: Instant): F[Unit]
}

object Agreements {

  import Codecs.{
    agreementCore,
    agreementId as agreementIdC,
    businessId as businessIdC,
    agreementStatus as statusC,
    serviceId as serviceIdC,
    title,
    body,
    currency as currencyC
  }
  import skunk.codec.all.timestamptz

  def make[F[_]: Concurrent](pool: Resource[F, Session[F]]): Agreements[F] = new Agreements[F] {

    def create(a: Agreement): F[Agreement] =
      pool.use { s =>
        s.transaction.use { _ =>
          for {
            tup <- s.prepare(Q.insertCore).flatMap(_.unique(coreTuple(a)))
            _ <- s.prepare(Q.linkService).flatMap(pc => a.serviceIds.traverse_(sid => pc.execute((a.id, sid))))
          } yield fromCore(tup, a.serviceIds)
        }
      }

    def find(id: AgreementId): F[Option[Agreement]] =
      pool.use { s =>
        for {
          core <- s.prepare(Q.byId).flatMap(_.option(id))
          out <- core.traverse(c => loadServices(s, c._1).map(fromCore(c, _)))
        } yield out
      }

    def listForBiller(id: BusinessId): F[List[Agreement]] =
      listBy(Q.byBiller, id)

    def listForPayer(id: BusinessId): F[List[Agreement]] =
      listBy(Q.byPayer, id)

    def listSigned: F[List[Agreement]] =
      pool.use { s =>
        s.execute(Q.signed).flatMap { cores =>
          cores.traverse(c => loadServices(s, c._1).map(fromCore(c, _)))
        }
      }

    private def listBy(q: Query[BusinessId, AgreementCore], id: BusinessId): F[List[Agreement]] =
      pool.use { s =>
        s.prepare(q).flatMap(_.stream(id, 32).compile.toList).flatMap { cores =>
          cores.traverse(c => loadServices(s, c._1).map(fromCore(c, _)))
        }
      }

    def updateStatus(id: AgreementId, status: AgreementStatus, at: Instant): F[Unit] =
      pool.use(_.prepare(Q.setStatus).flatMap(_.execute((status, at.atOffset(ZoneOffset.UTC), id)))).void

    private def loadServices(s: Session[F], id: AgreementId): F[List[ServiceId]] =
      s.prepare(Q.servicesFor).flatMap(_.stream(id, 32).compile.toList)
  }

  // Tuple shape returned by agreementCore codec.
  private type AgreementCore = (
      AgreementId,
      BusinessId,
      BusinessId,
      Title,
      Body,
      CurrencyCode,
      AgreementStatus,
      Option[Instant],
      Option[Instant],
      Option[Instant],
      Instant
  )

  private def coreTuple(a: Agreement): AgreementCore =
    (
      a.id,
      a.billerId,
      a.payerId,
      a.title,
      a.body,
      a.currency,
      a.status,
      a.sentAt,
      a.signedAt,
      a.terminatedAt,
      a.createdAt
    )

  private def fromCore(c: AgreementCore, services: List[ServiceId]): Agreement =
    Agreement(
      id = c._1,
      billerId = c._2,
      payerId = c._3,
      title = c._4,
      body = c._5,
      currency = c._6,
      status = c._7,
      serviceIds = services,
      sentAt = c._8,
      signedAt = c._9,
      terminatedAt = c._10,
      createdAt = c._11
    )

  private object Q {

    val insertCore: Query[AgreementCore, AgreementCore] =
      sql"""INSERT INTO agreements (id, biller_id, payer_id, title, body, currency, status,
                                    sent_at, signed_at, terminated_at, created_at)
            VALUES $agreementCore
            RETURNING id, biller_id, payer_id, title, body, currency, status,
                      sent_at, signed_at, terminated_at, created_at""".query(agreementCore)

    val linkService: Command[(AgreementId, ServiceId)] =
      sql"""INSERT INTO agreement_services (agreement_id, service_id)
            VALUES ($agreementIdC, $serviceIdC)
            ON CONFLICT DO NOTHING""".command

    val servicesFor: Query[AgreementId, ServiceId] =
      sql"SELECT service_id FROM agreement_services WHERE agreement_id = $agreementIdC".query(serviceIdC)

    val byId: Query[AgreementId, AgreementCore] =
      sql"""SELECT id, biller_id, payer_id, title, body, currency, status,
                   sent_at, signed_at, terminated_at, created_at
            FROM agreements WHERE id = $agreementIdC""".query(agreementCore)

    val byBiller: Query[BusinessId, AgreementCore] =
      sql"""SELECT id, biller_id, payer_id, title, body, currency, status,
                   sent_at, signed_at, terminated_at, created_at
            FROM agreements WHERE biller_id = $businessIdC ORDER BY created_at DESC""".query(agreementCore)

    val byPayer: Query[BusinessId, AgreementCore] =
      sql"""SELECT id, biller_id, payer_id, title, body, currency, status,
                   sent_at, signed_at, terminated_at, created_at
            FROM agreements WHERE payer_id = $businessIdC ORDER BY created_at DESC""".query(agreementCore)

    val signed: Query[Void, AgreementCore] =
      sql"""SELECT id, biller_id, payer_id, title, body, currency, status,
                   sent_at, signed_at, terminated_at, created_at
            FROM agreements WHERE status = 'signed' ORDER BY created_at""".query(agreementCore)

    val setStatus: Command[(AgreementStatus, java.time.OffsetDateTime, AgreementId)] =
      sql"""UPDATE agreements
            SET status = $statusC,
                signed_at = CASE WHEN $statusC = 'signed' THEN $timestamptz ELSE signed_at END,
                terminated_at = CASE WHEN $statusC = 'terminated' THEN $timestamptz ELSE terminated_at END
            WHERE id = $agreementIdC""".command
        .contramap[(AgreementStatus, java.time.OffsetDateTime, AgreementId)] { case (s, t, id) =>
          (s, s, t, s, t, id)
        }

    // Silence unused-codec warnings for codecs only used via interpolation upstream.
    val _ = (title, body, currencyC)
  }
}
