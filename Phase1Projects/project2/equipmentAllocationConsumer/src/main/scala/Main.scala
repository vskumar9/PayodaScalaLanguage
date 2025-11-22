import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import slick.basic.DatabaseConfig
import slick.jdbc.MySQLProfile

import scala.concurrent.ExecutionContext
import play.api.db.slick.DatabaseConfigProvider
import repositories._
import actors._
import mail.Mailer

/**
 * A lightweight implementation of [[play.api.db.slick.DatabaseConfigProvider]]
 * used in standalone or non-Play applications.
 *
 * This class is necessary because, outside of Play Framework, there is no
 * automatic dependency injection to provide a `DatabaseConfigProvider`.
 *
 * ### Purpose
 * - Allows repositories to be instantiated exactly as they would be in Play.
 * - Bridges standalone applications with Slick-based repositories.
 * - Ensures consistent database configuration resolution.
 *
 * @param dbConfig The Slick database configuration used across the application.
 */
class SimpleDbConfigProvider(dbConfig: DatabaseConfig[MySQLProfile]) extends DatabaseConfigProvider {
  /**
   * Returns the provided Slick [[DatabaseConfig]] as the requested profile type.
   *
   * @tparam P Slick profile type (usually inferred as `MySQLProfile`)
   */
  override def get[P <: slick.basic.BasicProfile]: slick.basic.DatabaseConfig[P] =
    dbConfig.asInstanceOf[slick.basic.DatabaseConfig[P]]
}

/**
 * Main entry point for the standalone Equipment System application.
 *
 * This object bootstraps:
 *   - Configuration loading
 *   - Actor system initialization
 *   - Slick database configuration and injection
 *   - Repository creation
 *   - Mailer setup
 *   - All domain actors (Allocation, Inventory, Maintenance, Reminder)
 *   - Kafka consumer actor for event-driven operations
 *
 * ### Responsibilities
 * - Constructs all infrastructure components without Play Framework.
 * - Wires repositories and actors together manually.
 * - Loads application configuration via Typesafe Config.
 * - Starts Kafka consumption loop for equipment-related events.
 *
 * ### Application Flow
 * 1. Load config from `application.conf`
 * 2. Create `ActorSystem`
 * 3. Resolve Slick `DatabaseConfig`
 * 4. Create repositories (Slick DAOs)
 * 5. Initialize `Mailer`
 * 6. Start domain actors
 * 7. Start `KafkaConsumerActor`
 *
 * This allows the equipment management system to run as a standalone
 * backend service without Play's web layer.
 */
object Main extends App {

  /** Loads application configuration (mail, DB, Kafka settings, etc.). */
  val conf = ConfigFactory.load()

  /** Global actor system for managing application actors. */
  implicit val system: ActorSystem = ActorSystem("EquipmentSystem")

  /** Execution context for async DB and actor operations. */
  implicit val ec: ExecutionContext = system.dispatcher

  /** Load Slick DB configuration from `application.conf` path `slick.dbs.default`. */
  val dbConfig: DatabaseConfig[MySQLProfile] =
    DatabaseConfig.forConfig[MySQLProfile]("slick.dbs.default", conf)

  /** Provide a Play-compatible DB config provider for repositories. */
  val dbConfigProvider: DatabaseConfigProvider = new SimpleDbConfigProvider(dbConfig)

  /** Load mail settings and create SMTP mail service. */
  val mailConf = conf.getConfig("mail")

  val emailService = new Mailer(
    host = mailConf.getString("host"),
    port = mailConf.getInt("port"),
    user = mailConf.getString("user"),
    pass = mailConf.getString("password")
  )

  // --------------------------------------------------------------------------
  // Repository Initialization
  // --------------------------------------------------------------------------

  val allocRepo    = new EquipmentAllocationRepository(dbConfigProvider)(ec)
  val itemRepo     = new EquipmentItemRepository(dbConfigProvider)(ec)
  val employeeRepo = new EmployeeRepository(dbConfigProvider)(ec)
  val userRepo     = new UserRepository(dbConfigProvider)(ec)
  val ticketRepo   = new MaintenanceTicketRepository(dbConfigProvider)(ec)

  // --------------------------------------------------------------------------
  // Actor Initialization
  // --------------------------------------------------------------------------

  /** Actor responsible for equipment allocation events. */
  val allocationActor = system.actorOf(
    AllocationActor.props(allocRepo, itemRepo, employeeRepo, userRepo, emailService),
    "allocation-actor"
  )

  /** Actor responsible for inventory events (equipment updates, conditions). */
  val inventoryActor = system.actorOf(
    InventoryActor.props(itemRepo, allocRepo, employeeRepo, userRepo, emailService),
    "inventory-actor"
  )

  /** Actor responsible for maintenance ticket workflows & email notifications. */
  val maintenanceActor = system.actorOf(
    MaintenanceActor.props(ticketRepo, itemRepo, employeeRepo, userRepo, emailService),
    "maintenance-actor"
  )

  /** Actor responsible for overdue reminders and general reminder notifications. */
  val reminderActor = system.actorOf(
    ReminderActor.props(allocRepo, itemRepo, employeeRepo, userRepo, emailService),
    "reminder-actor"
  )

  /** Kafka consumer actor that listens to equipment event topics and routes them. */
  system.actorOf(
    KafkaConsumerActor.props(allocationActor, inventoryActor, maintenanceActor, reminderActor),
    "kafka-consumer-actor"
  )
}
