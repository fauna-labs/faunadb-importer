package faunadb.importer.process.phases

import akka._
import akka.stream.scaladsl._
import faunadb.importer.errors._
import faunadb.importer.lang._
import faunadb.importer.persistence._
import faunadb.importer.values._
import faunadb.query.Expr
import faunadb.specs._
import faunadb.values.{ NullV, Value => FValue }

class InsertRecordsSpec
  extends ContextSpec
    with ConcurrentUtils {

  var queriesCount = 0

  val fauna = new FaunaStream[Record] {
    def runWith(queryRunner: QueryRunner): Flow[(Record, Expr), (Record, Result[FValue]), NotUsed] =
      Flow.fromFunction { case (record, _) =>
        queriesCount += 1
        record -> Ok(NullV)
      }
  }

  "The insert records phase" should "insert records at FaunaDB" in {
    await(
      InsertRecords(fauna, allRecordsIds)
        .run(Iterator(record1, record2, record3))
    )

    queriesCount shouldEqual 3
  }

  it should "fail if can't file id for record" in {
    the[ErrorHandler.Stop] thrownBy await(
      InsertRecords(fauna, IdCache()).run(Iterator(record1))
    ) should have message "Could not find pre-generated id 1 for record at line: 0, column: 0: null"
  }
}
