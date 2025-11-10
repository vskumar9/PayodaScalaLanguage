object DelayedGreeter extends App {
  def delayedMessage(delayMs: Int)(message: String): Unit = {
    Thread.sleep(delayMs)
    println(message)
  }

  val oneSecondSay = delayedMessage(1000)

  oneSecondSay("Hello...")
  oneSecondSay("How are you?")
  oneSecondSay("This message is delayed each time!")
}
