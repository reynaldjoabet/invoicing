package invoicing.db

import invoicing.domain.*

import cats.effect.*
import cats.syntax.all.*
import skunk.*
import skunk.implicits.*

trait Businesses[F[_]] {
  def create(b: Business): F[Business]
  def find(id: BusinessId): F[Option[Business]]
  def updateKyb(id: BusinessId, status: KybStatus): F[Unit]

  // ---- memberships ----
  def addMember(m: Membership): F[Membership]
  def acceptMembership(id: MembershipId): F[Unit]
  def membershipsFor(userId: UserId): F[List[Membership]]
  def membersOf(businessId: BusinessId): F[List[Membership]]
}

object Businesses {

  import Codecs.{
    business as businessC,
    businessId as businessIdC,
    membership as memC,
    membershipId as memIdC,
    userId as userIdC,
    kybStatus as kybC
  }
  import skunk.codec.all.timestamptz

  def make[F[_]: Sync](pool: Resource[F, Session[F]]): Businesses[F] = new Businesses[F] {
    def create(b: Business): F[Business] =
      pool.use(_.prepare(Q.insert).flatMap(_.unique(b)))
    def find(id: BusinessId): F[Option[Business]] =
      pool.use(_.prepare(Q.byId).flatMap(_.option(id)))
    def updateKyb(id: BusinessId, status: KybStatus): F[Unit] =
      pool.use(_.prepare(Q.setKyb).flatMap(_.execute((status, id)))).void

    def addMember(m: Membership): F[Membership] =
      pool.use(_.prepare(Q.insertMem).flatMap(_.unique(m)))
    def acceptMembership(id: MembershipId): F[Unit] =
      pool.use { s =>
        Sync[F]
          .delay(java.time.Instant.now())
          .flatMap(now => s.prepare(Q.acceptMem).flatMap(_.execute((now.atOffset(java.time.ZoneOffset.UTC), id))))
      }.void
    def membershipsFor(userId: UserId): F[List[Membership]] =
      pool.use(_.prepare(Q.memsFor).flatMap(_.stream(userId, 32).compile.toList))
    def membersOf(businessId: BusinessId): F[List[Membership]] =
      pool.use(_.prepare(Q.membersOf).flatMap(_.stream(businessId, 32).compile.toList))
  }

  private object Q {

    val insert: Query[Business, Business] =
      sql"""INSERT INTO businesses (id, name, country, vat, ein, default_currency, kyb_status, created_at)
            VALUES $businessC
            RETURNING id, name, country, vat, ein, default_currency, kyb_status, created_at""".query(businessC)

    val byId: Query[BusinessId, Business] =
      sql"""SELECT id, name, country, vat, ein, default_currency, kyb_status, created_at
            FROM businesses WHERE id = $businessIdC""".query(businessC)

    val setKyb: Command[(KybStatus, BusinessId)] =
      sql"UPDATE businesses SET kyb_status = $kybC WHERE id = $businessIdC".command

    val insertMem: Query[Membership, Membership] =
      sql"""INSERT INTO memberships (id, user_id, business_id, role, invited_at, accepted_at)
            VALUES $memC
            RETURNING id, user_id, business_id, role, invited_at, accepted_at""".query(memC)

    val acceptMem: Command[(java.time.OffsetDateTime, MembershipId)] =
      sql"UPDATE memberships SET accepted_at = $timestamptz WHERE id = $memIdC".command

    val memsFor: Query[UserId, Membership] =
      sql"""SELECT id, user_id, business_id, role, invited_at, accepted_at
            FROM memberships WHERE user_id = $userIdC""".query(memC)

    val membersOf: Query[BusinessId, Membership] =
      sql"""SELECT id, user_id, business_id, role, invited_at, accepted_at
            FROM memberships WHERE business_id = $businessIdC""".query(memC)
  }
}
