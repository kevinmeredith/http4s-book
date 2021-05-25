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

// The purpose of this spec is to write a unit test for the
// net.ch2.Messages[F] interface.
// Note that this class extends munit.CatsEffectSuite.
// It's a Typelevel library for testing results of type cats.effect.IO[A].
// See https://github.com/typelevel/munit-cats-effect for more details.
final class clientspec extends CatsEffectSuite {

  // Build a fake or stub org.http4s.client.Client[IO].
  // The function signature, (org.http4s.Status, io.circe.Json) => Client[IO], suggests
  // that the returned Client will, for any HTTP Request, produce an
  // HTTP Response with the input Response Status and Payload (JSON).
  // Why are we building this Client[IO]? It's necessary to supply one to
  // Messages.impl in order to build a Messages[IO].
  private def stubbedAPIClient(responseStatus: Status, responsePayload: Json): Client[IO] = {
    // http4s provides a handy function on Client, fromHtppApp. Its function signature is:
    //   def fromHttpApp[F[_]](app: HttpApp[F])(implicit F: Sync[F]): Client[F] =
    // The source code notes:
    // > Useful for generating pre-determined responses for requests in testing.
    // This clearly applies to this test case.
    Client.fromHttpApp[IO](
      // Finally, let's pass an HttpApp[IO] that returns an HTTP Response with the given input
      // Status and JSON payload.
      HttpApp.pure[IO](
        Response[IO](
          status = responseStatus
        ).withEntity[Json](responsePayload)
      )
    )
  }

  // Messages.impl requires a URI, namely the URI that will be supplied
  // to the other parameter, the Client[IO], to make an HTTP Request.
  // Since we'll be passing a fake/stub Client[IO], this input Uri is meaningless
  // and won't be used. As a result, let's build one as so.
  private val TestUri: Uri = uri"www.not-used-as-client-is-stubbed.com"

  // This test verifies that Messages[IO].getMessages successfully decodes
  // the stubbed HTTP Response from the 'stubbedAPIClient' call.
  test("return List of messages for HTTP-200 Response w/ well-formed payload") {
    // Define test data
    val timestamp: Instant = Instant.EPOCH

    val messageValue: String = "hello world"

    val singleMessage: Json = Json.obj(
      "value"     := messageValue,
      "timestamp" := timestamp.toEpochMilli
    )

    // Build a JSON payload
    val responsePayload: Json = Json.arr(singleMessage)

    // Make an HTTP Client that, for any HTTP Request, responds with an HTTP-200 Status,
    // as well as the supplied JSON payload.
    val testClient: Client[IO] = stubbedAPIClient(Status.Ok, responsePayload)

    // Create an implementation of the Messages[IO] interface by providing the 'testClient'.
    val messagesImpl: Messages[IO] = Messages.impl[IO](
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

    // Test that the actual list of returned List[Message.Messages]
    // matches what's expected, i.e. what was put into the HTTP Client stub.
    actual.map { _actual: List[Messages.Message] =>
      assertEquals(_actual, expected)
    }
  }

  // This test verifies that Messages[IO].getMessages raises an error due to
  // it failing to decode the stubbed HTTP Response.
  test("raise an error for a malformed payload ('value' is not a String and 'timestamp' is not valid either") {
    // Messages[F]#impl expects the HTTP Response's 'content' to be a String, not an Int
    val invalidMessageValue: Int = 1234
    // Messages[F]#impl expects the HTTP Response's 'timestamp' to be a Long Epoch Milli, not a String
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