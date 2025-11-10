object ImplicitParameterForPrecisionInOperator extends App {
  implicit val roundingPrecision: Double = 0.05

  case class Money(amount: Double) {

    private def roundToPrecision(
        value: Double
    )(implicit precision: Double): Double = {
      val rounded = Math.round(value / precision) * precision
      BigDecimal(rounded).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble
    }

    def +(other: Money)(implicit precision: Double): Money =
      Money(roundToPrecision(this.amount + other.amount))

    def -(other: Money)(implicit precision: Double): Money =
      Money(roundToPrecision(this.amount - other.amount))

    override def toString: String = f"Money($amount%.2f)"
  }

  val m1 = Money(10.23)
  val m2 = Money(5.19)

  println(m1 + m2) // Money(15.40)
  println(m1 - m2) // Money(5.05)
}
