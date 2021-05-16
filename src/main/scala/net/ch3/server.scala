package net.ch3

import cats.{ApplicativeError, Defer}
import cats.implicits._
import io.circe.{Encoder, Json}
import io.circe.syntax._
import org.http4s._
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.dsl.Http4sDsl

import java.time.{Instant, ZoneId, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatter.ISO_ZONED_DATE_TIME
import java.util.UUID

object server {

  /*
  * GET /{userId}/messages  - return all messages for a given User.
    * No authorization requirements
    * Responses:
        * HTTP-200 Retrieved all of the user's message - { "content" : String, "timestamp" : String ISO861 }
        * HTTP-500 Something went wrong on server
* POST /{userId}/messages - create a message for a given User
    * Must include Authorization header whose value identifies the User
    * Responses:
        * HTTP-200 Created the message
        * HTTP-401 Unauthorized (missing or invalid Authorization Header)
        * HTTP-403 Forbidden - Authorization user != path's user
        * HTTP-500 Something went wrong on server
   */

  object Messages {
    final case class Message(content: String, timestamp: Instant)
    object Message {

      // https://stackoverflow.com/a/27483371
      private val format: DateTimeFormatter =
        DateTimeFormatter
          .ISO_LOCAL_DATE_TIME
          .withZone(
            ZoneId.from(ZoneOffset.UTC)
          )

      implicit val encoder: Encoder[Message] = new Encoder[Message] {
        override def apply(a: Message): Json =
          Json.obj(
            "content"   := a.content,
            "timestamp" := format.format(a.timestamp)
          )
      }
    }
    def apply[F[_]](implicit ev: Messages[F]): Messages[F] = ev
  }
  trait Messages[F[_]] {
    def get(userId: UUID): F[List[Messages.Message]]
  }
  final case class UserId(value: UUID)

  def routes[F[_] : Http4sDsl : Messages : Defer : ApplicativeError[*, Throwable]]: HttpRoutes[F] = {
    val dsl: Http4sDsl[F] = implicitly[Http4sDsl[F]]

    import dsl._

    HttpRoutes.of[F] {
      case GET -> Root / UserId(userId) / "messages"  =>
        val messages: F[List[Messages.Message]] =
          Messages[F].get(userId)
        messages.map { _messages: List[Messages.Message] =>
          Response[F](status = Status.Ok)
            .withEntity[List[Messages.Message]](
              _messages
            )
        }

    }
  }

}