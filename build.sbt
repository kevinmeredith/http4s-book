scalaVersion := "2.12.12"

lazy val http4sVersion = "0.21.21"
lazy val scalaMetaVersion = "0.7.25"

libraryDependencies ++= Seq(
  "org.http4s"   %% "http4s-core"      % http4sVersion,
  "org.http4s"   %% "http4s-client"    % http4sVersion,
  "org.http4s"   %% "http4s-circe"     % http4sVersion,
  "org.http4s"   %% "http4s-dsl"       % http4sVersion,
  "org.http4s"   %% "http4s-server"    % http4sVersion,
  "org.scalameta" %% "munit"           % scalaMetaVersion  % Test
)

scalacOptions ++= Seq("-Ypartial-unification")

