# Chapter 2. Building an HTTP Client to Consume from an API

This chapter covers the following topics:

  - Brief Definition of `org.http4s.client.Client[F]`
  - Builing an HTTP Client Example
  - Testing Example
  - Tagless Final
  - Effect Types for Testing?

## Brief Definition of `org.http4s.client.Client[F]`

The `http4s`'s [docs](https://github.com/http4s/http4s/blob/v0.21.22/client/src/main/scala/org/http4s/client/Client.scala#L31) describe a
`Client`:

> A Client submits Requests to a server and processes the Response.

Let's look at the `http4s` source code of a `Client`:

```scala
trait Client[F[_]] {
  def run(req: Request[F]): Resource[F, Response[F]]
  ...
}
```

In short, given a `Request[F]`, `Client#run` will return a `Resource[F, Response[F]]`.

As the `cats-effect`'s [docs](https://typelevel.org/cats-effect/docs/2.x/datatypes/resource) note, `Resource`
"effectfully allocates and releases a resource." This means that, `cats.effect.Resource`, will properly dispose of any
resources for the `Response[F]`.

Let's look at an example to show how the `http4s` `Client[F]` API works.

## Builing an HTTP Client Example

```scala
```

## Testing Example

When I first used `http4s` professionally, I had come from building web applications using the [Play Framework](https://www.playframework.com/). The
simplicity of testing `http4s`'s `Client[F]`'s attracted me further to this HTTP library.

Let's look at an example. There's a single API: `GET /messages?topic={name}`. It returns an array of messages for a given topic.
A message has a "value," which is text, and "timestamp," which is the time at which the message occurred. Its data type is
Long since it's in milliseconds since the Epoch in Linux. Note that the `topic` query parameter is required.

Example:

```json
{
    "value"     : "Hello world",
    "timestamp" : 1619401781088
}
```

Before we dive into the `http4s` `Client[F]` example, let's discuss "Tagless Final" in Scala.

## Tagless Final

A well-known principle of Software Engineering is "program to an interface, not implementation." This point is important
since programming to an interface results in more maintainable and testable code. The "Tagless Final" approach follows
this principle.

In short, this approach's `interface` consists of using a Scala `trait` with a type parameter having a [kind](https://eed3si9n.com/herding-cats/Kinds.html)
 of `* -> *`.

Let's look at an example:

```scala
import cats._
import cats.effect.Sync
import cats.implicits._
import io.circe._
import org.http4s.client.{ Client, UnexpectedStatus }
import org.http4s._
import org.http4s.circe.CirceEntityDecoder.circeEntityDecoder
import java.time.Instant
import java.time.DateTimeException
import scala.util.control.NoStackTrace

// The `Messages` algebr  a includes a single method to get messages.
trait Messages[F[_]] {
  def getMessages(topicName: String): F[List[Messages.Message]]
}
// Next, let's look at the companion object. Its primary purpose is
// to provide an implementation of Messages[F].
object Messages {
  // Represents the model definition of a `Message`.
  final case class Message(value: String, timestamp: Instant)
  object Message {
    // Expose a 'from' method for attempting to build a Message
    // from a MessageDTO
    def from(m: MessageDTO): Either[DateTimeException, Message] =
      Either
        .catchOnly[DateTimeException] {
          Instant.ofEpochMilli(m.timestamp)
        }
        .map { ts: Instant =>
          new Message(m.value, ts)
        }

  }

  // DTO = Data Transfer Object
  // This 'private" case class represents the JSON payload. It's private
  // since its purpose is to make the conversion from JSON => Message, the true
  // model data type, easier.
  private final case class MessageDTO(value: String, timestamp: Long)
  private object MessageDTO {
    // Define a circe Decoder, a typeclass that provides a method
    // to go from, roughly speaking, JSON => A.
    implicit val decoder: Decoder[MessageDTO] = new Decoder[MessageDTO] {
      final def apply(c: HCursor): Decoder.Result[MessageDTO] =
        for {
          value         <- c.downField("value").as[String]
          timestampLong <- c.downField("timestamp").as[Long]
        } yield MessageDTO(value, timestampLong)
    }
  }

  // Define a custom Throwable that includes an "errorMessage" and underlying Throwable.
  // The motivation for this Throwable is to make it explicitly clear what failed when
  // attempting to get List[Message] via the HTTP API.
  // When an error occurs, it's critical that the Software Engineer reviewing error logs
  // know what failed and why. To the business, clear error reporting is a competitive
  // advantage since Software Engineers can more promptly address errors if their cause
  // is explicitly captured, such as "GetMessagesError."
  // In my own experience in production, a logger will be responsible for
  // logging the "errorMessage," as well as the stack trace of the "t" Throwable.
  // In addition, note that "GetMessagesError" mixes in scala.control.util.NoStackTrace.
  // It does that since the stack trace of "GetMessagesError" is not meaningful.
  // Finally, note that it's "private." If this error occurs, there will be no
  // way to meaningfully recover from it. Consequently, in the interest of minimizing
  // the surface area of this API, let's remove its accessibility to all but
  // to "object Messages."
  final case class GetMessagesError(errorMessage: String, t: Throwable)
    extends RuntimeException(errorMessage, t)
      with NoStackTrace

  // Define an implementation for Messages[F]
  // Note that there's a typeclass constraint of "F[_] : Sync." Read this as
  // the F, whose kind is * -> *, must have a cats.effect.Sync[F] instance in scope.
  // The purpose of the "c," Client[F], is to make an HTTP Request to the 3rd party API.
  // Finally, the "uri" type is needed as a URI is required for making an HTTP Request.
  def impl[F[_] : Sync](c: Client[F], uri: Uri): Messages[F] = new Messages[F] {
    def getMessages(topicName: String): F[List[Messages.Message]] = {
      // Create a URI with a path of /messages and an added query parameter
      val u: Uri = (uri / "messages").withQueryParam("topicName", topicName)

      // Build the HTTP Request w/ a Method of GET and URI = the constructed one
      val r: Request[F] = Request[F](method = Method.GET, uri = u)

      val messages: F[List[Message]] =
        c.run(r)                       // Call Client#run, which returns a
                                       // Resource[F, Response[F]]
          .use { resp: Response[F] =>  // Invoke the "use" method of Resource:
                                       //   Response[F] => F[List[Message]].
            resp match {               // Pattern match on the Response[F] to inspect,
                                       // in particular, its status.
              case Status.Ok(body) =>  // This pattern match applies to an HTTP-200
                                        // response.
                                       // The "body" variable
                                       // has a type of "Response[F]."
                // Attempt to decode the "body," whose type is "Response[F]," into
                // a "List[MessageDTO]".
                // Note that "org.http4s.Response" inherits this method
                // from "org.http4s.Media:"
                //  > final def as[A](
                //     implicit F: MonadThrow[F],
                //              decoder: EntityDecoder[F, A]
                //    ): F[A]
                // "MonadThrow[F]" is a type alias for "MonadError[F, Throwable]."
                // cats's docs explain MonadError:
                //  > allows you to raise and or handle an error value.
                // In this case, the "MonadError[F, Throwable]" allows for raising
                // or handling Throwable's.
                // http4s's code docs explain:
                //  > A type that can be used to decode a Message EntityDecoder
                //  > is used to attempt to decode a Message returning the
                //  > entire resulting A.
                // In this case, org.http4s.circe.CirceEntityDecoder.circeEntityDecoder
                // creates the "EntityDecoder[F, MessageDTO]." Its type signature
                //  is:
                //  > implicit def circeEntityDecoder[
                //     F[_]: Sync,
                //     A: Decoder
                //    ]: EntityDecoder[F, A]
                // So, that type signature states, given evidence of Sync[F]
                // and Decoder[A], produce an "EntityDecoder[F, A]." This code
                // requires a "Sync[F]" per Messages#impl's implicits.
                // Also, there's an implicit "Decoder[MessageDTO]"
                // defined in the "MessageDTO" object.
                val dtos: F[List[MessageDTO]] =
                  body.as[List[MessageDTO]]

                // At this point, the code has an "F[List[MessageDTO]]." It's then
                // necessary to convert each "MessageDTO" into a "Message," i.e.
                // the domain model representation of a message. Recall that "MessageDTO"
                // is just a private model that's used strictly for decoding JSON
                // into a type.
                val messages: F[List[Message]] =
                    dtos                                    // For converting
                                                            // F[List[MessageDTO]]
                                                            // to
                                                            //  F[List[Message]],
                                                            // we'll use
                                                            // cats.FlatMap[F]#flatMap.
                      .flatMap { _dtos: List[MessageDTO] => // List[MessageDTO] =>
                                                            //  F[List[Message]]
                        Traverse[List]                      // Summon an instance
                                                            // of cats.Traverse
                          .traverse[F, MessageDTO, Message](_dtos) {
                            dto: MessageDTO =>              // MessageDTO => F[Message]
                                val m: Either[DateTimeException, Message] =
                                  Message.from(dto)
                                Sync[F].fromEither(m)
                              }
                      }
                messages
              case non200Status => Sync[F].raiseError(
                GetMessagesError(
                  "non-200 response",
                  UnexpectedStatus(non200Status.status)
                )
              )
            }
          }

      // There are multiple failures that could occur when making an HTTP
      //  Request with org.http4s.client.Client:
      // (1) received non HTTP-200 response
      // (2) received HTTP-200 but failed to decode the HTTP Response's payload
      //     as JSON
      // (3) received HTTP-200 but failed to decode the HTTP Response's JSON
      //     payload as a 'MessageDTO'
      // (4) received HTTP-200 but at least one of the MessageDTO's includes
      //     an invalid 'Long' timestamp
      // (5) client times out, i.e. clients halts/fails due to an exceeded
      //     timer waiting for a response from the client
      // So far this code only handles error case (1) by wrapping it
      //   in a GetMessagesError.
      // When reviewing error messages in the logs, it's valuable to
      //  know exactly why an error occurred. In this case, it's important
      //  to know that as 'GetMessagesError' occurred, including the
      //  message and underlying stack trace.
      // As a result, let's use MonadError[F, Throwable]#adaptError:
      // > override def adaptError[A](fa: F[A])(pf: PartialFunction[E, E]): F[A]
      // In this code, the error "E" type is Throwable, and
      // the "A" is "List[Message]." Note that "adaptError" is an extension
      // method from cats.syntax.MonadErrorOps.
      messages
        .adaptError {
          case e @ GetMessagesError(_, _) =>
            // If a GetMessagesError's already been raised, keep it as the error type.
            e
          case other                      =>
            // If we did return an existing raised "GetMessagesError," we
            // would've produced a redundant
            // GetMessagesError("unexpected error", GetMessagesError(..., ...))
            GetMessagesError("unexpected error", other)
        }
    }
  }
}
```

Finally, let's write a test for this interface's implementation.

```scala
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
      // Finally, let's pass an HttpApp[IO] that returns an HTTP Response
      // with the given input Status and JSON payload.
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
  test("raise an error for a malformed payload " +
    "('value' is not a String and 'timestamp' is not " +
    "valid either") {
    // Messages[F]#impl expects the HTTP Response's 'content' to be a
    //  String, not an Int
    val invalidMessageValue: Int = 1234
    // Messages[F]#impl expects the HTTP Response's 'timestamp' to
    //  be a Long Epoch Milli, not a String
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
```

Lastly, let's run the tests with `sbt:test`:

```scala
sbt:http4s-book> testOnly net.ch2.clientspec
[info] compiling 1 Scala source to /Users/kevinmeredith/Workspace/http4s-book/target/scala-2.12/test-classes ...
net.ch2.clientspec:
  + return List of messages for HTTP-200 Response w/ well-formed payload 0.992s
  + raise an error for a malformed payload ('value' is not a String and 'timestamp' is not valid either 0.017s
[info] Passed: Total 2, Failed 0, Errors 0, Passed 2
[success] Total time: 2 s, completed Jun 19, 2021 10:44:09 PM
```

## Effect Types for Testing?

One argument of the Tagless Final approach, which provides a polymorphic `F[_]` for the effect type, is that it enables
using a type other than `cats.effect.IO` for testing. Although this is true, in my 4 years of professional
experience building web services in production, I've 99% of the time used `cats.effect.IO`.

It's a natural choice since that's what will be used in the real-world instance of the application. An additional
argument for using `cats.effect.IO` as the effect type is https://github.com/typelevel/munit-cats-effect. That library
enables building tests that compare values of type `IO[A]`.