package lila.api

import lila.common.Bus

final private[api] class Cli(
    userRepo: lila.user.UserRepo,
    security: lila.security.Env,
    teamSearch: lila.teamSearch.Env,
    forumSearch: lila.forumSearch.Env,
    tournament: lila.tournament.Env,
    explorer: lila.explorer.Env,
    fishnet: lila.fishnet.Env,
    study: lila.study.Env,
    studySearch: lila.studySearch.Env,
    coach: lila.coach.Env,
    evalCache: lila.evalCache.Env,
    plan: lila.plan.Env,
    msg: lila.msg.Env,
    video: lila.video.Env,
    email: lila.mailer.AutomaticEmail
)(implicit ec: scala.concurrent.ExecutionContext)
    extends lila.common.Cli {

  private val logger = lila.log("cli")

  def apply(args: List[String]): Fu[String] =
    run(args).dmap(_ + "\n") ~ {
      _.logFailure(logger, _ => args mkString " ") foreach { output =>
        logger.info("%s\n%s".format(args mkString " ", output))
      }
    }

  def process = {
    case "uptime" :: Nil => fuccess(s"${lila.common.Uptime.seconds} seconds")
    case "change" :: ("asset" | "assets") :: "version" :: Nil =>
      import lila.common.AssetVersion
      AssetVersion.change()
      fuccess(s"Changed to ${AssetVersion.current}")
    case "gdpr" :: "erase" :: username :: "forever" :: Nil =>
      userRepo named username map {
        case None                       => "No such user."
        case Some(user) if user.enabled => "That user account is not closed. Can't erase."
        case Some(user) =>
          userRepo setEraseAt user
          email gdprErase user
          Bus.publish(lila.user.User.GDPRErase(user), "gdprErase")
          s"Erasing all search data about ${user.username} now"
      }
    case "announce" :: "cancel" :: Nil =>
      AnnounceStore set none
      Bus.publish(AnnounceStore.cancel, "announce")
      fuccess("Removed announce")

    case "announce" :: length :: unit :: s"[${msgKey}]" :: Nil =>
      AnnounceStore.setPredefined(msgKey, s"$length $unit") match {
        case Some(announce) =>
          Bus.publish(announce, "announce")
          fuccess(announce.json.toString)
        case None =>
          fuccess(
            s"""Invalid announce. Format: `announce <length> <unit> [<msgKey>]` or just `announce cancel` to cancel it
Available message keys: ${AnnounceStore.predefinedMessageKeys.mkString(", ")}"""
          )
      }

    case "announce" :: msgWords =>
      AnnounceStore.set(msgWords mkString " ") match {
        case Some(announce) =>
          Bus.publish(announce, "announce")
          fuccess(announce.json.toString)
        case None =>
          fuccess(
            "Invalid announce. Format: `announce <length> <unit> <words...>` or just `announce cancel` to cancel it"
          )
      }
  }

  private def run(args: List[String]): Fu[String] = {
    (processors lift args) | fufail("Unknown command: " + args.mkString(" "))
  } recover { case e: Exception =>
    "ERROR " + e
  }

  private def processors =
    security.cli.process orElse
      teamSearch.cli.process orElse
      forumSearch.cli.process orElse
      tournament.cli.process orElse
      explorer.cli.process orElse
      fishnet.cli.process orElse
      study.cli.process orElse
      studySearch.cli.process orElse
      evalCache.cli.process orElse
      plan.cli.process orElse
      msg.cli.process orElse
      video.cli.process orElse
      process
}
