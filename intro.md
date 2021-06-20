# Introduction

This chapter covers the following topics:

- Introduction to the Author
- Importance of Team Ownership and Maintainability
- Intended Audience of Book
- Source Code Repository of Book
- Note on Scala and http4s Version
- Why I'm Writing This Book

## Introduction to the Author

Hi - I'm Kevin Meredith. Since August-2013 I've worked professionally in Scala.

Beginning in Feb-2017, I joined a company to write production code in Scala in Pure Functional Programming (FP) in Scala.
During that time-frame, I have built and maintained in production close to a dozen http4s web services. During this time,
I've used cats, scalaz, scalaz-concurrent, doobie, cats-effect, fs2, circe, argonaut, refined, scalacheck, specs2 and
scalatest libraries when building these production-deployed and real customer-s erving web services.

Prior to my full-time pure FP in Scala experience, which began in Feb-2017, I had built web services using
 Jersey (Java), Play, Lift, Hibernate, Akka and Spring.

Born and raised in Pennsylvania, I completed my Bachelor's of Science in Computer Engineering from Villanova University.
Next, I earned my Masters of Engineering in Networking from University of Pennsylvania while working full time.

In 2017 I spoke at NEScala, [Property-based Testing with ScalaCheck by Example](https://www.youtube.com/watch?v=7xUGBreqpnA).
In2020, I gave a talk at NEScala, [1000+ Compile-time Errors Later and Running Smoothly in Prod](https://www.youtube.com/watch?v=hXQJChR2ii0&t=5s).

Between Jan-2019 and April-2021 my role was Technical Lead. Essentially my role consisted of:
    * 70% heads-down time (coding + problem solving)
    * 20% pairing (coding or collaborating) with my teammates
    * 10% communicating with management and other teamms

## Importance of Team Ownership and Maintainability

Having worked in that role, I've come to appreciate the importance of team maintainability. A team should own a code-base,
not an individual. A team is on-call, not an individual. Relying upon individuals to own and maintain code-bases does not scale.

From the ~15 or so http4s production, customer-serving web application repositories that I either built from scratch or
maintained professionally, I firmly believe that significant differences across repositories is a liability. To clarify,
I've worked in code-bases ranging from "tagless final", i.e. programming to an `F[_]` interface, `EitherT`-based,
as well as `Kleisli` everywhere.

Given that I strongly prefer standardization, let me acknowledge the importance of adaptability and creativity that
Software Engineers savor. Although I favor standardization, I fully understand that standing still or rotting is not an
option. It's critical to improve the code-base based on the team's experience and future maintenance. Given that Software
Engineering is both an Art and Science, clearly Engineers desire to express themselves via their craft, code. So, in my
experience, if my teammate wanted to introduce a significant change to the code-base, it was welcomed, but also
responsibility of that teammate to educate the team on the benefits.

I live in Miami, Florida.

## Intended Audience of Book

My intended audience is intermediate software developers, namely those with some experience in functional programming.

## Source Code Repository of Book

This book's source, including the actual writing and code, is at https://github.com/kevinmeredith/http4s-book.

## Note on Scala and http4s Version

This book's code uses Scala `2.12` and `http4s` `0.21.x`.

All of my professional experience with Scala has been on version 2.x. As of this book's writing, Jun-2021,
https://http4s.org/versions/ notes that its `0.21.24` version is supported on Scala `2.12` and `2.13`.

## Why I'm Writing This Book

When building pure FP web services in http4s, I have the most confidence that my code does what I've intended. In other
words, pure FP in Scala is a powerful tool for enhancing the programmer's ability to reason about his/her code. That, I
firmly believe, is a significant competitive advantage in business.

http4s's [docs](https://http4s.org/v0.21/) have helped me to use its library.

In this book, I will give my opinions, based on my real-world production experience, on how I've gotten the most
value out of pure FP and http4s.

[Referential Transparency](https://www.reddit.com/r/scala/comments/3zofjl/why_is_future_totally_unusable/cyns21h/) and
types, especially effectful ones, are the reason.

In short, my motivation for writing this book is to share my opinions on how to build simple, team-owned and maintained
code-bases.