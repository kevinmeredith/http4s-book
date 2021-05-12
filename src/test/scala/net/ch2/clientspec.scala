package net.ch2

import cats.effect.IO
import cats.implicits._
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import munit.CatsEffectSuite
import org.http4s._
import org.http4s.circe.jsonEncoder
import org.http4s.implicits._
import org.http4s.client.Client

final class clientspec extends CatsEffectSuite {

  // Build a stubbed implementation of Messages[IO] to help with testing
  private def stubbedAPIClient(responseStatus: Status, responsePayload: Json): Client[IO] =
    Client.fromHttpApp[IO](
      HttpApp.pure[IO](
        Response[IO](
          status = responseStatus
        ).withEntity[Json](responsePayload)
      )
    )

  private val TestUri: Uri = uri"www.not-used-as-client-is-stubbed.com"

  test("return List of messages for HTTP-200 w/ well-formed payload") {

    val timestamp: Instant = Instant.now()

    val singleMessage = Json.obj(
      "value"     := "hello world",
      "timestamp" := timestamp.toEpochMilli
    )

    val responsePayload = Json.arr(singleMessage)

    val testClient: Client[IO] = stubbedAPIClient(Status.Ok, responsePayload)

    val messagesImpl = Messages.impl[IO](
      testClient,
      TestUri
    )

    val actual: IO[List[Messages.Message]] =
      messagesImpl.getMessages("test-input-that-does-not-matter")

    val expected: List[Messages.Message] =
      List(
        Messages.Message(
          "hello world",
          timestamp
        )
      )

    actual.map { _actual: List[Messages.Message] =>
      assertEquals(_actual, expected)
    }
  }
}