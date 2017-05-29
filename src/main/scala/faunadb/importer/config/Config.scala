package faunadb.importer.config

import faunadb.importer.errors._
import faunadb.importer.lang._
import faunadb.importer.report._

case class Config(
  // Fauna secret used to authenticate requests
  secret: String,

  // Fauna endpoints to send queries to.
  // If no endpoint, defaults to could.
  endpoints: Seq[String] = Seq.empty,

  // Number of queries to be executed at a single batch
  batchSize: Int = 50,

  // Number of threads used to run queries per endpoint
  threadsPerEndpoint: Int = 4,

  // Error handling strategy to be used
  errorStrategy: ErrorStrategy = ErrorStrategy.StopOnError,

  // Progress report strategy to be used
  reportType: ReportType = ReportType.Inline
)

final class ConfigBuilder {
  import ConfigBuilder._

  private val steps = IndexedSeq.newBuilder[BuildStep]

  def +=(step: BuildStep): ConfigBuilder = {
    steps += step
    this
  }

  def result(): Result[Config] = {
    steps.result().foldLeft(Config(""))((c, s) => s.configure(c)) match {
      case c if c.secret.isEmpty => Err("No key's secret specified")
      case c                     => Ok(c)
    }
  }
}

object ConfigBuilder {
  def apply(): ConfigBuilder = new ConfigBuilder()

  sealed trait BuildStep {
    def configure(c: Config): Config
  }

  final object Dsl {
    final case class Secret(value: String) extends BuildStep {
      def configure(c: Config): Config = c.copy(secret = value)
    }

    final case class Endpoints(value: Seq[String]) extends BuildStep {
      def configure(c: Config): Config = c.copy(endpoints = value)
    }

    final case class BatchSize(value: Int) extends BuildStep {
      def configure(c: Config): Config = c.copy(batchSize = value)
    }

    final case class ThreadsPerEndpoint(value: Int) extends BuildStep {
      def configure(c: Config): Config = c.copy(threadsPerEndpoint = value)
    }

    final case class OnError(value: ErrorStrategy) extends BuildStep {
      def configure(c: Config): Config = c.copy(errorStrategy = value)
    }

    final case class Report(value: ReportType) extends BuildStep {
      def configure(c: Config): Config = c.copy(reportType = value)
    }
  }
}
