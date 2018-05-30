package faunadb.specs

import java.io.File
import java.nio.file.Files

trait FileUtils {
  def withTempFile[A](name: String)(fn: File => A): A = {
    val file = Files.createTempFile(name, "tmp").toFile
    try fn(file) finally file.delete()
  }
}
