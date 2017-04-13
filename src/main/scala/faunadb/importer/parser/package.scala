package faunadb.importer

import faunadb.importer.config._
import faunadb.importer.lang._
import faunadb.importer.values._
import java.io.Reader

package object parser {
  private[parser] trait Parser {
    def parse(reader: Reader)(implicit context: Context): Stream[Result[Value]]
  }
}
