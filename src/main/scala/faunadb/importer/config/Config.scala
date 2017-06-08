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

  // Maximum number of concurrent requests per endpoint
  maxRequestsPerEndpoint: Int = 4,

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
    steps.result().foldLeft(Config(""))((c, fn) => fn(c)) match {
      case c if c.secret.isEmpty => Err("No key's secret specified")
      case c                     => Ok(c)
    }
  }
}

object ConfigBuilder {
  type BuildStep = (Config => Config)

  def apply(): ConfigBuilder =
    new ConfigBuilder()

  final object Dsl {
    def Secret(value: String): BuildStep = _.copy(secret = value)
    def Endpoints(value: Seq[String]): BuildStep = _.copy(endpoints = value)
    def BatchSize(value: Int): BuildStep = _.copy(batchSize = value)
    def OnError(value: ErrorStrategy): BuildStep = _.copy(errorStrategy = value)
    def Report(value: ReportType): BuildStep = _.copy(reportType = value)
    def MaxRequestsPerEndpoint(value: Int): BuildStep = _.copy(maxRequestsPerEndpoint = value)
  }
}
