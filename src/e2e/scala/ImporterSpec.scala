import faunadb._
import faunadb.importer._
import faunadb.importer.process._
import faunadb.query._
import faunadb.specs._
import faunadb.values._
import org.scalatest._

class ImporterSpec
  extends FlatSpec
    with Matchers
    with ConcurrentUtils
    with BeforeAndAfterAll {

  var secret: String = _
  val dbName = "import-test"
  val adminClient = FaunaClient("secret", "http://localhost:8443")

  override protected def beforeAll(): Unit = {
    await(adminClient.query(Delete(Database(dbName))) recover { case _ => () })
    await(adminClient.query(CreateDatabase(Obj("name" -> dbName))))

    val key = await(adminClient.query(CreateKey(Obj(
      "database" -> Database(dbName),
      "role" -> "server"
    ))))

    secret = key("secret").to[String].get

    adminClient.sessionWith(secret) { cli =>
      await(cli.query(Do(
        CreateClass(Obj("name" -> "users")),
        CreateClass(Obj("name" -> "tweets"))
      )))

      await(cli.query(
        CreateIndex(Obj(
          "name" -> "sorted_tweets",
          "source" -> Class("tweets"),
          "values" -> Arr(
            Obj("field" -> Arr("ts")),
            Obj("field" -> Arr("ref"))
          )
        ))
      ))
    }
  }

  override protected def afterAll(): Unit =
    await(
      adminClient
        .query(Delete(Database(dbName)))
        .recover { case _ => () }
    )

  "The import tool" should "import a schema" in {
    Import.IdCacheFile.delete()

    Main.main(Array(
      "import-schema",
      "--secret", secret,
      "--endpoints", "http://localhost:8443",
      "src/e2e/resources/schema.yaml"
    ))

    adminClient.sessionWith(secret) { cli =>
      await(cli.query(
        Map(
          Paginate(Match(Index("sorted_tweets"))),
          Lambda { (_, ref) =>
            Let {
              val tweet = Get(ref)
              Let {
                val user = Get(Select("data" / "user", tweet))
                Obj(
                  "user" -> Select("data" / "name", user),
                  "user_since" -> Epoch(Select("ts", user), TimeUnit.Microsecond),
                  "tweet" -> Select("data" / "message", tweet),
                  "ts" -> Epoch(Select("ts", tweet), TimeUnit.Microsecond)
                )
              }
            }
          }
        )
      )) shouldBe ObjectV("data" -> ArrayV(
        ObjectV(
          "user" -> StringV("Marry the owl"),
          "user_since" -> TimeV("2016-12-24T23:58:25.000000000Z"),
          "tweet" -> StringV("Hellow"),
          "ts" -> TimeV("2016-12-25T00:01:36.000000000Z")
        ),
        ObjectV(
          "user" -> StringV("Bob the cat"),
          "user_since" -> TimeV("2017-01-14T12:20:10.000000000Z"),
          "tweet" -> StringV("World"),
          "ts" -> TimeV("2017-01-14T12:24:36.000000000Z")
        )
      ))
    }
  }
}
