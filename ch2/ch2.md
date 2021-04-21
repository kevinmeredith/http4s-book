# Chapter 2. Building an HTTP Client to Consume from an API

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