package faunadb.importer.process.phases

import faunadb.importer.config._
import faunadb.importer.lang._
import faunadb.importer.persistence._
import faunadb.importer.values._
import faunadb.query.{ Expr, NextId }
import faunadb.values.{ Value => FValue }

private[process] object GenerateIds {
  def apply(cacheWrite: IdCache.Write, connPool: ConnectionPool)(implicit c: Context): GenerateIds =
    new GenerateIds(cacheWrite, connPool)
}

private[process] final class GenerateIds(cacheWrite: IdCache.Write, connPool: ConnectionPool)(implicit c: Context)
  extends Phase("Pre-generating ids", connPool)
    with PreserveValues {

  private val NextIdQuery = Ok(NextId()) // Avoid creating extra strings at each NextId call
  protected def buildQuery(record: Record): Result[Expr] = NextIdQuery

  protected def handleResponse(record: Record, value: FValue): Result[Unit] = {
    faunaIdAsString(value) flatMap { newId =>
      cacheWrite
        .put(c.clazz, record.id, newId)
        .map(_ => Err(s"Duplicated ID ${record.id} found for recored at: ${record.localized}"))
        .getOrElse(Result.unit)
    }
  }

  private def faunaIdAsString(id: FValue): Result[Long] = {
    id.to[String].map(toLong) getOrElse Err(s"Fauna did NOT returned a string ID. Value returned: $id")
  }

  private def toLong(s: String) = try Ok(s.toLong) catch {
    case e: Throwable => Err(s"Can NOT convert id returned from fauna to Long. ${e.getMessage}")
  }
}
