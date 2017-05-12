package faunadb.importer.parser

import com.fasterxml.jackson.core._
import com.fasterxml.jackson.dataformat.csv._
import faunadb.importer.config._
import faunadb.importer.lang._
import faunadb.importer.values._
import java.io.Reader

private[parser] object CSV extends TextFileParser {
  val name = "CSV"
  def parserFor(reader: Reader): CsvParser =
    new CsvFactory().createParser(reader)
}

private[parser] object TSV extends TextFileParser {
  val name: String = "TSV"

  def parserFor(reader: Reader): CsvParser = {
    val parser = new CsvFactory().createParser(reader)
    parser.setSchema(CsvSchema.builder().setColumnSeparator('\t').build())
    parser
  }
}

private sealed abstract class TextFileParser extends Parser {
  val name: String
  def parserFor(reader: Reader): CsvParser
  def parse(reader: Reader)(implicit context: Context): Iterator[Result[Value]] =
    new TextFile(name, parserFor(reader), context).parse()
}

private final class TextFile(ext: String, parser: CsvParser, context: Context) {
  import JsonToken._

  def parse(): Iterator[Result[Value]] =
    Iterator
      .iterate(read(first = true))(_ => read())
      .takeWhile(_ != null)

  private def read(first: Boolean = false): Result[Value] = {
    try advance(first) catch {
      case e: JsonParseException =>
        Err(s"Invalid $ext entry at ${toPosition(e.getLocation).localized}. ${e.getOriginalMessage}")
    }
  }

  private def advance(firstValue: Boolean): Result[Value] = parser.nextToken() match {
    case START_ARRAY if firstValue &&
      context.skipRootElement =>
      while (parser.currentToken() != END_ARRAY) parser.nextToken()
      parser.nextToken()
      readValue()

    case null => null
    case _    => readValue()
  }

  private def readValue(): Result[Value] = parser.currentToken() match {
    case VALUE_STRING if isNullText => Ok(Null(currentTokenPos))
    case VALUE_STRING               => Ok(Scalar(currentTokenPos, StringT, parser.getText))
    case START_ARRAY                => collectObject()
    case token                      => Err(s"Unexpected token $token at ${currentTokenPos.localized}")
  }

  private def collectObject(): Result[Value] = {
    val res = Map.newBuilder[String, Value]
    val pos = currentLocation
    var last: Result[Unit] = Result(())
    var column = 0

    while (parser.nextValue() != END_ARRAY) {
      if (last.isSuccess) {
        last = readColumn(column) map (res += _)
        column += 1
      }
    }

    last flatMap (_ => Ok(Object(pos, res.result())))
  }

  private def readColumn(column: Int): Result[(String, Value)] =
    readValue() flatMap { value =>
      if (column >= context.fieldsInOrder.length)
        Err(s"Line has more columns than specified at ${currentTokenPos.localized}")
      else
        Ok(context.fieldsInOrder(column) -> value)
    }

  private def isNullText: Boolean = {
    val text = parser.getText.trim
    text.isEmpty || text.equalsIgnoreCase("null")
  }

  private def currentLocation: Pos = toPosition(parser.getCurrentLocation)
  private def currentTokenPos: Pos = toPosition(parser.getTokenLocation)
  private def toPosition(loc: JsonLocation): Pos = Pos(loc.getLineNr, loc.getColumnNr)
}
