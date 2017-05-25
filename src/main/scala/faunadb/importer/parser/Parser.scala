package faunadb.importer.parser

import faunadb.importer.config._
import faunadb.importer.lang._
import faunadb.importer.values._
import java.io.Reader

private[parser] trait Parser {
  def parse(reader: Reader)(implicit context: Context): Iterator[Result[Value]]
}
