package faunadb.importer.persistence

import faunadb.specs._

class IdCacheSpec
  extends SimpleSpec
    with DataFixtures {

  "The ids table" should "store new ids by legacy ids grouped by class" in {
    val ids = IdCache()
    ids.put(aClass, "1", 1)
    ids.put(bClass, "1", 2)
    ids.put(bClass, "2", 3)

    ids.get(aClass, "1").get shouldEqual 1
    ids.get(bClass, "1").get shouldEqual 2
    ids.get(bClass, "2").get shouldEqual 3
  }
}
