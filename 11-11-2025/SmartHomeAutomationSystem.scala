package SmartHomeAutomationSystem

/** Represents a general smart home device. Provides basic operations such as
  * turning on, turning off, and checking status.
  */
trait Device {

  /** Turns the device on. */
  def turnOn(): Unit

  /** Turns the device off. */
  def turnOff(): Unit

  /** Prints the current operational status of the device. Default
    * implementation indicates that the device is operational.
    */
  def status(): Unit = println("Device is operational")
}

/** Adds network connectivity support to a device. Includes methods to connect
  * and disconnect from the network.
  */
trait Connectivity {

  /** Connects the device to the smart home network. */
  def connect(): Unit = println("Device connected to network")

  /** Disconnects the device from the network. */
  def disconnect(): Unit = print("Device disconnected")
}

/** Adds voice control functionality to a device. Overrides turnOn and turnOff
  * to simulate voice-activated actions.
  */
trait VoiceControl {

  /** Turns the device on via voice command. */
  def turnOn(): Unit =
    println("Voice control: Device turned on")

  /** Turns the device off via voice command. */
  def turnOff(): Unit =
    println("Voice control: Device turned off")
}

/** Enhances a device with energy-saving capabilities. Overrides the shutdown
  * behavior to conserve energy.
  */
trait EnergySaver extends Device {

  /** Activates the device's energy-saving mode. */
  def activateEnergySaver(): Unit =
    println("Energy saver mode activated")

  /** Custom shutdown behavior designed to reduce power usage. Overrides the
    * default Device turnOff method.
    */
  override def turnOff(): Unit =
    println("Device powered down to save energy")
}

/** A smart light device equipped with connectivity and energy-saving features.
  * Provides custom behavior for turning the light on.
  */
class SmartLight extends Device with Connectivity with EnergySaver {

  /** Turns the smart light on with a bright illumination message. */
  override def turnOn(): Unit =
    println("SmartLight is shining brightly!")
}

/** A smart thermostat device equipped with connectivity and energy-saving
  * features. Provides custom behavior for turning the thermostat on and off.
  */
class SmartThermostat extends Device with Connectivity with EnergySaver {

  /** Activates the thermostat heating mechanism. */
  override def turnOn(): Unit =
    println("SmartThermostat warming up!")

  /** Custom safe shutdown sequence for the thermostat. */
  override def turnOff(): Unit =
    println("SmartThermostat shutting down safely.")
}

/** Demonstrates smart device operations and trait-based feature composition in
  * a Smart Home Automation System.
  */
object SmartHomeAutomationSystem extends App {

  /** Instance of a smart light device. */
  val smartLight = new SmartLight

  /** Instance of a smart thermostat device. */
  val smartThermostat = new SmartThermostat

  smartLight.turnOn()
  smartLight.connect()
  smartLight.activateEnergySaver()
  smartLight.turnOff()
  smartLight.status()

  println("__________________________________________________________")

  smartThermostat.turnOn()
  smartThermostat.connect()
  smartThermostat.activateEnergySaver()
  smartThermostat.turnOff()
  smartThermostat.status()

  println("__________________________________________________________")

  /** A smart light enhanced with voice control. Demonstrates resolving method
    * conflicts using super with trait qualifiers.
    */
  val voiceControl = new SmartLight with VoiceControl {
    override def turnOn(): Unit =
      super[VoiceControl].turnOn()

    override def turnOff(): Unit =
      super[VoiceControl].turnOff()
  }

  voiceControl.turnOn()
  voiceControl.turnOff()
}
