package faunadb.specs

import faunadb.importer.persistence._
import faunadb.importer.values._
import scala.collection.mutable

trait DataFixtures {
  val aClass = "a_class"
  val bClass = "b_class"

  val pos = Pos(0, 0)
  val record1 = Record("1", None, Null(pos))
  val record2 = Record("2", None, Null(pos))
  val record3 = Record("3", None, Null(pos))

  val allRecordsIds: InMemoryIdCache = {
    val ids = new InMemoryIdCache()
    ids.put(aClass, record1.id, 1)
    ids.put(aClass, record2.id, 2)
    ids.put(aClass, record3.id, 3)
    ids
  }

  class InMemoryIdCache extends IdCache.Write with IdCache.Read {
    private val ids =
      mutable.Map.empty[String, mutable.Map[String, Long]]

    def put(clazz: String, oldId: String, newId: Long): Option[Long] = {
      if (!ids.contains(clazz)) ids(clazz) = mutable.Map.empty
      ids(clazz).put(oldId, newId)
    }

    def get(clazz: String, oldId: String): Option[Long] =
      ids.get(clazz) flatMap (_.get(oldId))

    def close(): Unit = ()

    override def toString: String = ids.toString
    override def hashCode(): Int = ids.hashCode()

    override def equals(obj: Any): Boolean = obj match {
      case c: InMemoryIdCache => ids.toSeq == c.ids.toSeq
      case _                  => false
    }
  }
}
