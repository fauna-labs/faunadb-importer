package faunadb.specs

import java.io.File
import java.nio.file.Files

trait FileUtils {

  def withTempFile[A](name: String)(fn: (File) => A): A = {
    val file = tempFile(name)
    try fn(file) finally file.delete()
  }

  def withTempDirectory[A](name: String)(fn: (File) => A): A = {
    val dir = tempDir(name)
    try fn(dir) finally dir.delete()
  }

  private def tempFile(name: String): File = File.createTempFile(name, null)
  private def tempDir(name: String): File = Files.createTempDirectory(name).toFile
}
