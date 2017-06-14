package faunadb.importer.process.phases

import faunadb.importer.errors._
import faunadb.importer.persistence._
import faunadb.importer.values._
import faunadb.specs._
import faunadb.values._

class InsertRecordsSpec
  extends ContextSpec
    with ConcurrentUtils
    with Mocks {

  "The insert records phase" should "insert records at FaunaDB" in new MockedFauna {
    queryReturns(NullV) // Discarding results

    await(
      InsertRecords(allRecordsIds, connPool).run(
        Iterator(
          record1,
          record2,
          record3
        )
      )
    )

    verifyQueryWasCalled()
  }

  it should "fail if can't find id for record" in new MockedFauna {
    the[ErrorHandler.Stop] thrownBy await(
      InsertRecords(new InMemoryIdCache(), connPool).run(
        Iterator(
          record1
        )
      )
    ) should have message "Could not find pre-generated id 1 for record at line: 0, column: 0: null"
  }

  it should "fail if timestamp field has incorrect type" in new MockedFauna {
    the[ErrorHandler.Stop] thrownBy await(
      InsertRecords(allRecordsIds, connPool).run(
        Iterator(
          record1.copy(ts = Some(Scalar(pos, DoubleT, "2.2")))
        )
      )
    ) should have message "Invalid value at timestamp field at line: 0, column: 0: 2.2"
  }

  it should "fail if timestamp field has ivalid timestamp" in new MockedFauna {
    the[ErrorHandler.Stop] thrownBy await(
      InsertRecords(allRecordsIds, connPool).run(
        Iterator(
          record1.copy(ts = Some(Scalar(pos, TimeT(None), "xxx")))
        )
      )
    ) should have message "Can not use timestamp field for record at line: 0, column: 0: null. For input string: \"xxx\""
  }
}
