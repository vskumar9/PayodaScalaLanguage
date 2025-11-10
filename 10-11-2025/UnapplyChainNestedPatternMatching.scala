object UnapplyChainNestedPatternMatching extends App {
  case class Address(city: String, pincode: Int)
  case class Person(name: String, address: Address)

  val p = Person("Ravi", Address("Chennai", 600001))

  p match {
    case Person(_, Address(city, pin)) if city.startsWith("C") =>
      println(s"$city - $pin")
    case _ =>
      println("No match")
  }
}
