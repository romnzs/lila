package controllers

import views._

import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json.Json
import cats.data.Validated
import reactivemongo.api.bson.BSONObjectID
import org.joda.time.DateTime
import scalatags.Text.all.stringFrag
import lila.app._
import lila.api.Context
import lila.oauth.{ AccessToken, AccessTokenRequest, AuthorizationRequest, PersonalToken }

final class OAuth(env: Env) extends LilaController(env) {

  private def reqToAuthorizationRequest(req: RequestHeader) =
    AuthorizationRequest.Raw(
      clientId = get("client_id", req),
      responseType = get("response_type", req),
      redirectUri = get("redirect_uri", req),
      state = get("state", req),
      codeChallenge = get("code_challenge", req),
      codeChallengeMethod = get("code_challenge_method", req),
      scope = get("scope", req)
    )

  private def withPrompt(f: AuthorizationRequest.Prompt => Fu[Result])(implicit ctx: Context) =
    reqToAuthorizationRequest(ctx.req).prompt match {
      case Validated.Valid(prompt) => f(prompt)
      case Validated.Invalid(error) =>
        BadRequest(html.site.message("Bad authorization request")(stringFrag(error.description))).fuccess
    }

  def authorize =
    Open { implicit ctx =>
      withPrompt { prompt =>
        fuccess(ctx.me.fold(Redirect(routes.Auth.login.url, Map("referrer" -> List(ctx.req.uri)))) { me =>
          Ok(html.oAuth.app.authorize(prompt, me))
        })
      }
    }

  def authorizeApply =
    Auth { implicit ctx => me =>
      withPrompt { prompt =>
        prompt.authorize(me) match {
          case Validated.Valid(authorized) =>
            env.oAuth.authorizationApi.create(authorized) map { code =>
              Redirect(authorized.redirectUrl(code))
            }
          case Validated.Invalid(error) => Redirect(prompt.redirectUri.error(error, prompt.state)).fuccess
        }
      }
    }

  private val accessTokenRequestForm = Form(
    mapping(
      "grant_type"    -> optional(text),
      "code"          -> optional(text),
      "code_verifier" -> optional(text),
      "redirect_uri"  -> optional(text),
      "client_id"     -> optional(text)
    )(AccessTokenRequest.Raw.apply)(AccessTokenRequest.Raw.unapply)
  )

  def tokenApply =
    Action.async(parse.form(accessTokenRequestForm)) { implicit req =>
      req.body.prepare match {
        case Validated.Valid(prepared) =>
          env.oAuth.authorizationApi.consume(prepared) flatMap {
            case Validated.Valid(granted) =>
              val expiresIn = 60 * 60 * 24 * 60
              val token = AccessToken(
                id = AccessToken.Id(lila.oauth.Protocol.Secret.random("lio_").value),
                publicId = BSONObjectID.generate(),
                clientId = PersonalToken.clientId, // TODO
                userId = granted.userId,
                createdAt = DateTime.now().some,
                description = granted.redirectUri.clientOrigin.some,
                scopes = granted.scopes,
                clientOrigin = granted.redirectUri.clientOrigin.some,
                expires = DateTime.now().plusSeconds(expiresIn).some
              )
              env.oAuth.tokenApi.create(token) inject Ok(
                Json.obj(
                  "token_type"   -> "bearer",
                  "access_token" -> token.id.value,
                  "expires_in"   -> expiresIn
                )
              )
            case Validated.Invalid(err) => BadRequest(err.toJson).fuccess
          }
        case Validated.Invalid(err) => BadRequest(err.toJson).fuccess
      }
    }

  private val revokeClientForm = Form(single("origin" -> text))

  def revokeClient =
    AuthBody { implicit ctx => me =>
      implicit def req = ctx.body
      revokeClientForm
        .bindFromRequest()
        .fold(
          _ => funit,
          origin => env.oAuth.tokenApi.revokeByClientOrigin(origin, me) // TODO: also remove from token cache
        )

    }
}