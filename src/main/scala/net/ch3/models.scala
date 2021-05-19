package net.ch3

import cats.Eq
import cats.implicits._
import java.util.UUID

object models {
  final case class UserId(value: UUID)

  final case class AuthToken(value: String)
  object AuthToken {
    implicit val eq: Eq[AuthToken] = new Eq[AuthToken] {
      override def eqv(x: AuthToken, y: AuthToken): Boolean =
        x.value === y.value
    }
  }
}
