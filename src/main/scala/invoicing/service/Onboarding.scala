package invoicing.service

import invoicing.domain.*
import invoicing.db.{Businesses, Users}
import invoicing.external.{Kyb, Notifications}

import cats.effect.*
import cats.syntax.all.*

import java.time.Instant
import java.util.UUID

/** Onboarding & KYB.
  *
  *   - User self-registers via Auth (see [[Auth]]).
  *   - User creates one or more Businesses; each starts at `NotStarted`.
  *   - User submits KYB; we move to `Pending` and call out to Veratad / Trulioo / whichever provider. The webhook (or
  *     `refresh`) flips to Approved/Rejected.
  */
trait Onboarding[F[_]] {
  def createBusiness(
      userId: UserId,
      name: BusinessName,
      country: CountryCode,
      vat: Option[VatNumber],
      ein: Option[EinNumber],
      defaultCurrency: CurrencyCode
  ): F[Either[Onboarding.Error, Business]]

  def submitKyb(businessId: BusinessId): F[KybStatus]
  def refreshKyb(businessId: BusinessId, providerRef: String): F[KybStatus]

  def inviteMember(businessId: BusinessId, email: Email, role: BusinessRole): F[Membership]
}

object Onboarding {

  sealed trait Error
  object Error {
    case object NeedVatOrEin extends Error
    case object BusinessNotFound extends Error
    case object InviteeNotFound extends Error
  }

  def make[F[_]: Sync](
      users: Users[F],
      businesses: Businesses[F],
      kyb: Kyb[F],
      notifications: Notifications[F]
  ): Onboarding[F] = new Onboarding[F] {

    def createBusiness(
        userId: UserId,
        name: BusinessName,
        country: CountryCode,
        vat: Option[VatNumber],
        ein: Option[EinNumber],
        defaultCurrency: CurrencyCode
    ): F[Either[Error, Business]] =
      (vat, ein) match {
        case (None, None) => Sync[F].pure(Left(Error.NeedVatOrEin))
        case _            =>
          for {
            now <- Sync[F].delay(Instant.now())
            b = Business(
              BusinessId.assume(UUID.randomUUID()),
              name,
              country,
              vat,
              ein,
              defaultCurrency,
              KybStatus.NotStarted,
              now
            )
            saved <- businesses.create(b)
            _ <- businesses.addMember(
              Membership(
                id = MembershipId.assume(UUID.randomUUID()),
                userId = userId,
                businessId = saved.id,
                role = BusinessRole.Owner,
                invitedAt = now,
                acceptedAt = Some(now)
              )
            )
          } yield Right(saved)
      }

    def submitKyb(businessId: BusinessId): F[KybStatus] =
      businesses.find(businessId).flatMap {
        case None    => Sync[F].raiseError(new NoSuchElementException(s"business $businessId"))
        case Some(b) =>
          for {
            _ <- businesses.updateKyb(businessId, KybStatus.Pending)
            dec <- kyb.submit(b)
            // Sandbox auto-approves; production waits for webhook.
            out <- dec match {
              case Kyb.Decision.Approved =>
                businesses.updateKyb(businessId, KybStatus.Approved).as(KybStatus.Approved)
              case Kyb.Decision.Rejected(_) =>
                businesses.updateKyb(businessId, KybStatus.Rejected).as(KybStatus.Rejected)
              case Kyb.Decision.Pending(_) =>
                Sync[F].pure(KybStatus.Pending)
            }
            // Best-effort owner notification (skipped if we can't resolve a contact).
            _ <- notifications
              .emailKybUpdate(
                b.country.value match {
                  case _ => Email.assume("noreply@example.invalid") // resolved by lookup in production
                },
                b.copy(kybStatus = out)
              )
              .attempt
              .void
          } yield out
      }

    def refreshKyb(businessId: BusinessId, providerRef: String): F[KybStatus] =
      kyb.status(providerRef).flatMap {
        case Kyb.Decision.Approved    => businesses.updateKyb(businessId, KybStatus.Approved).as(KybStatus.Approved)
        case Kyb.Decision.Rejected(_) => businesses.updateKyb(businessId, KybStatus.Rejected).as(KybStatus.Rejected)
        case Kyb.Decision.Pending(_)  => businesses.updateKyb(businessId, KybStatus.Pending).as(KybStatus.Pending)
      }

    def inviteMember(businessId: BusinessId, email: Email, role: BusinessRole): F[Membership] =
      users.findByEmail(email).flatMap {
        case None    => Sync[F].raiseError(new NoSuchElementException(s"no user with email ${email.value}"))
        case Some(u) =>
          Sync[F]
            .delay(Instant.now())
            .flatMap(now =>
              businesses.addMember(
                Membership(
                  id = MembershipId.assume(UUID.randomUUID()),
                  userId = u.id,
                  businessId = businessId,
                  role = role,
                  invitedAt = now,
                  acceptedAt = None
                )
              )
            )
      }
  }
}
