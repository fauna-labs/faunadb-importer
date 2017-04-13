package faunadb.specs

import faunadb.importer.persistence._
import faunadb.importer.values._

trait DataFixtures {
  val aClass = "a_class"
  val bClass = "b_class"

  val pos = Pos(0, 0)
  val record1 = Record("1", None, Null(pos))
  val record2 = Record("2", None, Null(pos))
  val record3 = Record("3", None, Null(pos))

  val allRecordsIds: IdCache = {
    val ids = IdCache()
    ids.put(aClass, record1.id, 1)
    ids.put(aClass, record2.id, 2)
    ids.put(aClass, record3.id, 3)

    ids
  }
}
