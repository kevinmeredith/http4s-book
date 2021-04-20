scalaVersion := "2.12.12"

lazy val http4sVersion = "0.21.21"

libraryDependencies ++= Seq(
  "org.http4s"   %% "http4s-core"      % http4sVersion,
  "org.http4s"   %% "http4s-dsl"       % http4sVersion,
  "org.http4s"   %% "http4s-server"    % http4sVersion
)

scalacOptions ++= Seq("-Ypartial-unification")
