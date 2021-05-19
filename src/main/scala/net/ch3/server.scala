package net.ch3

import cats.effect.Sync
import cats.implicits._
import io.circe.Decoder.Result
import io.circe.{Decoder, DecodingFailure, Encoder, HCursor, Json}
import io.circe.syntax._
import org.http4s._
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.dsl.Http4sDsl

import java.time.{Instant, ZoneId, ZoneOffset}
import java.time.format.{DateTimeFormatter, DateTimeParseException}
import java.util.UUID
import net.ch3.models.{AuthToken, UserId}
import net.ch3.server.Messages

import scala.util.Try

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

      implicit val decoder: Decoder[Message] = new Decoder[Message] {
        override def apply(c: HCursor): Result[Message] =
          for {
            content <- c.downField("content").as[String]
            timestampStr <- c.downField("timestamp").as[String]
            timestamp = Either.catchOnly[DateTimeParseException](format.parse(timestampStr))
            ts <- timestamp.leftMap { t: Throwable =>
              DecodingFailure(t.getMessage, c.history)
            }
          } yield Message(content, Instant.from(ts))
      }

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
    def get(userId: UserId): F[List[Messages.Message]]
    def create(userId: UserId, message: Messages.Message): F[Unit]
  }
  object UserId {
    def unapply(str: String): Option[UserId] =
      Try(new UserId(UUID.fromString(str))).toOption
  }

  sealed abstract class ApiError() extends RuntimeException
  object ApiError {
    case object MissingAuthorizationHeader extends ApiError
    final case class InvalidAuthorizationType(authScheme: AuthScheme) extends ApiError
    case object IncorrectAuthToken extends ApiError
  }

  def routes[F[_] : Http4sDsl : Messages : Sync](trustedAuthToken: AuthToken): HttpRoutes[F] = {
    val dsl: Http4sDsl[F] = implicitly[Http4sDsl[F]]

    import dsl._

    def authorizationCheck(headers: Headers): F[AuthToken] =
      for {
        authorization <- Sync[F].fromOption(
          headers.get(org.http4s.headers.Authorization),
          ApiError.MissingAuthorizationHeader
        )
        creds = authorization.credentials
        _ <- Sync[F].delay(println(creds))
        authToken <- {
          creds match {
            case Credentials.AuthParams(scheme, _) => Sync[F].raiseError(ApiError.InvalidAuthorizationType(scheme))
            case Credentials.Token(_, str) => Sync[F].pure(AuthToken(str))
          }
        }
      } yield authToken


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
      case req @ POST -> Root / UserId(userId) / "messages"  =>
        for {
          authToken <- authorizationCheck(req.headers)
          _ <- {
            if (authToken === trustedAuthToken) Sync[F].unit
            else Sync[F].raiseError(ApiError.IncorrectAuthToken)
          }
          createRequest <- req.as[]
        } yield Response[F](status = Status.Ok)


    }
  }

}