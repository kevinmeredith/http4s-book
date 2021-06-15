# Chapter 3. Building a Web Server

This chapter covers the following topics:
  * Recap of `org.http4s.HttpRoutes[F]`
  * Introduction to `HttpApp[F]`
  * Builing an HTTP Server Example
  * Testing Example

## Recap of `org.http4s.HttpRoutes[F]`

Recall that Chapter 1 introduced `org.http4s.HttpRoutes[F]`. It's a type alias that consists of:

```scala
type HttpRoutes[F[_]] = Http[OptionT[F, *], F]
type Http[F[_], G[_]] = Kleisli[F, Request[G], Response[G]]
```

`Kleisli[F, Request[G], Response[G]]` effectively is `Request[G] => F[Response[G]]`. Recall that, in our case, the `F[_]`
 is the same for both the `F[_]` and `G[_]` parameters.

It's trivial to compose multiple routes together via <+>.

Note that, so far, we haven't yet built a web server, but rather only routes. Let's look at `org.http4s.HttpApp`.

```scala
type HttpApp[F[_]] = Http[F, F]
type Http[F[_], G[_]] = Kleisli[F, Request[G], Response[G]]
```

Again, since our effect type is `F[_]` for each parameter, we end up with:

`Kleisli[F, Request[F], Response[F]]`, which effecitvely reduces to a function: `Request[F] => F[Response[F]]`.

Let's look into an example of building a server with two routes:

* GET /{userId}/messages  - return all messages for a given User.
    * No authorization requirements
    * Responses:
        * HTTP-200 Retrieved the message
        * HTTP-500 Something went wrong on server
* POST /{userId}/messages - create a message for a given User
    * Must include Authorization header whose value identifies the User
    * Responses:
        * HTTP-200 Created the message
        * HTTP-401 Unauthorized (missing or invalid Authorization Header)
        * HTTP-403 Forbidden - Authorization user != path's user
        * HTTP-500 Something went wrong on server

Now let's read the src/main code.

```scala
```

## Why Throwable instead of EitherT?

The server and client use an error type of java.lang.Throwable. In my four years of experience
building production web services to real customers, I've found `F[A]`, where IO is supplied for F
in the main program, to be a simple choice.

Critics of Throwable will correctly point out that Throwable is not sealed. In other words, it can't
provide any compile-time guarantees that our code will handle all sub-classes of Throwable. Such critics
will note that using a 'sealed trait ErrorADT' hierarchy will provide such a guarantee. Likely those folks
will point to EitherT[F, ErrorADT, A] as a better alternative to F[A].

Although EitherT[IO, ErrorADT, A] may seem appealing, it has the following disadvantages:
  * two error channels, Throwable and ErrorADT, make for ambiguous error handling
  * unifying multiple error types, e.g. ErrorADTA and ErrorADTB into ParentErrorADT, requires "lifting"
    boilerplate (see https://gist.github.com/tpolecat/a0b65e8ffdf5dc34a48f)
  * F[A] is simpler for lesser experienced FP Developers to understand than EitherT

Based on my experience, I recommend:

 * having the custom Throwable sub-classes be sealed to gain exhaustivity checking
    * Note that ApiError is sealed, so pattern matching on it enables us to use the compiler for exhaustive checks
 * using a middleware to match on the ApiError, and then the exhaustive cases
    * See the 'middleware' private method in server.scala

## Recommendations with Middleware Approach

 * Group together related services when defining a `def routes[F[_]](repository: Repository[F]): HttpRoutes[F]`
    * An object/class that contains multiple routes is a liability. With many routes comes more interface inputs,
      which results in more complicated and bloated tests. The bloat comes into play since, when testing a route
      in the manner from `serverspec.scala`, every test requires an implementation of each interface.
 * Use a middleware for each routes, i.e. don't re-use them across different `def routes` in objects/classes.
    * Re-using middlewares across routes, i.e. two different object's routes use the same middleware, results in tight
      coupling across services. When using a shared middleware, any changes could ripple out and break the other route. That
      can lead to slower development.
    * Consequently, I recommend separate ErrorADT hierarchies per `def routes`.
        * Although it may result in duplicating code, using a separate error ADT per routes captures all error cases, no more
          or less.
        *

Let's review the src/test code.

```scala
```