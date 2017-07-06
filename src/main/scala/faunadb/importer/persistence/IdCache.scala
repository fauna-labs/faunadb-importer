package faunadb.importer.persistence

import faunadb.importer.report._
import java.io._

trait CacheFile {
  def close(): Unit
}

object IdCache {
  trait Write extends CacheFile {
    def put(clazz: String, oldId: String, newId: Long): Option[Long]
  }

  trait Read extends CacheFile {
    def get(clazz: String, oldId: String): Option[Long]
  }

  def openForWrite(cacheFile: File): Write = {
    if (cacheFile.exists()) cacheFile.delete() else {
      cacheFile.getParentFile.mkdirs()
    }

    tryToOpen(new IdCacheWrite(cacheFile))
  }

  def openForRead(cacheFile: File): Read = {
    tryToOpen(new IdCacheRead(cacheFile))
  }

  private def tryToOpen[A](f: => A): A = {
    try f catch {
      case e: Throwable =>
        throw new IllegalStateException(
          "Missing or corrupted id cache. Cache must be re-generated.", e)
    }
  }
}

private final class IdCacheWrite(cacheFile: File)
  extends IdCache.Write {

  private[this] val table =
    new SSTable.Write(cacheFile)

  // noinspection IfElseToOption
  // java.Long becomes 0 when implicitly converted to scala.Long
  def put(clazz: String, oldId: String, newId: Long): Option[Long] = {
    val res = table.put(clazz, oldId, newId)
    if (res == null) None else Some(res)
  }

  def close(): Unit = {
    Log.info(s"Consolidating ${table.size()} ids...")
    table.close()
  }
}

private final class IdCacheRead(cacheFile: File)
  extends IdCache.Read {

  private[this] val table =
    new SSTable.Read(cacheFile)

  // noinspection IfElseToOption
  // java.Long becomes 0 when implicitly converted to scala.Long
  def get(clazz: String, oldId: String): Option[Long] = {
    val res = table.get(clazz, oldId)
    if (res == null) None else Some(res)
  }

  def close(): Unit = {
    table.close()
  }
}
