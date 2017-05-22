package faunadb.importer.process.phases

import akka._
import akka.stream.scaladsl._
import faunadb.importer.errors._
import faunadb.importer.persistence._
import faunadb.query._
import faunadb.specs._
import faunadb.values._

class InsertRecordsSpec
  extends ContextSpec
    with ConcurrentUtils {

  var queriesCount = 0

  val fauna = new FaunaStream {
    def runWith(queryRunner: QueryRunner): Flow[Seq[Expr], Seq[Value], NotUsed] = Flow.fromFunction(
      _.map { _ =>
        queriesCount += 1
        NullV
      }
    )
  }

  "The insert records phase" should "insert records at FaunaDB" in {
    await(
      InsertRecords(fauna, allRecordsIds)
        .run(Stream(record1, record2, record3))
    )

    queriesCount shouldEqual 3
  }

  it should "fail if can't file id for record" in {
    the[ErrorHandler.Stop] thrownBy await(
      InsertRecords(fauna, IdCache()).run(Stream(record1))
    ) should have message "Could not find pre-generated id 1 for record at line: 0, column: 0: null"
  }
}
