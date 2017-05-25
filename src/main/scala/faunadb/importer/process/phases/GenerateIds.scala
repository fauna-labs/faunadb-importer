package faunadb.importer.process.phases

import akka._
import akka.stream.scaladsl._
import faunadb.importer.config._
import faunadb.importer.lang._
import faunadb.importer.persistence._
import faunadb.importer.values._
import faunadb.query.{ Expr, NextId }
import faunadb.values.{ Value => FValue }

private[process] object GenerateIds {
  def apply(fauna: FaunaStream[Record], idCache: IdCache)(implicit c: Context): GenerateIds =
    new GenerateIds(fauna, idCache)
}

private[process] final class GenerateIds(fauna: FaunaStream[Record], idCache: IdCache)
  (implicit val context: Context) extends Phase[Record] {

  val description = "Pre-generating ids"

  protected val runFlow: Flow[(Record, Expr), (Record, Result[FValue]), NotUsed] =
    fauna.runWith(QueryRunner.PreserveValues)

  protected def buildExpr(record: Record): Result[Expr] =
    Ok(NextId())

  protected def handledResult(record: Record, value: FValue): Result[Unit] =
    faunaIdAsString(value) flatMap { newId =>
      idCache.put(context.clazz, record.id, newId) map { _ =>
        Err(
          s"Duplicated id ${record.id} found for record at " +
            s"${record.localized}"
        )
      } getOrElse Ok(())
    }

  private def faunaIdAsString(id: FValue): Result[Long] =
    id.to[String]
      .map(toLong)
      .getOrElse(Err(s"Fauna did NOT returned a string ID. Value returned: $id"))

  private def toLong(s: String) = try Ok(s.toLong) catch {
    case e: Throwable => Err(s"Can NOT convert id returned from fauna to Long. ${e.getMessage}")
  }
}
