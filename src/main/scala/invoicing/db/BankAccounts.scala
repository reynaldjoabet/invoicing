package invoicing.db

import invoicing.domain.*

import cats.effect.*
import cats.syntax.all.*
import skunk.*
import skunk.implicits.*

trait BankAccounts[F[_]] {
  def add(a: BankAccount): F[BankAccount]
  def listFor(businessId: BusinessId): F[List[BankAccount]]
  def find(id: BankAccountId): F[Option[BankAccount]]
}

object BankAccounts {

  import Codecs.{bankAccount as accountC, bankAccountId as accountIdC, businessId as businessIdC}

  def make[F[_]: Concurrent](pool: Resource[F, Session[F]]): BankAccounts[F] = new BankAccounts[F] {
    def add(a: BankAccount): F[BankAccount] =
      pool.use(_.prepare(Q.insert).flatMap(_.unique(a)))
    def listFor(businessId: BusinessId): F[List[BankAccount]] =
      pool.use(_.prepare(Q.listFor).flatMap(_.stream(businessId, 16).compile.toList))
    def find(id: BankAccountId): F[Option[BankAccount]] =
      pool.use(_.prepare(Q.byId).flatMap(_.option(id)))
  }

  private object Q {

    val insert: Query[BankAccount, BankAccount] =
      sql"""INSERT INTO bank_accounts (id, business_id, account_type, holder_name,
                                       iban, bic, routing_number, account_number,
                                       currency, is_default, created_at)
            VALUES $accountC
            RETURNING id, business_id, account_type, holder_name, iban, bic,
                      routing_number, account_number, currency, is_default, created_at""".query(accountC)

    val listFor: Query[BusinessId, BankAccount] =
      sql"""SELECT id, business_id, account_type, holder_name, iban, bic,
                   routing_number, account_number, currency, is_default, created_at
            FROM bank_accounts WHERE business_id = $businessIdC
            ORDER BY is_default DESC, created_at""".query(accountC)

    val byId: Query[BankAccountId, BankAccount] =
      sql"""SELECT id, business_id, account_type, holder_name, iban, bic,
                   routing_number, account_number, currency, is_default, created_at
            FROM bank_accounts WHERE id = $accountIdC""".query(accountC)
  }
}
