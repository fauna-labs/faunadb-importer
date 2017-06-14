package faunadb.importer.values

import faunadb.importer.lang._
import org.joda.time._
import scala.util._

sealed abstract class Type(val name: String)
case class RefT(clazz: String) extends Type(s"ref($clazz)")
case object SelfRefT extends Type("selfref")
case object StringT extends Type("string")
case object LongT extends Type("long")
case object DoubleT extends Type("double")
case object BoolT extends Type("boolean")

sealed trait DateTimeT[A] {
  this: Type =>

  protected val format: Option[String]
  protected val defaultParser: TimeParser
  protected def convertDateTime(dt: DateTime): A

  private lazy val parser =
    format
      .map(StringPatternParser(_))
      .getOrElse(defaultParser)

  def convert[B](raw: String, f: A => B): Try[B] =
    parser.parse(raw) map (convertDateTime _ andThen f)
}

case class TimeT(format: Option[String]) extends Type("timestamp") with DateTimeT[Instant] {
  protected val defaultParser: TimeParser = ISOTimeParser
  protected def convertDateTime(dt: DateTime): Instant = dt.toInstant
}

case class DateT(format: Option[String]) extends Type("date") with DateTimeT[LocalDate] {
  protected val defaultParser: TimeParser = StringPatternParser("yyyy-MM-dd")
  protected def convertDateTime(dt: DateTime): LocalDate = dt.toLocalDate
}

object Type {
  private val defRegx = "^(\\w+)(\\((.+)\\))?$".r

  def byDefinition(definition: String): Result[Type] = {
    defRegx.findFirstMatchIn(definition.trim) match {
      case Some(field) =>
        field.group(1) match {
          case "ref"    => Ok(if (field.group(3) == null) SelfRefT else RefT(field.group(3)))
          case "ts"     => Ok(TimeT(Option(field.group(3))))
          case "date"   => Ok(DateT(Option(field.group(3))))
          case "string" => Ok(StringT)
          case "long"   => Ok(LongT)
          case "double" => Ok(DoubleT)
          case "bool"   => Ok(BoolT)
          case other    => Err("Unknown type \"" + other + "\"")
        }

      case None => Err("Could NOT parse field definition \"" + definition + "\"")
    }
  }
}
