package faunadb.importer.persistence

import java.io._

object State {
  def load[A](file: File): Option[A] = {
    if (file.exists()) {
      val input = new ObjectInputStream(new FileInputStream(file))
      try Some(input.readObject().asInstanceOf[A]) finally input.close()
    } else {
      None
    }
  }

  def store(file: File, value: Any): Unit = {
    file.getParentFile.mkdirs()
    val output = new ObjectOutputStream(new FileOutputStream(file))
    try output.writeObject(value) finally output.close()
  }
}
