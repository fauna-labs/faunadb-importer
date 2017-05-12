package faunadb.importer.process.phases

import akka._
import akka.stream.scaladsl._
import faunadb.importer.errors._
import faunadb.importer.lang._
import faunadb.importer.persistence._
import faunadb.importer.values._
import faunadb.query.Expr
import faunadb.specs._
import faunadb.values.{ StringV, Value => FValue }

class GenerateIdsSpec extends ContextSpec with ConcurrentUtils {

  val fauna = new FaunaStream[Record] {
    var count = 0
    def runWith(queryRunner: QueryRunner): Flow[(Record, Expr), (Record, Result[FValue]), NotUsed] =
      Flow.fromFunction { case (record, _) =>
        count += 1
        record -> Ok(StringV(s"$count"))
      }
  }

  "The generate ids phase" should "generate ids for all records" in {
    val idCache = IdCache()

    await(
      GenerateIds(fauna, idCache)
        .run(Iterator(record1, record2, record3))
    )

    idCache shouldEqual allRecordsIds
  }

  it should "fail on duplicated id" in {
    the[ErrorHandler.Stop] thrownBy await(
      GenerateIds(fauna, IdCache())
        .run(Iterator(record1, record1))
    ) should have message "Duplicated id 1 found for record at line: 0, column: 0: null"
  }

}
