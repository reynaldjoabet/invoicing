package invoicing

import invoicing.config.*
import invoicing.db.*
import invoicing.external.*
import invoicing.http.*
import invoicing.service.*

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import org.typelevel.otel4s.metrics.Meter.Implicits.noop
import org.typelevel.otel4s.trace.Tracer.Implicits.noop
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import skunk.Session

object Main extends IOApp.Simple {

  def run: IO[Unit] = {
    val logger = Slf4jLogger.getLogger[IO]

    val server: Resource[IO, Unit] =
      for {
        cfg <- Resource.eval(AppConfig.load[IO])

        pool <- Session
          .Builder[IO]
          .pooled(
            max = cfg.db.poolMax
          )

        // ---- repositories ----
        usersR = Users.make[IO](pool)
        bizR = Businesses.make[IO](pool)
        bankR = BankAccounts.make[IO](pool)
        serviceR = Services.make[IO](pool)
        agreeR = Agreements.make[IO](pool)
        invoiceR = Invoices.make[IO](pool)
        paymentR = Payments.make[IO](pool)

        // ---- external sandboxes ----
        kyb <- Resource.eval(Kyb.sandbox[IO])
        rail <- Resource.eval(PaymentRail.sandbox[IO])
        notifications <- Resource.eval(Notifications.sandbox[IO](msg => logger.info(msg)))
        chatbot <- Resource.eval(Chatbot.sandbox[IO])

        // ---- contact lookup helper used by services ----
        // Resolve a business -> its owner's email (first acceptedAt membership).
        ownerContact: (invoicing.domain.BusinessId => IO[Option[invoicing.domain.Email]]) = bid =>
          for {
            mems <- bizR.membersOf(bid)
            owner = mems.find(_.role == invoicing.domain.BusinessRole.Owner).orElse(mems.headOption)
            email <- owner.flatTraverse(m => usersR.find(m.userId).map(_.map(_.email)))
          } yield email

        // ---- application services ----
        authSvc = Auth.make[IO](usersR)
        onboarding = Onboarding.make[IO](usersR, bizR, kyb, notifications)
        invoicingSvc = InvoicingService.make[IO](bizR, invoiceR, notifications, ownerContact)
        paymentSvc = PaymentService.make[IO](
          invoiceR,
          paymentR,
          bankR,
          bizR,
          rail,
          notifications,
          payerContact = ownerContact,
          billerContact = ownerContact
        )
        agreementSvc = AgreementService.make[IO](agreeR, notifications, ownerContact)

        // ---- HTTP ----
        routes = new Routes[IO](
          authSvc,
          usersR,
          bizR,
          bankR,
          serviceR,
          agreeR,
          invoiceR,
          paymentR,
          onboarding,
          invoicingSvc,
          paymentSvc,
          agreementSvc
        ).routes
        app = Logger.httpApp(logHeaders = true, logBody = false)(routes.orNotFound)

        host <- Resource.eval(IO.fromOption(Host.fromString(cfg.http.host))(new IllegalArgumentException("bad host")))
        port <- Resource.eval(IO.fromOption(Port.fromInt(cfg.http.port))(new IllegalArgumentException("bad port")))

        _ <- EmberServerBuilder.default[IO].withHost(host).withPort(port).withHttpApp(app).build
        _ <- Resource.eval(logger.info(s"Listening on http://$host:$port"))
        _ <- Resource.eval(IO.pure(chatbot)) // hold the ref; the chatbot HTTP surface is TODO
      } yield ()

    server.useForever
  }
}
