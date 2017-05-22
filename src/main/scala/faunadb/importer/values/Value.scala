package faunadb.importer.values

final case class Record(id: String, ts: Option[Value], data: Value) {
  def localized: String = data.localized
}

final case class Pos(line: Int, column: Int) {
  def localized: String = s"line: $line, column: $column"
}

sealed trait Value {
  val pos: Pos
  val repr: String
  def localized = s"${pos.localized}: $repr"
}

final case class Scalar(pos: Pos, tpe: Type, raw: String) extends Value {
  lazy val repr: String = tpe match {
    case StringT => "\"" + raw + "\""
    case _       => raw
  }
}

final case class Object(pos: Pos, fields: Map[String, Value]) extends Value {
  lazy val repr: String =
    fields
      .map { case (k, v) => s""""$k": ${v.repr}""" }
      .mkString("{ ", ", ", " }")
}

object Object {
  type Entry = (String, Value)
  def apply(pos: Pos, fields: Entry*): Object =
    new Object(pos, fields.toMap)
}

final case class Sequence(pos: Pos, elements: Vector[Value]) extends Value {
  lazy val repr: String = elements map (_.repr) mkString ("[", ", ", "]")
}

object Sequence {
  def apply(pos: Pos, elements: Value*): Sequence =
    new Sequence(pos, elements.toVector)
}

final case class Null(pos: Pos) extends Value {
  val repr: String = "null"
}
