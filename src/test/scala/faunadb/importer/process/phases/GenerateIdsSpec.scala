package faunadb.importer.process.phases

import faunadb.importer.errors._
import faunadb.importer.persistence._
import faunadb.specs._
import faunadb.values._

class GenerateIdsSpec
  extends ContextSpec
    with ConcurrentUtils
    with Mocks {

  val idCache = new InMemoryIdCache()

  "The generate ids phase" should "generate ids for all records" in new MockedFauna {
    batchedQueryReturns(
      StringV(record1.id),
      StringV(record2.id),
      StringV(record3.id)
    )

    await(
      GenerateIds(idCache, connPool).run(
        Iterator(
          record1,
          record2,
          record3
        )
      )
    )

    idCache shouldEqual allRecordsIds
  }

  it should "fail on duplicated IDs" in new MockedFauna {
    batchedQueryReturns(
      StringV(record1.id),
      StringV(record1.id)
    )

    the[ErrorHandler.Stop] thrownBy await(
      GenerateIds(idCache, connPool).run(
        Iterator(
          record1,
          record1
        )
      )
    ) should have message "Duplicated ID 1 found for recored at: line: 0, column: 0: null"
  }

  it should "fail if fauna does not return a string id" in new MockedFauna {
    batchedQueryReturns(NullV)

    the[ErrorHandler.Stop] thrownBy await(
      GenerateIds(idCache, connPool).run(
        Iterator(
          record1
        )
      )
    ) should have message "Fauna did NOT returned a string ID. Value returned: NullV"
  }

  it should "fail if fauna returns a unconvertable string" in new MockedFauna {
    batchedQueryReturns(StringV("Can't be Long"))

    the[ErrorHandler.Stop] thrownBy await(
      GenerateIds(idCache, connPool).run(
        Iterator(
          record1
        )
      )
    ) should have message "Can NOT convert id returned from fauna to Long. For input string: \"Can't be Long\""
  }
}
