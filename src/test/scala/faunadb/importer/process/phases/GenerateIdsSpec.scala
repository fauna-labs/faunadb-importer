package faunadb.importer.process.phases

import akka._
import akka.stream.scaladsl._
import faunadb.importer.errors._
import faunadb.importer.persistence._
import faunadb.query._
import faunadb.specs._
import faunadb.values._

class GenerateIdsSpec extends ContextSpec with ConcurrentUtils {

  val fauna = new FaunaStream {
    def runWith(queryRunner: QueryRunner): Flow[Seq[Expr], Seq[Value], NotUsed] = Flow.fromFunction {
      _.zip(Stream.from(1)).map {
        case (_, id) => StringV(s"$id")
      }
    }
  }

  "The generate ids phase" should "generate ids for all records" in {
    val idCache = IdCache()

    await(
      GenerateIds(fauna, idCache)
        .run(Stream(record1, record2, record3))
    )

    idCache shouldEqual allRecordsIds
  }

  it should "fail on duplicated id" in {
    the[ErrorHandler.Stop] thrownBy await(
      GenerateIds(fauna, IdCache())
        .run(Stream(record1, record1))
    ) should have message "Duplicated id 1 found for record at line: 0, column: 0: null"
  }

}
