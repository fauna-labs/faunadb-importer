package faunadb.importer.process.phases

import faunadb.importer.config._
import faunadb.importer.lang.{ Result, _ }
import faunadb.importer.persistence._
import faunadb.importer.values._
import faunadb.query._
import faunadb.values.{ Value => FValue, _ }

private[process] object InsertRecords {
  def apply(cacheRead: IdCache.Read, connPool: ConnectionPool)(implicit c: Context): InsertRecords =
    new InsertRecords(cacheRead, connPool)
}

private[process] final class InsertRecords(cacheRead: IdCache.Read, connPool: ConnectionPool)(implicit c: Context)
  extends Phase("Inserting records", connPool)
    with DiscardValues {

  private val toFauna: (Record) => Result[FValue] = RecordConverter(cacheRead)

  protected def buildQuery(record: Record): Result[Expr] = {
    for {
      ref <- refFor(record)
      ts <- tsFor(record)
      data <- toFauna(record)
    } yield
      Insert(ref, ts, Action.Create, Obj("data" -> data))
  }

  private def refFor(record: Record): Result[RefV] = {
    cacheRead.get(c.clazz, record.id) map { newId =>
      Ok(RefV(
        s"classes/${c.clazz}/$newId"
      ))
    } getOrElse {
      Err(s"Could not find pre-generated id ${record.id} " +
        s"for record at ${record.localized}")
    }
  }

  private def tsFor(record: Record): Result[Expr] = {
    record.ts match {
      case Some(Scalar(_, t @ TimeT(_), raw)) =>
        val res = t.convert(raw, ts => Ok(TimeV(ts): Expr)) recover {
          case e => Err(s"Can not use timestamp field for record at ${record.localized}. ${e.getMessage}")
        }
        res.get

      case None        => Ok(Time("now"))
      case Some(other) => Err(s"Invalid value at timestamp field at ${other.localized}")
    }
  }
}
