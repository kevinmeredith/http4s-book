package net.ch2

import cats.effect.IO
import cats.implicits._
import io.circe.Json
import io.circe.syntax._

import java.time.Instant
import munit.CatsEffectSuite
import net.ch2.Messages.GetMessagesError
import org.http4s._
import org.http4s.circe.jsonEncoder
import org.http4s.implicits._
import org.http4s.client.Client

final class clientspec extends CatsEffectSuite {

  // Build a fake or stub 
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
    val timestamp: Instant = Instant.EPOCH

    val messageValue: String = "hello world"

    val singleMessage = Json.obj(
      "value"     := messageValue,
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
          messageValue,
          timestamp
        )
      )

    actual.map { _actual: List[Messages.Message] =>
      assertEquals(_actual, expected)
    }
  }
  test("raise an error for a malformed payload") {
    val invalidMessageValue: Int = 1234
    val invalidTimestamp: String = "oops-not-an-epoch-milli"

    val invalidSingleMessage = Json.obj(
      "value"     := invalidMessageValue,
      "timestamp" := invalidTimestamp
    )

    val responsePayload = Json.arr(invalidSingleMessage)

    val testClient: Client[IO] = stubbedAPIClient(Status.Ok, responsePayload)

    val messagesImpl = Messages.impl[IO](
      testClient,
      TestUri
    )

    val actual: IO[List[Messages.Message]] =
      messagesImpl.getMessages("test-input-that-does-not-matter")

    actual.attempt.map {
      case Right(x)                     => fail(s"Expected Throwable, but got Right($x)")
      case Left(GetMessagesError(_, _)) => assert(true)
      case Left(e)                      => fail(s"Expected Throwable of type GetMessagesError, but got $e")
    }
  }
}