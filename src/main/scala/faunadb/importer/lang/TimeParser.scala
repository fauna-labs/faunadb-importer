package faunadb.importer.lang

import org.joda.time._
import org.joda.time.format._
import scala.util._

sealed trait TimeParser {
  def parse(raw: String): Try[DateTime]
}

object ISOTimeParser extends TimeParser {
  def parse(raw: String): Try[DateTime] =
    Try(new DateTime(raw.toLong, DateTimeZone.UTC))
}

object StringPatternParser {
  def apply(format: String): StringPatternParser =
    new StringPatternParser(format)
}

final class StringPatternParser private(format: String) extends TimeParser {
  private val parser: DateTimeFormatter =
    DateTimeFormat
      .forPattern(format)
      .withZoneUTC()

  def parse(raw: String): Try[DateTime] =
    Try(parser.parseDateTime(raw))
}
