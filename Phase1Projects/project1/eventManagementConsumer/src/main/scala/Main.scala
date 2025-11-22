import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import slick.basic.DatabaseConfig
import slick.jdbc.MySQLProfile
import scala.concurrent.ExecutionContext

import play.api.db.slick.DatabaseConfigProvider

import repositories._
import actors._
import utils.SimpleDbConfigProvider
import mail.Mailer

/**
 * Application entry point responsible for bootstrapping the Event Management System.
 *
 * This object performs all essential startup tasks, including:
 *   - Loading configuration from application.conf
 *   - Initializing the ActorSystem
 *   - Creating the Slick database configuration
 *   - Initializing repositories and utility services
 *   - Creating application actors (Event, Team, Task, Notification, KafkaConsumer)
 *
 * The system uses Akka Actors for event-driven processing and Slick for database access.
 * All dependencies are wired manually here (without using Play's dependency injection).
 */
object Main extends App {

  /** Loads configuration from application.conf. */
  val conf = ConfigFactory.load()

  /** Global ActorSystem used for all actors in the application. */
  implicit val system: ActorSystem = ActorSystem("EventManagementSystem")

  /** ExecutionContext backed by the ActorSystem dispatcher. */
  implicit val ec: ExecutionContext = system.dispatcher

  /** Slick database configuration loaded from `slick.dbs.default`. */
  val dbConfig: DatabaseConfig[MySQLProfile] =
    DatabaseConfig.forConfig[MySQLProfile]("slick.dbs.default", conf)

  /**
   * A simple provider that wraps the database configuration,
   * enabling repositories to receive it via constructor injection.
   */
  val dbConfigProvider: DatabaseConfigProvider =
    new SimpleDbConfigProvider(dbConfig)

  // ---------------------------------------------------------------------------
  // Repository Initialization
  // ---------------------------------------------------------------------------

  /** Repository for managing events. */
  val eventsRepo         = new EventsRepository(dbConfigProvider)(ec)

  /** Repository for managing teams. */
  val teamsRepo          = new TeamsRepository(dbConfigProvider)(ec)

  /** Repository for managing team-member associations. */
  val teamMembersRepo    = new TeamMembersRepository(dbConfigProvider)(ec)

  /** Repository for managing tasks. */
  val tasksRepo          = new TasksRepository(dbConfigProvider)(ec)

  /** Repository for managing notifications. */
  val notificationsRepo  = new NotificationsRepository(dbConfigProvider)(ec)

  /** Repository for managing users. */
  val usersRepo          = new UsersRepository(dbConfigProvider)(ec)

  // ---------------------------------------------------------------------------
  // Email Service Initialization
  // ---------------------------------------------------------------------------

  /** Configuration block for mail settings. */
  val mailConf = conf.getConfig("mail")

  /**
   * Email service implementation using SMTP settings loaded from configuration.
   */
  val emailService = new Mailer(
    host = mailConf.getString("host"),
    port = mailConf.getInt("port"),
    user = mailConf.getString("user"),
    pass = mailConf.getString("password")
  )

  // ---------------------------------------------------------------------------
  // Actor Initialization
  // ---------------------------------------------------------------------------

  /** Actor responsible for handling event lifecycle and notifications. */
  val eventActor = system.actorOf(
    EventActor.props(eventsRepo, usersRepo, emailService),
    "event-actor"
  )

  /** Actor responsible for team management workflows. */
  val teamActor  = system.actorOf(
    TeamActor.props(teamsRepo, usersRepo, teamMembersRepo, emailService),
    "team-actor"
  )

  /** Actor responsible for processing and updating tasks. */
  val taskActor  = system.actorOf(
    TaskActor.props(tasksRepo, usersRepo, teamsRepo, emailService),
    "task-actor"
  )

  /** Actor responsible for sending notification emails and updating status. */
  val notificationActor = system.actorOf(
    NotificationActor.props(notificationsRepo, usersRepo, teamsRepo, emailService),
    "notification-actor"
  )

  // ---------------------------------------------------------------------------
  // Kafka Consumer Initialization
  // ---------------------------------------------------------------------------

  /**
   * Kafka consumer actor that receives messages from Kafka topics and
   * forwards them to the respective domain actors for processing.
   */
  system.actorOf(
    KafkaConsumerActor.props(eventActor, teamActor, taskActor, notificationActor),
    "kafka-consumer-actor"
  )
}
