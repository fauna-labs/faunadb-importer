package faunadb.importer.values

import faunadb.importer.lang._
import org.joda.time._
import org.joda.time.format._
import scala.util.Try

sealed trait Type {val name: String}
case class RefT(clazz: String) extends Type {val name = s"ref($clazz)"}
case object SelfRefT extends Type {val name = "selfref"}
case object StringT extends Type {val name = "string"}
case object LongT extends Type {val name = "long"}
case object DoubleT extends Type {val name = "double"}
case object BoolT extends Type {val name = "boolean"}

sealed trait DateTimeT[A] extends Type {
  val format: Option[String]
  val defaultFormatter: DateTimeFormatter

  private lazy val formatter: DateTimeFormatter =
    format
      .map(DateTimeFormat.forPattern)
      .getOrElse(defaultFormatter)

  protected def convertUsing(formatter: DateTimeFormatter, raw: String): A
  def convert[B](raw: String, f: A => B): Try[B] = Try(f(convertUsing(formatter, raw)))
}

case class TimeT(format: Option[String]) extends DateTimeT[Instant] {
  val name = "timestamp"
  val defaultFormatter: DateTimeFormatter = ISODateTimeFormat.dateTimeParser()
  protected def convertUsing(formatter: DateTimeFormatter, raw: String): Instant =
    formatter.parseDateTime(raw).toInstant
}

case class DateT(format: Option[String]) extends DateTimeT[LocalDate] {
  val name = "date"
  val defaultFormatter: DateTimeFormatter = ISODateTimeFormat.yearMonthDay()
  protected def convertUsing(formatter: DateTimeFormatter, raw: String): LocalDate =
    formatter.parseDateTime(raw).toLocalDate
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
