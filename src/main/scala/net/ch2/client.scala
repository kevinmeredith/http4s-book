package net.ch2

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

// The `Messages` algebra includes a single method to get messages.
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
    // Define a Decoder, i.e. a typeclass that provides a method
    // to go from JSON => A.
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
  private final case class GetMessagesError(errorMessage: String, t: Throwable)
    extends RuntimeException(errorMessage, t)
      with NoStackTrace

  def impl[F[_] : Sync](c: Client[F], uri: Uri): Messages[F] = new Messages[F] {
    def getMessages(topicName: String): F[List[Messages.Message]] = {
      val u: Uri = (uri / "messages").withQueryParam("topicName", topicName)

      val r: Request[F] = Request[F](method = Method.GET, uri = u)

      val messages: F[List[Message]] =
        c.run(r)
          .use { resp: Response[F] =>
            resp match {
              case Status.Ok(body) =>
                val dtos: F[List[MessageDTO]] =
                  body.as[List[MessageDTO]]
                dtos
                  .flatMap { _dtos: List[MessageDTO] =>
                    Traverse[List]
                      .traverse[F, MessageDTO, Message](_dtos) { dto: MessageDTO =>
                        val m: Either[DateTimeException, Message] = Message.from(dto)
                        Sync[F].fromEither(m)
                      }
                  }
                  .adaptError {
                    case e => GetMessagesError("200 response error", e)
                  }
              case non200Status => Sync[F].raiseError(
                GetMessagesError(
                  "non-200 response",
                  UnexpectedStatus(non200Status.status)
                )
              )
            }
          }

      messages.adaptError {
        case e @ GetMessagesError(_, _) => e
        case other                      => GetMessagesError("unexpected error", other)
      }
    }
  }
}