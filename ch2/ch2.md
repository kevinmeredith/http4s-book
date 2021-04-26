# Chapter 2. Building an HTTP Client to Consume from an API

## Definition

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

## Example

## Testing

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
since programming to an interface results in more maintainable and testable. The "Tagless Final" approach has the same aim.

In short, this approach's `interface` consists of using a Scala `trait` with a type parameter having a [kind](https://eed3si9n.com/herding-cats/Kinds.html)
 of `* -> *`.

Let's look at an example:

```scala
import org.http4s.client.Client
import org.http4s.Uri
import java.time.Instant
import java.util.UUID

trait Notifications[F[_]] {
    def notify(userId: UUID): F[Unit]
    def getNotifications(userId: UUID): F[List[Notifications.Notification]]
}

object Notifications {
    final case class Notification(message: String, timestamp: Instant)

    // We need an HTTP Client, as well as a URI, for getting notifications.
    // In short, for this implementation of `Notifications[F[_]]`, we need
    // an HTTP Client for accessing an API.
    def impl(c: Client[IO], notificationsApiUri: Uri): Notifications[IO] = new Notifications[IO] {
        override def notify(userId: UUID): IO[Unit] = ???
        def getNotifications(userId: UUID): IO[List[Notification]] = ???
    }
}
```

So, the `trait` represents the interface, whereas `Notifications#impl` defines an interpretation of it.is

Alternatively, in my professional experience using this approach in production, I've heard the `trait` referred to as the
"algebra," and the implementation is the "interpreter."

Let's return to the `GET /messages?topic={name}` API for which we're going to build an `http4s`'s Client.

```scala
import cats._
import cats.effect.IO
import cats.implicits._
import io.circe._
import org.http4s.client.{ Client, UnexpectedStatus }
import org.http4s._
import org.http4s.circe._
import java.time.Instant
import java.time.DateTimeException
import java.util.UUID
import scala.util.control.NoStackTrace

trait Messages[F[_]] {
    def getMessages(topicName: String): F[List[Messages.Message]]
};object Messages {
    final case class Message(value: String, timestamp: Instant)
    object Message {
        implicit val decoder: Decoder[Message] = new Decoder[Message] {
          final def apply(c: HCursor): Decoder.Result[Message] =
            for {
              value <- c.downField("value").as[String]
              timestampLong <- c.downField("timestamp").as[Long]
              timestamp <- Either.catchOnly[DateTimeException](Instant.ofEpochMilli(timestampLong)).leftMap { t: Throwable =>
                Left(???)
              }
            } yield {
               Message(value, timestamp)
            }
        }
    }

    private final case class GetMessagesError(msg: String, t: Throwable)
      extends RuntimeException(msg, t)
      with NoStackTrace

    def impl(c: Client[IO], uri: Uri): Messages[IO] = new Messages[IO] {
        def getMessages(topicName: String): IO[List[Messages.Message]] = {
            val u: Uri = (uri / "messages").withQueryParam("topicName", topicName)

            val r: Request[IO] = Request[IO](method = Method.GET, uri = u)

            val messages: IO[List[Message]] =
                c.run(r)
                 .use { resp: Response[IO] =>
                    resp match {
                        case Status.Ok(body) => body.as[List[Message]]
                        case non200Status    => IO.raiseError(new GetMessagesError("non-200 response", UnexpectedStatus(non200Status)))
                    }
                }

            messages.adaptError {
                case e @ GetMessagesError(_, _) => e
                case other                      => new GetMessagesError("unexpected error", other)
            }
        }
    }
}
```