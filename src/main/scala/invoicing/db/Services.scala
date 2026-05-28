package invoicing.db

import invoicing.domain.*

import cats.effect.*
import cats.syntax.all.*
import skunk.*
import skunk.implicits.*

trait Services[F[_]] {
  def create(s: Service): F[Service]
  def listFor(billerId: BusinessId, includeArchived: Boolean): F[List[Service]]
  def find(id: ServiceId): F[Option[Service]]
  def archive(id: ServiceId): F[Unit]
}

object Services {

  import Codecs.{service as serviceC, serviceId as serviceIdC, businessId as businessIdC}
  import skunk.codec.all.bool

  def make[F[_]: Concurrent](pool: Resource[F, Session[F]]): Services[F] = new Services[F] {
    def create(s: Service): F[Service] =
      pool.use(_.prepare(Q.insert).flatMap(_.unique(s)))
    def listFor(billerId: BusinessId, includeArchived: Boolean): F[List[Service]] =
      pool.use(_.prepare(Q.listFor).flatMap(_.stream((billerId, includeArchived), 64).compile.toList))
    def find(id: ServiceId): F[Option[Service]] =
      pool.use(_.prepare(Q.byId).flatMap(_.option(id)))
    def archive(id: ServiceId): F[Unit] =
      pool.use(_.prepare(Q.archive).flatMap(_.execute(id))).void
  }

  private object Q {

    val insert: Query[Service, Service] =
      sql"""INSERT INTO services (id, biller_id, title, description, kind, interval,
                                  unit_price_minor, currency, tax_bps, archived, created_at)
            VALUES $serviceC
            RETURNING id, biller_id, title, description, kind, interval,
                      unit_price_minor, currency, tax_bps, archived, created_at""".query(serviceC)

    val listFor: Query[(BusinessId, Boolean), Service] =
      sql"""SELECT id, biller_id, title, description, kind, interval,
                   unit_price_minor, currency, tax_bps, archived, created_at
            FROM services
            WHERE biller_id = $businessIdC AND ($bool OR NOT archived)
            ORDER BY archived, title""".query(serviceC)

    val byId: Query[ServiceId, Service] =
      sql"""SELECT id, biller_id, title, description, kind, interval,
                   unit_price_minor, currency, tax_bps, archived, created_at
            FROM services WHERE id = $serviceIdC""".query(serviceC)

    val archive: Command[ServiceId] =
      sql"UPDATE services SET archived = true WHERE id = $serviceIdC".command
  }
}
