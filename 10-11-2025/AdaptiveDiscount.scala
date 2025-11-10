object AdaptiveDiscount extends App {
  def discountStrategy(memberType: String): Double => Double = {
    memberType.toLowerCase match {
      case "gold"   => amount => amount * 0.8
      case "silver" => amount => amount * 0.9
      case _        => amount => amount
    }
  }

  val goldDiscount = discountStrategy("gold")
  println(goldDiscount(1000)) // 800.0

  val silverDiscount = discountStrategy("silver")
  println(silverDiscount(1000)) // 900.0

  val normalDiscount = discountStrategy("regular")
  println(normalDiscount(1000)) // 1000.0

}
