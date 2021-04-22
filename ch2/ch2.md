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

Let's look at an example to show how the `http4s` API works.

## Example

## Testing

When I first used `http4s` professionally, I had come from building web applications using the [Play Framework](). The
simplicity of testing `http4s`'s `Client[F]`'s attracted me further to this HTTP library.