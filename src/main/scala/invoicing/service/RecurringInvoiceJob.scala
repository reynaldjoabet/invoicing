package invoicing.service

import invoicing.domain.*
import invoicing.db.*

import cats.effect.*
import cats.syntax.all.*

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Background job that issues invoices for recurring services on their due date. Runs once a day. Picks every active
  * recurring service tied to a signed agreement and emits an invoice if the configured interval has elapsed since the
  * last invoice for that (biller, payer, service).
  *
  * Idempotent: re-running on the same day is a no-op because the previous invoice's `created_at` falls within the
  * lookback window.
  */
trait RecurringInvoiceJob[F[_]] {
  def runOnce(asOf: LocalDate): F[RecurringInvoiceJob.Summary]
}

object RecurringInvoiceJob {

  final case class Summary(generated: Int, skipped: Int)

  def make[F[_]: Concurrent](
      agreements: Agreements[F],
      services: Services[F],
      invoicing: InvoicingService[F],
      // Production injects an Invoices repo to check 'last invoice for service'.
      // Sketched here as a lookup function so the trait stays narrow.
      lastInvoiceFor: (BusinessId, BusinessId, ServiceId) => F[Option[LocalDate]]
  ): RecurringInvoiceJob[F] = new RecurringInvoiceJob[F] {

    def runOnce(asOf: LocalDate): F[Summary] =
      for {
        signed <- agreements.listSigned
        pairs <- signed.flatTraverse { a =>
          a.serviceIds
            .traverse(sid => services.find(sid).map(_.map((a, _))))
            .map(_.flatten)
        }
        summary <- pairs.foldM(Summary(0, 0)) { case (acc, (agr, svc)) =>
          (svc.kind, svc.interval) match {
            case (ServiceKind.Recurring, Some(interval)) =>
              lastInvoiceFor(agr.billerId, agr.payerId, svc.id).flatMap { last =>
                if (isDue(last, asOf, interval))
                  issueOne(agr, svc, asOf).map {
                    case Right(_) => acc.copy(generated = acc.generated + 1)
                    case Left(_)  => acc.copy(skipped = acc.skipped + 1)
                  }
                else
                  Concurrent[F].pure(acc.copy(skipped = acc.skipped + 1))
              }
            case _ => Concurrent[F].pure(acc)
          }
        }
      } yield summary

    private def issueOne(agr: Agreement, svc: Service, asOf: LocalDate): F[Either[InvoicingService.Error, Invoice]] = {
      val line = InvoicingService.LineInput(
        serviceId = Some(svc.id),
        description = svc.title,
        quantity = LineQty.assume(BigDecimal(1)),
        unitPriceMinor = svc.unitPriceMinor,
        taxBps = svc.taxBps
      )
      invoicing.issue(
        billerId = agr.billerId,
        payerId = agr.payerId,
        agreementId = Some(agr.id),
        currency = svc.currency,
        lines = List(line),
        issuedOn = asOf,
        dueOn = asOf.plusDays(14),
        deliveryMode = InvoiceDeliveryMode.Auto,
        notes = None
      )
    }
  }

  /** Returns true when `last` is far enough in the past that another invoice is due. */
  def isDue(last: Option[LocalDate], today: LocalDate, interval: RecurringInterval): Boolean = {
    val daysSince = last.fold(Long.MaxValue)(d => ChronoUnit.DAYS.between(d, today))
    val threshold = interval match {
      case RecurringInterval.Weekly    => 7L
      case RecurringInterval.Monthly   => 28L
      case RecurringInterval.Quarterly => 90L
      case RecurringInterval.Annual    => 365L
    }
    daysSince >= threshold
  }
}
