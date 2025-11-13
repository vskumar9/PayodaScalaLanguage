/** Represents a general spacecraft with a given fuel level. Defines abstract
  * launch behavior and a default landing procedure.
  *
  * @param fuelLevel
  *   The amount of fuel available in the spacecraft.
  */
abstract class Spacecraft(val fuelLevel: Int) {

  /** Initiates the spacecraft's launch sequence. */
  def launch(): Unit

  /** Performs a standard landing sequence. Default implementation prints a
    * generic landing message.
    */
  def land(): Unit =
    println("Standard landing procedure initiated. Touchdown successful.")
}

/** Provides autonomous navigation capabilities for spacecraft. Includes a
  * default implementation for automatic navigation.
  */
trait Autopilot {

  /** Engages the autopilot navigation system. */
  def autoNavigate(): Unit = println("Autopilot engaged.")
}

/** A spacecraft designed for transporting cargo. It supports launch, landing,
  * and autopilot functionalities.
  *
  * @param fuelLevel
  *   Amount of fuel available.
  */
class CargoShip(fuelLevel: Int) extends Spacecraft(fuelLevel) with Autopilot {

  /** Launch sequence specific to cargo spacecraft. */
  override def launch(): Unit =
    println("CargoShip firing main thrusters for liftoff!")

  /** Performs a custom landing operation. Marked as final to prevent further
    * overriding.
    */
  final override def land(): Unit =
    println("CargoShip performing automated landing and cargo unloading.")
}

/** A spacecraft designed to carry passengers. Extends Spacecraft and includes
  * autopilot capabilities.
  *
  * @param fuelLevel
  *   Amount of fuel available.
  */
class PassengerShip(fuelLevel: Int)
    extends Spacecraft(fuelLevel)
    with Autopilot {

  /** Launch sequence specific to passenger spacecraft with safety checks. */
  override def launch(): Unit =
    println("PassengerShip commencing passenger safety checks and takeoff!")

  /** Custom landing procedure including passenger safety protocols. */
  override def land(): Unit =
    println("PassengerShip land: Safety protocols for all passengers.")
}

/** A luxury variant of the PassengerShip. Includes entertainment features for
  * passengers.
  *
  * This class is final and cannot be extended further.
  *
  * @param fuelLevel
  *   Amount of fuel available.
  */
final class LuxuryCruiser(fuelLevel: Int) extends PassengerShip(fuelLevel) {

  /** Plays onboard entertainment for luxury passengers. */
  def playEntertainment(): Unit =
    println("Welcome to the intergalactic theater: Enjoy movies and music!")
}

/** Demonstrates spacecraft operations using cargo, passenger, and luxury ships.
  */
object IntergalacticTransportSystem extends App {

  /** Instance of a cargo spacecraft with fuel level 100. */
  val cargoShip: CargoShip = CargoShip(100)

  /** Instance of a passenger spacecraft with fuel level 200. */
  val passengerShip: PassengerShip = PassengerShip(200)

  /** Instance of a luxury spacecraft with fuel level 300. */
  val luxuryCruiser: LuxuryCruiser = LuxuryCruiser(300)

  cargoShip.launch()
  cargoShip.land()
  cargoShip.autoNavigate()
  println(s"Cargo Ship Fuel Level: ${cargoShip.fuelLevel}\n")

  passengerShip.launch()
  passengerShip.land()
  passengerShip.autoNavigate()
  println(s"Passenger Ship Fuel Level: ${passengerShip.fuelLevel}\n")

  luxuryCruiser.launch()
  luxuryCruiser.land()
  luxuryCruiser.autoNavigate()
  luxuryCruiser.playEntertainment()
  println(s"Luxury Cruiser Fuel Level: ${luxuryCruiser.fuelLevel}\n")
}
