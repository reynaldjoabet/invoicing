import Dependencies.*
// `config` and `testing` also come from sbt.*; a named import outranks a wildcard one.
import Dependencies.{config, testing}

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / version      := "0.1.0-SNAPSHOT"

ThisBuild / crossScalaVersions := Seq("3.3.8", "3.9.0")

ThisBuild / scalacOptions := Seq(
  "-encoding",
  "UTF-8",
  "-no-indent",
  "-deprecation",
  "-feature",
  "-unchecked",
  // "-Werror",
  // "-Wunused:all",
  "-Wvalue-discard",
  "-Wnonunit-statement",
  "-language:strictEquality",
  "-Xcheck-macros",
  "-Xmax-inlines:64"
)

ThisBuild / libraryDependencySchemes += "org.tpolecat" %% "skunk-core" % VersionScheme.Always

Global / onChangedBuildSource := ReloadOnSourceChanges

lazy val root = (project in file("."))
  .settings(
    name                 := "invoicing",
    libraryDependencies ++= Seq(
      core.catsEffect,
      core.fs2,
      domain.uuidGenerator,
      domain.iron.core,
      domain.iron.circe,
      domain.iron.skunk,
      domain.iron.pureconfig,
      http.http4s.circe,
      http.sttp.core,
      http.sttp.cats,
      http.sttp.circe,
      http.sttp.backends.http4s,
      json.circe.core,
      json.circe.generic,
      json.circe.parser,
      json.jsoniter.circe,
      logging.log4cats,
      logging.logback,
      config.pureconfig.core,
      config.pureconfig.generic,
      database.postgres,
      database.hikaricp,
      database.flyway,
      database.skunk,
      kafka.fs2,
      cache.caffeine,
      security.auth0,
      security.jwtScala.core,
      security.password4j,
      security.nimbus.joseJwt,
      security.nimbus.oauth2Oidc,
      security.bouncycastle.pkix,
      security.bouncycastle.provider,
      security.vault,
      observability.kamon.bundle,
      observability.kamon.prometheus,
      observability.datadog,
      testing.munit,
      testing.munitCatsEffect,
      testing.scalacheck
    ) ++ http.http4s.core
      ++ json.jsoniter.core
      ++ observability.otel4s
      ++ testing.hedgehog
  )
