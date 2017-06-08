package faunadb.importer

import com.fasterxml.jackson.annotation._
import com.fasterxml.jackson.core.`type`._
import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.dataformat.yaml._
import faunadb.importer.config._
import faunadb.importer.errors._
import faunadb.importer.lang._
import faunadb.importer.report._
import faunadb.importer.values._
import java.io.File
import java.net.URL
import java.util
import scala.collection.JavaConversions._
import scala.util.Try
import scopt._

private[importer] object CmdArgs {
  private val c = new CmdArgs()

  def parse(args: Array[String]): Option[(Config, Seq[(File, Context)])] =
    if (parser.parse(args)(Zero.zero(c)))
      c.result()
    else
      None

  private val parser = new scopt.OptionParser[CmdArgs]("faunadb-import") {
    import ConfigBuilder.Dsl._
    import ContextBuilder.Dsl._

    head(
      """faunadb-import 1.0-SNAPSHOT
        |For more information check https://github.com/fauna/faunadb-importer
        |""".stripMargin
    )

    opt[String]("secret")
      .text("FaunaDB key's secret to use when authenticating requests")
      .abbr("s")
      .required()
      .validate(notBlank("secret"))
      .foreach(c.config += Secret(_))

    opt[Seq[String]]("endpoints")
      .text("FaunaDB endpoints's urls. The import load will be balanced across all endpoints")
      .valueName("<url,url...>")
      .abbr("e")
      .validate { endpoints =>
        endpoints
          .find(url => !isValidUrl(url))
          .map(url => failure(s"Invalid URL: $url"))
          .getOrElse(success)
      }
      .foreach(c.config += Endpoints(_))

    opt[Int]("batch-size")
      .text("Number of queries to be grouped at a single batch")
      .abbr("b")
      .validate(biggerThanZero("Batch size"))
      .foreach(c.config += BatchSize(_))

    opt[Int]("max-requests-per-endpoint")
      .text("The maximum number of concurrent requests per endpoint")
      .abbr("m")
      .validate(biggerThanZero("Threads per endpoint"))
      .foreach(c.config += MaxRequestsPerEndpoint(_))

    opt[String]("error-strategy")
      .text("The error strategy to be used")
      .valueName(s"<stop|continue>")
      .abbr("r")
      .validate(either("Error strategy", "stop", "continue"))
      .foreach {
        case "stop"     => c.config += OnError(ErrorStrategy.StopOnError)
        case "continue" => c.config += OnError(ErrorStrategy.DoNotStop)
      }

    opt[String]("report-type")
      .text("Progress report strategy to use")
      .valueName(s"<inline|detailed|silent>")
      .abbr("t")
      .validate(either("Report type", "inline", "detailed", "silent"))
      .foreach {
        case "inline"   => c.config += Report(ReportType.Inline)
        case "detailed" => c.config += Report(ReportType.Detailed)
        case "silent"   => c.config += Report(ReportType.Silent)
      }

    help("help").text("Display this text")

    cmd("import-file").children(
      arg[File]("file")
        .text("File to import. Supported formats: JSON, CSV, and TSV")
        .required()
        .validate(fileExists)
        .foreach(c.context.file(_)),

      opt[Seq[String]]("format")
        .text(
          "Fields' definition in the following format: " +
            "<field-name>[->new-name]:<field-type>[(config)],...")
        .abbr("f")
        .validate {
          _.foldLeft(success) { case (prev, field) =>
            prev.right flatMap { _ =>
              field.split(":").map(_.trim) match {
                case Array(name, definition) if name.split("->").forall(!_.trim.isEmpty) =>
                  Type.byDefinition(definition).fold(failure, _ => prev)

                case _ =>
                  failure(s"Invalid field ${field.trim}")
              }
            }
          }
        }
        .foreach {
          _ foreach { field =>
            val Array(name, definition) = field.split(":").map(_.trim)
            val parts = name.split("->").map(_.trim)
            if (parts.length == 2) c.context += Rename(parts(0), parts(1))
            c.context += Field(parts.head, Type.byDefinition(definition).get)
          }
        },

      opt[String]("class")
        .text("The class in which the data will be imported")
        .abbr("c")
        .required()
        .validate(notBlank("class"))
        .foreach(c.context += Clazz(_)),

      opt[Boolean]("skip-root")
        .abbr("k")
        .text(
          "Configures the parser to ignore the first line for TSV/CSV " +
            "files or to ignore first array element in JSON files"
        )
        .foreach(c.context += SkipRoot(_)),

      opt[Seq[String]]("ignore-fields")
        .text("Fields to be ignored when inserting data into FaunaDB")
        .abbr("i")
        .validate { fields =>
          if (fields.forall(_.trim.nonEmpty)) success
          else failure("ignore-fields can't have a blank field")
        }
        .foreach(_.foreach(c.context += Ignore(_))),

      opt[String]("ts-field")
        .text("Field to be used as a timestamp for each inserted instance at FaunaDB")
        .abbr("t")
        .validate(notBlank("ts-field"))
        .foreach(c.context += TSField(_))
    )

    cmd("import-schema").children(
      arg[File]("schema-file")
        .required()
        .text("YAML schema definition")
        .validate(fileExists)
        .validate(SchemaFile.parse(_).fold(failure, _ => success))
        .foreach(SchemaFile.parse(_, c.context).get)
    )

    override def renderingMode: RenderingMode = RenderingMode.OneColumn

    private def isValidUrl(str: String): Boolean = {
      try {
        new URL(str)
        true
      } catch {
        case _: Throwable => false
      }
    }

    private def fileExists(file: File): Either[String, Unit] = {
      if (file.exists()) success
      else failure(s"${file.getName} does NOT exist")
    }

    private def biggerThanZero(name: String)(n: Int): Either[String, Unit] = {
      if (n > 0) success
      else failure(s"$name must be bigger than 0")
    }

    private def either(name: String, options: String*)(value: String): Either[String, Unit] = {
      if (options.contains(value)) success
      else failure(
        s"$name should be either: ${options.take(options.length - 1).mkString(", ")}, " +
          s"or ${options.last}"
      )
    }

    private def notBlank(name: String)(str: String): Either[String, Unit] = {
      if (str.trim.nonEmpty) success
      else failure(s"$name can NOT be blank")
    }
  }
}

private final class CmdArgs(
  val config: ConfigBuilder = ConfigBuilder(),
  val context: ContextBuilder = ContextBuilder()
) {
  def result(): Option[(Config, Seq[(File, Context)])] = {
    config.result() flatMap { c =>
      context.result(c) map ((c, _))
    } fold (
      err => {
        println(err)
        None
      },
      res => Some(res)
    )
  }
}

private final class SchemaFile(
  @JsonProperty("class") val clazz: String,
  @JsonProperty("fields") val fields: util.LinkedList[FieldDef],
  @JsonProperty("ignoredFields") val ignoredFields: util.Set[String],
  @JsonProperty("tsField") val tsField: String,
  @JsonProperty("skipRoot") val skipRoot: Boolean
)

private final class FieldDef(
  @JsonProperty("name") val name: String,
  @JsonProperty("type") val tpe: String,
  @JsonProperty("rename") val rename: String
)

private object SchemaFile {
  type ResultMap = util.LinkedHashMap[String, SchemaFile]

  def parse(yamlFile: File, context: ContextBuilder = ContextBuilder()): Result[Unit] = {
    import ContextBuilder.Dsl._

    readSchemaDefinition(yamlFile) map {
      _ flatMapS { case (fileToImport, schema) =>
        toFile(fileToImport) map { file =>
          context.file(file)
          context += Clazz(schema.clazz)
          context += SkipRoot(schema.skipRoot)
          context += TSField(schema.tsField)
          schema.ignoredFields.foreach(context += Ignore(_))

          schema.fields.toSeq.flatMapS { fieldDef =>
            Type.byDefinition(fieldDef.tpe) map { tpe =>
              if (fieldDef.rename != null) context += Rename(fieldDef.name, fieldDef.rename)
              context += Field(fieldDef.name, tpe)
            }
          }
        }
      } map (_ => ())
    } getOrElse
      Err("Could not parse schema definition. " +
        "Check the schema syntax as well as if all files names are correct.")
  }

  private def readSchemaDefinition(file: File): Try[Seq[(String, SchemaFile)]] = Try {
    new ObjectMapper(new YAMLFactory())
      .readValue[ResultMap](file, new TypeReference[ResultMap] {})
      .toSeq
  }

  private def toFile(name: String): Result[File] = {
    val f = new File(name)
    if (f.exists()) Ok(f) else Err(s"File $name does NOT exist.")
  }
}
