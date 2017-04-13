package faunadb.importer.persistence

import scala.collection.mutable

object IdCache {
  def apply(): IdCache = new IdCache()
}

final class IdCache private() {
  // Do NOT support concurrent read/writes
  private val ids = mutable.Map[String, Long]()

  def put(clazz: String, oldId: String, newId: Long): Option[Long] = ids.put(s"$clazz$oldId", newId)
  def get(clazz: String, oldId: String): Option[Long] = ids.get(s"$clazz$oldId")

  override def equals(other: scala.Any): Boolean = other match {
    case obj: IdCache => ids == obj.ids
    case _            => false
  }

  override def hashCode(): Int = ids.hashCode()
  override def toString: String = ids.toString()
}
