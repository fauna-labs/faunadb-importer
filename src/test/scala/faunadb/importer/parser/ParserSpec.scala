package faunadb.importer.parser

import faunadb.importer.config._
import faunadb.importer.lang._
import faunadb.importer.values._
import faunadb.specs._
import java.io.StringReader

trait ParserSpec extends ContextSpec {
  val parser: Parser
  def parse(str: String)(implicit context: Context): Stream[Result[Value]] =
    parser.parse(new StringReader(str)).toStream
}
