package faunadb.importer.parser

import faunadb.importer.config._
import faunadb.importer.lang._
import faunadb.importer.report._
import faunadb.importer.values._
import java.io._

object InputParser {
  private val FileType = ".*\\.(\\w+)$".r

  def apply(file: File)(implicit c: Context): Result[InputParser] =
    parserFor(file) map (new InputParser(file, _))

  private def parserFor(file: File): Result[Parser] = file.getName match {
    case FileType(ext) => ext.toLowerCase() match {
      case "json" => Ok(JSON)
      case "csv"  => Ok(CSV)
      case other  => Err(s"Unsupported file type $other for ${file.getName}")
    }

    case _ => Err(s"Unsupported file ${file.getName}")
  }
}

final class InputParser private(file: File, fileParser: Parser)(implicit c: Context) {
  private val input = MonitoredIOReader(new BufferedReader(new FileReader(file)))(Stats.BytesRead.inc)
  def records(): Iterator[Result[Record]] = RecordParser.parse(fileParser.parse(input))
  def close(): Unit = input.close()
}
