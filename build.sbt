import Dependencies.*

ThisBuild / scalaVersion      := "3.3.8"
ThisBuild / organization      := "io.invoicing"
ThisBuild / version           := "0.1.0"
ThisBuild / semanticdbEnabled := true

ThisBuild / scalacOptions := Seq(
  "-encoding",
  "UTF-8",
  "-no-indent",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-source:3.3",
  "-java-output-version:17",
  "-Werror",
  "-Wvalue-discard",
  "-Wnonunit-statement",
  "-Xlint:all",
  "-Xcheck-macros",
  "-Xmax-inlines:64"
)

// iron-skunk 3.3.2 is published against skunk-core 1.0.0-M12, so 2.x trips the early-semver
// eviction check. Its whole skunk surface is Codec#eimap, whose signature is unchanged in
// 2.0.0-RC2 -- the 2.0 major is the otel4s 0.16 -> 1.x upgrade, which iron-skunk never touches.
ThisBuild / libraryDependencySchemes += "org.tpolecat" %% "skunk-core" % VersionScheme.Always

Global / onChangedBuildSource := ReloadOnSourceChanges

lazy val root = (project in file("."))
  .settings(
    name                 := "invoicing",
    libraryDependencies ++= all
  )
