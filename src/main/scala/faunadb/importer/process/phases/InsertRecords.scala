package faunadb.importer.process.phases

import akka._
import akka.stream.scaladsl._
import faunadb.importer.config._
import faunadb.importer.lang.{ Result, _ }
import faunadb.importer.persistence._
import faunadb.importer.values._
import faunadb.query._
import faunadb.values.{ Value, _ }

private[process] object InsertRecords {
  def apply(fauna: FaunaStream[Record], idCache: IdCache)(implicit context: Context): InsertRecords =
    new InsertRecords(fauna, idCache)
}

private[process] final class InsertRecords(fauna: FaunaStream[Record], idCache: IdCache)
  (implicit val context: Context) extends Phase[Record] {

  val description = "Inserting records"

  protected val runFlow: Flow[(Record, Expr), (Record, Result[Value]), NotUsed] =
    fauna.runWith(QueryRunner.DiscardValues)

  protected val toFauna: (Record) => Result[Value] =
    RecordConverter(idCache)

  protected def buildExpr(record: Record): Result[Expr] =
    for {
      ref <- refFor(record)
      ts <- tsFor(record)
      data <- toFauna(record)
    } yield
      Insert(ref, ts, Action.Create, Obj("data" -> data))

  // TODO: Keep track of inserted rows so we can retry the failures later
  protected def handledResult(record: Record, value: Value): Result[Unit] = Ok(())

  private def refFor(record: Record): Result[RefV] =
    idCache.get(context.clazz, record.id) map { newId =>
      Ok(RefV(
        s"classes/${context.clazz}/$newId"
      ))
    } getOrElse
      Err(s"Could not find pre-generated id ${record.id} " +
        s"for record at ${record.localized}")

  private def tsFor(record: Record): Result[Expr] =
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
