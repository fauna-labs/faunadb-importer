package faunadb.importer.lang

import faunadb.specs.SimpleSpec

class ResultSpec extends SimpleSpec {

  "A success" should "be a success" in {
    Ok(1).isSuccess shouldBe true
    Ok(1).isFailure shouldBe false
  }

  it should "map" in {
    Ok(1).map(_ + 1) shouldBe Ok(2)
  }

  it should "flat map" in {
    Ok(1).flatMap(n => Ok(n + 1)) shouldBe Ok(2)
  }

  it should "fold" in {
    Ok(1).fold(_ => 2, _ => 4) shouldBe 4
  }

  "A error" should "be a failure" in {
    Err("an error").isFailure shouldBe true
    Err("an error").isSuccess shouldBe false
  }

  it should "map" in {
    Err[String]("an error").map(_ + 1) shouldBe Err("an error")
  }

  it should "flat map" in {
    Err[String]("an error").flatMap(n => Ok(n + 1)) shouldBe Err("an error")
  }

  it should "fold" in {
    Err[String]("an error").fold(_ => 2, _ => 4) shouldBe 2
  }

  def isEven(i: Int): Result[Int] = if (i % 2 == 0) Err("not even") else Ok(i)

  "The short circuit" should "stop when find an Err" in {
    var runs = 0
    def next(i: Int): Result[Int] = {
      runs += 1
      isEven(i)
    }

    Stream.from(1).take(10).flatMapS(next) shouldBe Err("not even")
    runs shouldBe 2
  }

  it should "flatMap stream" in {
    Stream(1, 2, 3).flatMapS(Ok(_)) shouldBe Ok(Stream(1, 2, 3))
    Stream(1, 2, 3).flatMapS(isEven) shouldBe Err("not even")
  }

  it should "foldLeft a stream" in {
    Stream(1, 2, 3).foldLeftS(10)((acc, n) => Ok(acc + n)) shouldBe Ok(16)
    Stream(1, 2, 3).foldLeftS(10)((_, n) => isEven(n)) shouldBe Err("not even")
  }

  it should "flatMap map" in {
    Map("name" -> "bob").flatMapS(Ok(_)) shouldBe Ok(Map("name" -> "bob"))
    Map("name" -> "bob").flatMapS(_ => Err("an error")) shouldBe Err("an error")
  }

}
