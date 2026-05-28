package invoicing.service

import invoicing.domain.*
import invoicing.db.*
import invoicing.external.Notifications

import cats.effect.*
import cats.syntax.all.*

import java.time.Instant
import java.util.UUID

trait AgreementService[F[_]] {
  def draft(
      billerId: BusinessId,
      payerId: BusinessId,
      title: Title,
      body: Body,
      currency: CurrencyCode,
      services: List[ServiceId]
  ): F[Agreement]
  def send(id: AgreementId): F[Unit]
  def sign(id: AgreementId): F[Unit]
  def reject(id: AgreementId): F[Unit]
  def terminate(id: AgreementId): F[Unit]
  def remind(id: AgreementId): F[Unit]
}

object AgreementService {

  def make[F[_]: Sync](
      agreements: Agreements[F],
      notifications: Notifications[F],
      contactFor: BusinessId => F[Option[Email]]
  ): AgreementService[F] = new AgreementService[F] {

    def draft(
        billerId: BusinessId,
        payerId: BusinessId,
        title: Title,
        body: Body,
        currency: CurrencyCode,
        services: List[ServiceId]
    ): F[Agreement] =
      for {
        now <- Sync[F].delay(Instant.now())
        a = Agreement(
          id = AgreementId.assume(UUID.randomUUID()),
          billerId = billerId,
          payerId = payerId,
          title = title,
          body = body,
          currency = currency,
          status = AgreementStatus.Draft,
          serviceIds = services,
          sentAt = None,
          signedAt = None,
          terminatedAt = None,
          createdAt = now
        )
        saved <- agreements.create(a)
      } yield saved

    def send(id: AgreementId): F[Unit] =
      for {
        now <- Sync[F].delay(Instant.now())
        _ <- agreements.updateStatus(id, AgreementStatus.Sent, now)
        _ <- agreements.find(id).flatMap(_.traverse_(notifyPayer))
      } yield ()

    def sign(id: AgreementId): F[Unit] =
      Sync[F].delay(Instant.now()).flatMap(agreements.updateStatus(id, AgreementStatus.Signed, _))

    def reject(id: AgreementId): F[Unit] =
      Sync[F].delay(Instant.now()).flatMap(agreements.updateStatus(id, AgreementStatus.Rejected, _))

    def terminate(id: AgreementId): F[Unit] =
      Sync[F].delay(Instant.now()).flatMap(agreements.updateStatus(id, AgreementStatus.Terminated, _))

    def remind(id: AgreementId): F[Unit] =
      agreements.find(id).flatMap(_.traverse_(notifyPayer))

    private def notifyPayer(a: Agreement): F[Unit] =
      contactFor(a.payerId).flatMap(_.traverse_(notifications.emailAgreementSent(_, a)))
  }
}
