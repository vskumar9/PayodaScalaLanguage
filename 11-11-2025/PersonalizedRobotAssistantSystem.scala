/** Represents a general robot with basic lifecycle operations. Includes methods
  * to start, shut down, and check operational status.
  */
trait Robot {

  /** Starts the robot's core systems. */
  def start(): Unit

  /** Shuts down the robot. */
  def shutdown(): Unit

  /** Prints the current operational status of the robot. Default implementation
    * indicates the robot is operational.
    */
  def status(): Unit = println("Robot is operational")
}

/** Provides speech capabilities for a robot. Enables the robot to speak
  * messages aloud.
  */
trait SpeechModule {

  /** Generates a speech output with the given message.
    *
    * @param message
    *   The message to be spoken by the robot.
    */
  def speak(message: String): Unit =
    println(s"Robot says: $message")
}

/** Adds movement capabilities to a robot. Supports both forward and backward
  * movement.
  */
trait MovementModule {

  /** Moves the robot forward. */
  def moveForward(): Unit = println("Moving forward")

  /** Moves the robot backward. */
  def moveBackward(): Unit = println("Moving backward")
}

/** Adds energy-saving behavior to a robot. Overrides the shutdown process to
  * conserve power.
  */
trait EnergySaver extends Robot {

  /** Activates energy-saving mode. */
  def activateEnergySaver(): Unit = println("Energy saver mode activated")

  /** Custom shutdown behavior designed to conserve energy. Overrides the
    * default Robot shutdown method.
    */
  override def shutdown(): Unit =
    println("Robot shutting down to save energy")
}

/** A simple implementation of a robot. Provides basic start and shutdown
  * actions.
  */
class BasicRobot extends Robot {

  /** Starts the basic robot systems. */
  override def start(): Unit =
    println("BasicRobot starting up")

  /** Shuts down the basic robot systems. */
  override def shutdown(): Unit =
    println("BasicRobot shutting down")
}

/** Demonstrates various robot configurations using behavior-mixing traits.
  */
object PersonalizedRobotAssistantSystem extends App {

  /** Robot with speech and movement capabilities. */
  val myRobot = new BasicRobot with SpeechModule with MovementModule
  myRobot.start()
  myRobot.status()
  myRobot.speak("Hello!")
  myRobot.moveForward()
  myRobot.shutdown()

  println("------------------------------------------------")

  /** Robot with speech, movement, and energy-saving abilities. */
  val energyRobot = new BasicRobot
    with SpeechModule
    with MovementModule
    with EnergySaver
  energyRobot.start()
  energyRobot.speak("Saving energy now")
  energyRobot.moveBackward()
  energyRobot.activateEnergySaver()
  energyRobot.shutdown()

  println("------------------------------------------------")

  /** Robot with mixed-in traits in a different order to illustrate Scala’s
    * trait linearization and method resolution.
    */
  val reversedRobot = new BasicRobot
    with SpeechModule
    with EnergySaver
    with MovementModule
  reversedRobot.start()
  reversedRobot.speak("Testing method resolution")
  reversedRobot.moveForward()
  reversedRobot.activateEnergySaver()
  reversedRobot.shutdown()
}
