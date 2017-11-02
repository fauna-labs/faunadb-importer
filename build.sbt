lazy val testAll = TaskKey[Unit]("test-all")
lazy val E2ETest = config("e2e") extend Test

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .configs(E2ETest)
  .settings(
    name := "faunadb-importer",
    version := "1.0.1-SNAPSHOT",
    scalaVersion := "2.12.2",
    scalacOptions ++= Seq(
      "-unchecked",
      "-deprecation",
      "-feature",
      "-Xfatal-warnings"
    ),
    javaOptions ++= Seq(
      "-server",
      "-XX:+UseCompressedOops",
      "-XX:+UseStringDeduplication"
    ),
    fork := true,

    libraryDependencies ++= Seq(
      // Main
      "com.faunadb" %% "faunadb-scala" % "1.2.0",
      "io.monix" %% "monix" % "2.3.0",
      "com.github.scopt" %% "scopt" % "3.5.0",
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "com.fasterxml.jackson.core" % "jackson-core" % "2.8.8",
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.8.8",
      "com.fasterxml.jackson.dataformat" % "jackson-dataformat-csv" % "2.8.8",
      "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % "2.8.8",
      "org.mapdb" % "mapdb" % "3.0.4",

      // Test
      "org.scalatest" %% "scalatest" % "3.0.1" % "test",
      "org.scalamock" %% "scalamock-scalatest-support" % "3.5.0" % "test"
    ),

    // E2E Testing
    inConfig(E2ETest)(Defaults.testSettings) ++ Seq(
      scalaSource in E2ETest := baseDirectory.value / "src/e2e/scala",
      resourceDirectory in E2ETest := baseDirectory.value / "src/e2e/resources/"
    ),

    testAll := (test in E2ETest).dependsOn(test in Test).value,

    // Assembly
    assemblyJarName in assembly := s"faunadb-importer-${version.value}.jar",
    test in assembly := Some(testAll.value),

    // Packaging
    mappings in Universal := {
      val fatJar = assembly.value
      val baseDir = (baseDirectory in Compile).value
      val allNonJars = (mappings in Universal).value filter { case (_, name) => !name.endsWith(".jar") }

      allNonJars ++ Seq(
        (baseDir / "README.md") -> "README.md",
        fatJar -> s"lib/${fatJar.getName}"
      )
    },

    scriptClasspath := Seq((assemblyJarName in assembly).value),
    bashScriptExtraDefines ++= javaOptions.value map (opt => s"""addJava "$opt""""),
    batScriptExtraDefines ++= javaOptions.value filter (_ != "server") map (opt => s"set _JAVA_OPTS=%_JAVA_OPTS% $opt") // -server is not supported on Win32
  )

