package faunadb.importer.config

import faunadb.importer.lang._
import faunadb.importer.values._
import java.io.File

case class Context(
  // Global configuration
  config: Config,

  // Class name in which data parsed in this context should be saved
  clazz: String,

  // Identify the field to be used as the instance's id. Usually legacy primary keys
  idField: Option[String] = None,

  // Field to be used as the timestamp for the inserted instance
  tsField: Option[String] = None,

  // Maps existing fields to its desired type
  typesByField: Map[String, Type] = Map(),

  // Fields' names on the order as the were configured
  fieldsInOrder: IndexedSeq[String] = IndexedSeq.empty,

  // Map field's legacy names to their new names in Fauna,
  fieldNameByLegacyName: Map[String, String] = Map.empty,

  // List of fields to be ignored from final instance data,
  ignoredFields: Set[String] = Set.empty,

  // If true, when a JSON file starts with a array, only its entries will be parsed
  // If true, when a parsing CSV/TSV files, the first line will be skipped
  skipRootElement: Boolean = false
)

final class ContextBuilder {
  import ContextBuilder._

  private var currentFile: Option[File] = None
  private var stepsByFile = Map.empty[File, IndexedSeq[BuildStep]]
  private val currentSteps = IndexedSeq.newBuilder[BuildStep]

  def +=(config: BuildStep): ContextBuilder = {
    currentSteps += config
    this
  }

  def file(f: File): ContextBuilder = {
    groupStepsByFile()
    currentFile = Some(f)
    this
  }

  def result(config: Config): Result[Seq[(File, Context)]] = {
    groupStepsByFile()

    stepsByFile.flatMapS { case (file, steps) =>
      steps
        .foldLeftS(Context(config, ""))((c, fn) => fn(c))
        .flatMap { context =>
          if (context.clazz.nonEmpty) Ok(file -> context)
          else Err("\"class\" was NOT specified for import file " + file.getName)
        }
    } flatMap { filesToImport =>
      if (filesToImport.isEmpty) Err("No file to import could be found")
      else Ok(filesToImport.toSeq)
    }
  }

  private def groupStepsByFile(): Unit =
    currentFile foreach { file =>
      val steps = stepsByFile.getOrElse(file, IndexedSeq.empty[BuildStep])
      stepsByFile = stepsByFile + (file -> (steps ++ currentSteps.result()))
      currentSteps.clear()
    }
}


object ContextBuilder {
  type BuildStep = (Context => Result[Context])

  def apply(): ContextBuilder =
    new ContextBuilder()

  final object Dsl {
    def SkipRoot(value: Boolean): BuildStep = c => Ok(c.copy(skipRootElement = value))
    def Clazz(value: String): BuildStep = c => Ok(c.copy(clazz = value))
    def Ignore(value: String): BuildStep = c => Ok(c.copy(ignoredFields = c.ignoredFields + value))
    def TSField(value: String): BuildStep = c => Ok(c.copy(tsField = Option(value)))

    def Field(name: String, tpe: Type): BuildStep = { c =>
      val res = tpe match {
        case SelfRefT if c.idField.isDefined => Err("There can be only one self-ref field per import file")
        case SelfRefT                        => Ok(c.copy(idField = Some(name)))
        case _                               => Ok(c)
      }

      res map (_.copy(
        fieldsInOrder = c.fieldsInOrder :+ name,
        typesByField = c.typesByField + (name -> tpe)
      ))
    }

    def Rename(oldName: String, newName: String): BuildStep = { c =>
      if (oldName != newName)
        Ok(c.copy(
          fieldNameByLegacyName =
            c.fieldNameByLegacyName + (oldName -> newName)
        ))
      else
        Ok(c)
    }
  }
}
