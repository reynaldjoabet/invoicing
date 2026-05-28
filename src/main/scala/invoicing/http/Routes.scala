package invoicing.http

import invoicing.domain.*
import invoicing.db.*
import invoicing.service.*
//import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import cats.effect.*
import cats.syntax.all.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import java.util.UUID

/** Single routing surface for biller + payer + admin.
  *
  * Header conventions:
  *   - `X-User-Id` is the authenticated principal (set by an upstream JWT validator in production).
  *   - `X-Business-Id` identifies which Business the user is acting *as*. The same user can act as biller in one
  *     request and payer in the next — the "mode switch" the brief describes is just changing this header.
  *
  * Each route that touches business data verifies the principal has a membership in the asserted business.
  */
final class Routes[F[_]: Concurrent: Clock](
    auth: Auth[F],
    users: Users[F],
    businesses: Businesses[F],
    bankAccounts: BankAccounts[F],
    services: Services[F],
    agreements: Agreements[F],
    invoices: Invoices[F],
    payments: Payments[F],
    onboarding: Onboarding[F],
    invoicingSvc: InvoicingService[F],
    paymentSvc: PaymentService[F],
    agreementSvc: AgreementService[F]
) extends Http4sDsl[F] {

  import Json.given

  // ----- principals -----

  private object PrincipalOf {
    def unapply(req: Request[F]): Option[(UserId, BusinessId)] =
      for {
        uHdr <- req.headers.get(org.typelevel.ci.CIString("X-User-Id"))
        bHdr <- req.headers.get(org.typelevel.ci.CIString("X-Business-Id"))
        u <- scala.util.Try(UUID.fromString(uHdr.head.value)).toOption.map(UserId.assume)
        b <- scala.util.Try(UUID.fromString(bHdr.head.value)).toOption.map(BusinessId.assume)
      } yield (u, b)
  }

  /** Returns Right if the principal has a membership in the asserted business. */
  private def authBusiness(req: Request[F]): F[Either[Response[F], (UserId, BusinessId)]] =
    PrincipalOf.unapply(req) match {
      case None         => Forbidden().map(Left(_))
      case Some((u, b)) =>
        businesses.membershipsFor(u).map(_.exists(_.businessId == b)).flatMap {
          case true  => Concurrent[F].pure(Right((u, b)))
          case false => Forbidden().map(Left(_))
        }
    }

  // ----- path vars -----

  private object BizVar { def unapply(s: String): Option[BusinessId] = uuidVar(s, BusinessId.assume) }
  private object MemVar { def unapply(s: String): Option[MembershipId] = uuidVar(s, MembershipId.assume) }
  private object ServiceVar { def unapply(s: String): Option[ServiceId] = uuidVar(s, ServiceId.assume) }
  private object AgreementVar { def unapply(s: String): Option[AgreementId] = uuidVar(s, AgreementId.assume) }
  private object InvoiceVar { def unapply(s: String): Option[InvoiceId] = uuidVar(s, InvoiceId.assume) }
  // private object BankAcctVar { def unapply(s: String): Option[BankAccountId] = uuidVar(s, BankAccountId.assume) }

  private def uuidVar[A](s: String, f: UUID => A): Option[A] =
    scala.util.Try(UUID.fromString(s)).toOption.map(f)

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {

    case GET -> Root / "health" =>
      Ok(Map("status" -> "ok").asJson)

    // ----- auth -----

    case req @ POST -> Root / "auth" / "signup" =>
      req.as[Json.SignupBody].flatMap { b =>
        auth.signup(b.email, b.password, b.fullName).flatMap {
          case Right(u)                         => Created(u)
          case Left(Auth.Error.EmailTaken)      => Conflict(Map("error" -> "email_taken").asJson)
          case Left(Auth.Error.PasswordTooWeak) => BadRequest(Map("error" -> "password_too_weak").asJson)
          case Left(other)                      => BadRequest(Map("error" -> other.toString).asJson)
        }
      }

    case req @ POST -> Root / "auth" / "login" =>
      req
        .as[Json.LoginBody]
        .flatMap(b =>
          auth.login(b.email, b.password).flatMap {
            case Right(u) => Ok(u)
            case Left(_)  => Forbidden(Map("error" -> "invalid").asJson)
          }
        )

    // ----- onboarding: businesses, KYB, members -----

    case req @ POST -> Root / "businesses" =>
      PrincipalOf.unapply(req) match {
        case None           => Forbidden()
        case Some((uid, _)) =>
          req
            .as[Json.CreateBusinessBody]
            .flatMap(b =>
              onboarding.createBusiness(uid, b.name, b.country, b.vat, b.ein, b.defaultCurrency).flatMap {
                case Right(biz) => Created(biz)
                case Left(err)  => BadRequest(Map("error" -> err.toString).asJson)
              }
            )
      }

    case req @ POST -> Root / "businesses" / BizVar(bid) / "kyb" =>
      authBusiness(req).flatMap {
        case Left(r)  => Concurrent[F].pure(r)
        case Right(_) =>
          onboarding.submitKyb(bid).flatMap(s => Accepted(Map("status" -> s.toString.toLowerCase).asJson))
      }

    case req @ POST -> Root / "businesses" / BizVar(bid) / "members" =>
      authBusiness(req).flatMap {
        case Left(r)  => Concurrent[F].pure(r)
        case Right(_) =>
          req.as[Json.InviteMemberBody].flatMap(b => onboarding.inviteMember(bid, b.email, b.role).flatMap(Created(_)))
      }

    case req @ POST -> Root / "memberships" / MemVar(mid) / "accept" =>
      authBusiness(req).flatMap {
        case Left(r)  => Concurrent[F].pure(r)
        case Right(_) => businesses.acceptMembership(mid).flatMap(_ => NoContent())
      }

    // ----- bank accounts -----

    case req @ POST -> Root / "bank-accounts" =>
      authBusiness(req).flatMap {
        case Left(r)         => Concurrent[F].pure(r)
        case Right((_, bid)) =>
          req.as[Json.CreateBankAccountBody].flatMap { b =>
            for {
              now <- Clock[F].realTimeInstant
              a = BankAccount(
                BankAccountId.assume(UUID.randomUUID()),
                bid,
                b.accountType,
                b.holderName,
                b.iban,
                b.bic,
                b.routingNumber,
                b.accountNumber,
                b.currency,
                b.isDefault,
                now
              )
              s <- bankAccounts.add(a)
              r <- Created(s)
            } yield r
          }
      }

    case req @ GET -> Root / "bank-accounts" =>
      authBusiness(req).flatMap {
        case Left(r)         => Concurrent[F].pure(r)
        case Right((_, bid)) => bankAccounts.listFor(bid).flatMap(Ok(_))
      }

    // ----- biller: services -----

    case req @ POST -> Root / "biller" / "services" =>
      authBusiness(req).flatMap {
        case Left(r)         => Concurrent[F].pure(r)
        case Right((_, bid)) =>
          req.as[Json.CreateServiceBody].flatMap { b =>
            for {
              now <- Clock[F].realTimeInstant
              s = Service(
                ServiceId.assume(UUID.randomUUID()),
                bid,
                b.title,
                b.description,
                b.kind,
                b.interval,
                b.unitPriceMinor,
                b.currency,
                b.taxBps,
                false,
                now
              )
              saved <- services.create(s)
              r <- Created(saved)
            } yield r
          }
      }

    case req @ GET -> Root / "biller" / "services" =>
      authBusiness(req).flatMap {
        case Left(r)         => Concurrent[F].pure(r)
        case Right((_, bid)) => services.listFor(bid, includeArchived = false).flatMap(Ok(_))
      }

    case req @ POST -> Root / "biller" / "services" / ServiceVar(sid) / "archive" =>
      authBusiness(req).flatMap {
        case Left(r)  => Concurrent[F].pure(r)
        case Right(_) => services.archive(sid).flatMap(_ => NoContent())
      }

    // ----- biller: agreements -----

    case req @ POST -> Root / "biller" / "agreements" =>
      authBusiness(req).flatMap {
        case Left(r)         => Concurrent[F].pure(r)
        case Right((_, bid)) =>
          req
            .as[Json.CreateAgreementBody]
            .flatMap(b =>
              agreementSvc.draft(bid, b.payerId, b.title, b.body, b.currency, b.serviceIds).flatMap(Created(_))
            )
      }

    case req @ POST -> Root / "biller" / "agreements" / AgreementVar(aid) / "send" =>
      authBusiness(req).flatMap {
        case Left(r)  => Concurrent[F].pure(r)
        case Right(_) => agreementSvc.send(aid).flatMap(_ => Accepted())
      }

    case req @ GET -> Root / "biller" / "agreements" =>
      authBusiness(req).flatMap {
        case Left(r)         => Concurrent[F].pure(r)
        case Right((_, bid)) => agreements.listForBiller(bid).flatMap(Ok(_))
      }

    // ----- biller: invoices -----

    case req @ POST -> Root / "biller" / "invoices" =>
      authBusiness(req).flatMap {
        case Left(r)         => Concurrent[F].pure(r)
        case Right((_, bid)) =>
          req.as[Json.IssueInvoiceBody].flatMap { b =>
            val lineInputs = b.lines
              .map(l => InvoicingService.LineInput(l.serviceId, l.description, l.quantity, l.unitPriceMinor, l.taxBps))
            invoicingSvc
              .issue(
                bid,
                b.payerId,
                b.agreementId,
                b.currency,
                lineInputs,
                b.issuedOn,
                b.dueOn,
                b.deliveryMode,
                b.notes
              )
              .flatMap {
                case Right(i)  => Created(i)
                case Left(err) => BadRequest(Map("error" -> err.toString).asJson)
              }
          }
      }

    case req @ POST -> Root / "biller" / "invoices" / InvoiceVar(iid) / "send" =>
      authBusiness(req).flatMap {
        case Left(r)  => Concurrent[F].pure(r)
        case Right(_) =>
          invoicingSvc.send(iid).flatMap {
            case Right(i)  => Ok(i)
            case Left(err) => BadRequest(Map("error" -> err.toString).asJson)
          }
      }

    case req @ POST -> Root / "biller" / "invoices" / InvoiceVar(iid) / "cancel" =>
      authBusiness(req).flatMap {
        case Left(r)  => Concurrent[F].pure(r)
        case Right(_) => invoicingSvc.cancel(iid).flatMap(_ => NoContent())
      }

    case req @ GET -> Root / "biller" / "invoices" =>
      authBusiness(req).flatMap {
        case Left(r)         => Concurrent[F].pure(r)
        case Right((_, bid)) => invoices.listForBiller(bid).flatMap(Ok(_))
      }

    // ----- payer: incoming -----

    case req @ GET -> Root / "payer" / "agreements" =>
      authBusiness(req).flatMap {
        case Left(r)         => Concurrent[F].pure(r)
        case Right((_, bid)) => agreements.listForPayer(bid).flatMap(Ok(_))
      }

    case req @ POST -> Root / "payer" / "agreements" / AgreementVar(aid) / "sign" =>
      authBusiness(req).flatMap {
        case Left(r)  => Concurrent[F].pure(r)
        case Right(_) => agreementSvc.sign(aid).flatMap(_ => NoContent())
      }

    case req @ POST -> Root / "payer" / "agreements" / AgreementVar(aid) / "reject" =>
      authBusiness(req).flatMap {
        case Left(r)  => Concurrent[F].pure(r)
        case Right(_) => agreementSvc.reject(aid).flatMap(_ => NoContent())
      }

    case req @ GET -> Root / "payer" / "invoices" =>
      authBusiness(req).flatMap {
        case Left(r)         => Concurrent[F].pure(r)
        case Right((_, bid)) => invoices.listForPayer(bid).flatMap(Ok(_))
      }

    case req @ POST -> Root / "payer" / "invoices" / InvoiceVar(iid) / "pay" =>
      authBusiness(req).flatMap {
        case Left(r)  => Concurrent[F].pure(r)
        case Right(_) =>
          req
            .as[Json.PayInvoiceBody]
            .flatMap(b =>
              paymentSvc.pay(iid, b.bankAccountId).flatMap {
                case Right(p)  => Created(p)
                case Left(err) => BadRequest(Map("error" -> err.toString).asJson)
              }
            )
      }

    case req @ GET -> Root / "payer" / "invoices" / InvoiceVar(iid) / "payments" =>
      authBusiness(req).flatMap {
        case Left(r)  => Concurrent[F].pure(r)
        case Right(_) => payments.listFor(iid).flatMap(Ok(_))
      }

    // ----- admin: simple user search -----

    case GET -> Root / "admin" / "users" / userSeg =>
      uuidVar(userSeg, UserId.assume) match {
        case None      => BadRequest()
        case Some(uid) => users.find(uid).flatMap(_.fold(NotFound())(u => Ok(u)))
      }
  }
}
