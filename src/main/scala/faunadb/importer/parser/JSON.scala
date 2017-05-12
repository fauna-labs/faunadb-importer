package faunadb.importer.parser

import com.fasterxml.jackson.core._
import faunadb.importer.config._
import faunadb.importer.lang._
import faunadb.importer.values._
import java.io.Reader
import scala.collection.generic.CanBuildFrom

private[parser] object JSON extends Parser {
  def parse(reader: Reader)(implicit context: Context): Iterator[Result[Value]] = {
    val jsonFactory = new JsonFactory()
    new JSON(jsonFactory.createParser(reader), context).parse()
  }
}

private final class JSON(parser: JsonParser, context: Context) {
  import JsonToken._

  def parse(): Iterator[Result[Value]] =
    Iterator
      .iterate(read(first = true))(_ => read())
      .takeWhile(_ != null)

  private def read(first: Boolean = false): Result[Value] = {
    try advance(first) catch {
      case e: JsonParseException =>
        Err(s"Invalid JSON entry at ${toPosition(e.getLocation).localized}. ${e.getOriginalMessage}")
    }
  }

  private def advance(firstValue: Boolean): Result[Value] = parser.nextToken() match {
    case START_ARRAY if firstValue &&
      context.skipRootElement =>
      advance(false)

    case END_ARRAY if context.skipRootElement &&
      parser.getParsingContext.inRoot() =>
      null

    case null => null
    case _    => readValue()
  }

  private def readValue(): Result[Value] = parser.currentToken() match {
    case START_OBJECT => collectUntil(END_OBJECT)(readObjectEntry, toObject)
    case START_ARRAY  => collectUntil(END_ARRAY)(readValue, toSequence)
    case VALUE_NULL   => Ok(Null(currentPos))

    case VALUE_STRING             => Ok(Scalar(currentPos, StringT, parser.getText))
    case VALUE_NUMBER_INT         => Ok(Scalar(currentPos, LongT, parser.getText))
    case VALUE_NUMBER_FLOAT       => Ok(Scalar(currentPos, DoubleT, parser.getText))
    case VALUE_TRUE | VALUE_FALSE => Ok(Scalar(currentPos, BoolT, parser.getText))

    case token => Err(s"Unexpected token $token at ${currentPos.localized}")
  }

  private def collectUntil[Coll, Elem, To](stop: JsonToken)(read: () => Result[Elem], build: (Pos, Coll) => To)
    (implicit cbf: CanBuildFrom[_, Elem, Coll]): Result[To] = {

    val pos = currentPos
    val res = cbf()
    var last: Result[Unit] = Result(())

    while (parser.nextValue() != stop) {
      if (last.isSuccess) {
        last = read() map (res += _)
      }
    }

    last flatMap (_ => Ok(build(pos, res.result())))
  }

  private def readObjectEntry(): Result[Object.Entry] = {
    val name = parser.getCurrentName
    readValue() map (name -> _)
  }

  private def toObject(pos: Pos, entries: Map[String, Value]): Object = Object(pos, entries)
  private def toSequence(pos: Pos, elems: Vector[Value]): Sequence = Sequence(pos, elems)
  private def currentPos: Pos = toPosition(parser.getTokenLocation)
  private def toPosition(loc: JsonLocation): Pos = Pos(loc.getLineNr, loc.getColumnNr)
}
