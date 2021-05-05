package net.ch2

import cats.effect.IO
import cats.implicits._
import io.circe.Json
import io.circe.syntax._

import java.time.{Clock, Instant}
import munit._
import org.http4s._
import org.http4s.circe.jsonEncoder
import org.http4s.implicits._
import org.http4s.client.Client

object clientspec extends FunSuite {

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

    val timestamp: Instant = ???

    val result: IO[List[Messages.Message]] =
      for {
        singleMessage = Json.obj(
          "value"     := "hello world",
          "timestamp" := timestamp.toEpochMilli
        )
        responsePayload = Json.arr(singleMessage)
        testClient = stubbedAPIClient(Status.Ok, responsePayload)
        messagesImpl = Messages.impl[IO](
          testClient,
          TestUri
        )
        messages <- messagesImpl.getMessages("does-not-matter")
      } yield messages

    val expected: List[Messages.Message] =
      List.M

    assertEquals(obtained, expected)
  }
  }
}