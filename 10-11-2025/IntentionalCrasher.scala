object IntentionalCrasher extends App {
  val safeDivide: PartialFunction[Int, String] = {
    case x if x != 0 => s"Result: ${100 / x}"
  }

  val safe = safeDivide.lift

  println(safe(10)) // Some("Result: 10")
  println(safe(0)) // None

}
