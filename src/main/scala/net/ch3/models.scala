package net.ch3

import cats.Eq
import cats.implicits._
import java.util.UUID

object models {
  final case class UserId(value: UUID)

  final case class Secret(value: String)
  object Secret {
    implicit val eq: Eq[Secret] = new Eq[Secret] {
      override def eqv(x: Secret, y: Secret): Boolean =
        x.value === y.value
    }
  }
}
