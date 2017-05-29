package faunadb.importer.persistence

import faunadb.specs._
import java.io.File

class StateSpec extends SimpleSpec with FileUtils {

  "State" should "save and load from file" in withTempFile("State_file") { file =>
    State.store(file, "some value")
    State.load(file) shouldBe Some("some value")
  }

  it should "return None if file does not exist" in {
    State.load(new File("I don't exist")) shouldBe None
  }

  it should "create directories before saving a file" in withTempDirectory("State_directory") { dir =>
    val file = new File(dir, "cache/ids")
    State.store(file, "some value")
    State.load(file) shouldBe Some("some value")
  }

  it should "truncate file if it already exist" in withTempFile("State_truncated") { file =>
    State.store(file, "some value")
    State.store(file, "some other value")
    State.load(file) shouldBe Some("some other value")
  }

}
