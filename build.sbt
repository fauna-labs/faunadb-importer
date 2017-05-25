lazy val testAll = TaskKey[Unit]("test-all")
lazy val E2ETest = config("e2e") extend Test

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .configs(E2ETest)
  .settings(
    name := "faunadb-importer",
    version := "1.0-SNAPSHOT",
    scalaVersion := "2.11.8",
    scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature", "-Xfatal-warnings"),
    javaOptions ++= Seq("-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200", "-XX:G1HeapRegionSize=4m", "-server"),
    fork := true,

    // TODO: Check licences
    libraryDependencies ++= Seq(
      // Main
      "com.faunadb" %% "faunadb-scala" % "1.1.0",
      "com.typesafe.akka" %% "akka-actor" % "2.5.0",
      "com.typesafe.akka" %% "akka-stream" % "2.5.0",
      "com.github.scopt" %% "scopt" % "3.5.0",
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "com.fasterxml.jackson.core" % "jackson-core" % "2.8.6",
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.6.4",
      "com.fasterxml.jackson.dataformat" % "jackson-dataformat-csv" % "2.6.4",
      "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % "2.6.4",

      // Test
      "org.scalatest" %% "scalatest" % "3.0.1" % "test"
    ),

    // E2E Testing
    inConfig(E2ETest)(Defaults.testSettings) ++ Seq(
      scalaSource := baseDirectory.value / "src/e2e/scala",
      resourceDirectory in Test := baseDirectory.value / "src/e2e/resources/"
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
    bashScriptExtraDefines += s"addJava ${javaOptions.value mkString " "}",
    batScriptExtraDefines += s"set _JAVA_OPTS=%_JAVA_OPTS% ${javaOptions.value filter (_ != "-server") mkString " "}" // -server is not supported on Win32
  )

