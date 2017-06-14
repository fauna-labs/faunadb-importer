package faunadb.importer.values

import faunadb.importer.config._
import faunadb.importer.lang.{ Result, _ }
import faunadb.importer.persistence._
import faunadb.values.{ Value => FValue, _ }

object RecordConverter {
  def apply(cacheRead: IdCache.Read)(record: Record)(implicit context: Context): Result[FValue] =
    new RecordConverter(cacheRead, context, record).convert()
}

final class RecordConverter private(cacheRead: IdCache.Read, context: Context, record: Record) {

  def convert(): Result[FValue] = convertValue(record.data, 0)

  private def convertValue(value: Value, depth: Int): Result[FValue] = value match {
    case v: Object   => convertObject(v, depth)
    case v: Sequence => convertSequence(v, depth)
    case v: Scalar   => convertScalar(v, v.tpe)
    case _: Null     => Ok(NullV)
  }

  private def convertObject(obj: Object, depth: Int): Result[FValue] = {
    val converted = if (depth == 0) {
      obj.fields.filterKeys(!context.ignoredFields.contains(_)) flatMapS { case (key, value) =>
        context.typesByField
          .get(key)
          .map(convertScalar(value, _))
          .getOrElse(convertValue(value, depth + 1))
          .map(context.fieldNameByLegacyName.getOrElse(key, key) -> _)
      }
    } else {
      obj.fields.flatMapS { case (key, value) =>
        convertValue(value, depth + 1) map (key -> _)
      }
    }

    converted map (ObjectV(_))
  }

  private def convertSequence(value: Sequence, depth: Int): Result[FValue] =
    value.elements flatMapS (convertValue(_, depth + 1)) map (ArrayV(_))

  private def convertScalar(original: Value, expected: Type): Result[FValue] = {
    try {
      original match {
        case Scalar(_, _, raw) => expected match {
          case SelfRefT => Ok(StringV(record.id))
          case StringT  => Ok(StringV(raw))
          case LongT    => Ok(LongV(raw.toLong))
          case DoubleT  => Ok(DoubleV(raw.toDouble))
          case BoolT    => Ok(BooleanV(raw.toBoolean))

          case ts: TimeT => Ok(ts.convert(raw, TimeV(_)).get)
          case dt: DateT => Ok(dt.convert(raw, DateV(_)).get)

          case RefT(clazz) =>
            cacheRead.get(clazz, raw) map (id => Ok(RefV(s"classes/$clazz/$id"))) getOrElse {
              Err(s"Can not find referenced id $raw for class $clazz at ${record.localized}")
            }
        }

        case _: Null => Ok(NullV)
        case other   =>
          Err(s"Can not convert value to ${expected.name} at ${other.localized}")
      }
    } catch {
      case e: IllegalArgumentException =>
        Err(s"Can not convert value to ${expected.name} at ${original.localized}. ${e.getMessage}")

      case e: Throwable =>
        Err(s"Unexpected error ${e.getMessage} while trying to convert value " +
          s"to ${expected.name} at ${original.localized}")
    }
  }
}
