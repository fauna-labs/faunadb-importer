package faunadb.specs

import java.io.{ Reader, StringReader }
import scala.language.implicitConversions

trait IOReaderUtils {
  implicit def strToSource(str: String): Reader =
    new StringReader(str)
}
