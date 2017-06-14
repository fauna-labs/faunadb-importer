package faunadb.importer.persistence

import faunadb.specs._
import java.io._

class IdCacheSpec
  extends SimpleSpec
    with DataFixtures
    with FileUtils {

  def whenWriting[A](file: File)(f: IdCache.Write => A): A = {
    val cache = IdCache.openForWrite(file)
    try f(cache) finally cache.close()
  }

  def whenReading[A](file: File)(f: IdCache.Read => A): A = {
    val cache = IdCache.openForRead(file)
    try f(cache) finally cache.close()
  }

  "The ids table" should "store new ids by legacy ids grouped by class" in withTempFile("IdCache_file") { file =>
    whenWriting(file) { cache =>
      cache.put(aClass, "1", 1)
      cache.put(bClass, "2", 3)
      cache.put(bClass, "1", 2)
    }

    whenReading(file) { cache =>
      cache.get(aClass, "1").get shouldEqual 1
      cache.get(bClass, "2").get shouldEqual 3
      cache.get(bClass, "1").get shouldEqual 2
      cache.get(bClass, "non-existing-id") shouldBe None
      cache.get("non-existing-class", "1") shouldBe None
    }
  }

  it should "throw an exception if fail to open file" in withTempFile("IdCache_corrupted") { file =>
    val out = new FileOutputStream(file)
    try out.write("nonsense".getBytes()) finally out.close()

    the[IllegalStateException] thrownBy IdCache.openForRead(file) should have message
      "Missing or corrupted id cache. Cache must be re-generated."
  }
}
