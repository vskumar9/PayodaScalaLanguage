case class Order(id: Int, amount: Double, status: String)

object FunctionalFilteringAndTransformation {
  def main(args: Array[String]): Unit = {
    val orders = List(
      Order(1, 1200.0, "Delivered"),
      Order(2, 250.5, "Pending"),
      Order(3, 980.0, "Delivered"),
      Order(4, 75.0, "Cancelled")
    )

    // Filter completed orders and transform to get their amounts
    val result1 = for {
      Order(id, amount, status) <- orders
      if status == "Delivered"
      if amount > 500
    } yield s"Order #$id -> ₹$amount"

    // Using functional methods
    val result2 = orders
      .withFilter { case Order(_, _, status) => status == "Delivered" }
      .withFilter { case Order(_, amount, _) => amount > 500 }
      .map { case Order(id, amount, _) => s"Order #$id -> ₹$amount" }

    println("Amounts of Completed Orders:")
    result1.foreach(println)
    println("\nAmounts of Completed Orders (Functional):")
    result2.foreach(println)
  }
}
