package jira

import cats.*
import cats.implicits.*
import core.Newtype
import io.circe.*
import io.circe.generic.auto.*
import io.circe.syntax.*
import sttp.capabilities.WebSockets
import sttp.client3.*
import sttp.client3.circe.*
import java.net.URI

type ProjectKey = ProjectKey.Type
object ProjectKey extends Newtype[String]

type Summary = Summary.Type
object Summary extends Newtype[String]

case class Description(paragraphContent: List[Content])

type Username = Username.Type
object Username extends Newtype[String]

type Password = Password.Type
object Password extends Newtype[String]

type Address = Address.Type
object Address extends Newtype[String]

case class Config(username: Username, password: Password, address: Address)

sealed trait Content:
  def toMessage: Map[String, Json]
object Content:
  case class Text(value: String) extends Content:
    def toMessage: Map[String, Json] =
      Map("text" -> value.asJson, "type" -> "text".asJson)

  case class Link(url: URI) extends Content:
    def toMessage: Map[String, Json] =
      Map("type" -> "inlineCard".asJson, "attrs" -> Map("url" -> url).asJson)

trait Jira[F[_]]:
  def createTicket(
      projectKey: ProjectKey,
      summary: Summary,
      description: Description,
      issueType: String
  ): F[Either[String, Unit]]

object Jira:
  def make[F[_]: Monad](
      config: Config,
      backend: SttpBackend[F, WebSockets]
  ): Jira[F] = new:
    given conv[T: Encoder]: Conversion[T, Json] with
      def apply(x: T): Json = x.asJson

    def createTicket(
        projectKey: ProjectKey,
        summary: Summary,
        description: Description,
        issueType: String
    ): F[Either[String, Unit]] =
      val body: Json = Map(
        "fields" -> Map[String, Json](
          "project"   -> Map("key" -> projectKey),
          "issuetype" -> Map("name" -> issueType),
          "summary"   -> summary,
          "description" -> Map[String, Json](
            "type"    -> "doc",
            "version" -> 1,
            "content" -> List(
              Map[String, Json](
                "type" -> "paragraph",
                "content" -> description
                  .paragraphContent
                  .map(_.toMessage)
                  .asJson
              )
            ).asJson
          )
        )
      )
      basicRequest
        .post(uri"${config.address}/rest/api/3/issue")
        .auth
        .basic(config.username.value, config.password.value)
        .body(body)
        .send(backend)
        .map(_.body.leftMap(_.toString).void)
