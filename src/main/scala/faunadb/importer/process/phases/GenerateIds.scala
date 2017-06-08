package faunadb.importer.process.phases

import faunadb.importer.config._
import faunadb.importer.lang._
import faunadb.importer.persistence._
import faunadb.importer.values._
import faunadb.query.{ Expr, NextId }
import faunadb.values.{ Value => FValue }

private[process] object GenerateIds {
  def apply(idCache: IdCache, connPool: ConnectionPool)(implicit c: Context): GenerateIds =
    new GenerateIds(idCache, connPool)
}

private[process] final class GenerateIds(idCache: IdCache, connPool: ConnectionPool)(implicit c: Context)
  extends Phase("Pre-generating ids", connPool)
    with PreserveValues {

  protected def buildQuery(record: Record): Result[Expr] = Ok(NextId())

  protected def handleResponse(record: Record, value: FValue): Result[Unit] = {
    faunaIdAsString(value) flatMap { newId =>
      idCache.put(c.clazz, record.id, newId) map { _ =>
        Err(
          s"Duplicated id ${record.id} found for record at " +
            s"${record.localized}"
        )
      } getOrElse Ok(())
    }
  }

  private def faunaIdAsString(id: FValue): Result[Long] =
    id.to[String]
      .map(toLong)
      .getOrElse(Err(s"Fauna did NOT returned a string ID. Value returned: $id"))

  private def toLong(s: String) = try Ok(s.toLong) catch {
    case e: Throwable => Err(s"Can NOT convert id returned from fauna to Long. ${e.getMessage}")
  }
}
