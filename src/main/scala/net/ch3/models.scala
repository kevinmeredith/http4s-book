package net.ch3

import java.util.UUID

object models {
  final case class UserId(value: UUID)

  final case class Secret(value: String)
}
