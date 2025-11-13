/** Represents the core functionality of a drone. Provides methods to activate,
  * deactivate, and check the status of the drone.
  */
trait Drone {

  /** Activates the drone */
  def activate(): Unit

  /** Deactivates the drone */
  def deactivate(): Unit

  /** Prints the current operational status of the drone. Default implementation
    * prints "Drone status: operational".
    */
  def status(): Unit = println("Drone status: operational")
}

/** Provides navigation capabilities for a drone. Includes the ability to fly to
  * a specified destination.
  *
  * Extends [[Drone]] and overrides the deactivate method.
  */
trait NavigationModule extends Drone {

  /** Navigates the drone to the given destination.
    *
    * @param destination
    *   The target location to fly to.
    */
  def flyTo(destination: String): Unit =
    println(s"Flying to $destination")

  /** Deactivates navigation-specific systems. */
  override def deactivate(): Unit =
    println("Navigation systems shutting down")
}

/** Adds defensive capabilities to a drone. Includes shield activation.
  *
  * Extends [[Drone]] and overrides the deactivate method.
  */
trait DefenseModule extends Drone {

  /** Activates the drone's defense shields. */
  def activateShields(): Unit = println("Shields activated")

  /** Deactivates defense-related systems. */
  override def deactivate(): Unit =
    println("Defense systems deactivated")
}

/** Adds communication capabilities to a drone. Includes the ability to send
  * messages.
  *
  * Extends [[Drone]] and overrides the deactivate method.
  */
trait CommunicationModule extends Drone {

  /** Sends a communication message.
    *
    * @param msg
    *   The message to be transmitted.
    */
  def sendMessage(msg: String): Unit =
    println(s"Sending message: $msg")

  /** Shuts down the communication module. */
  override def deactivate(): Unit =
    println("Communication module shutting down")
}

/** A simple drone implementation with basic activate and deactivate behavior.
  */
class BasicDrone extends Drone {

  /** Activates the basic drone. */
  override def activate(): Unit =
    print("BasicDrone activated")

  /** Deactivates the basic drone. */
  override def deactivate(): Unit =
    print("BasicDrone deactivated")
}

/** Demonstrates different combinations of drone modules using Scala traits
  * mixed into a basic drone.
  */
object IntergalacticDrone extends App {

  /** Drone with navigation and defense capabilities. */
  val drone1 = new BasicDrone with NavigationModule with DefenseModule
  drone1.activate()
  drone1.status()
  drone1.flyTo("Mars")
  drone1.activateShields()
  drone1.deactivate()

  println("________________________________________________")

  /** Drone with communication and navigation modules. */
  val drone2 = new BasicDrone with CommunicationModule with NavigationModule
  drone2.activate()
  drone2.status()
  drone2.sendMessage("Hello, Earth!")
  drone2.flyTo("Venus")
  drone2.deactivate()

  println("________________________________________________")

  /** Drone with defense, communication, and navigation capabilities. */
  val drone3 = new BasicDrone
    with DefenseModule
    with CommunicationModule
    with NavigationModule
  drone3.activate()
  drone3.status()
  drone3.activateShields()
  drone3.sendMessage("Mission Complete")
  drone3.flyTo("Jupiter")
  drone3.deactivate()
}
