object ChainedImplicitConversions extends App {
  case class Rational(n: Int, d: Int) {
    require(d != 0, "Denominator cannot be zero")

    def /(that: Rational): Rational =
      Rational(this.n * that.d, this.d * that.n)

    override def toString: String = s"Rational($n,$d)"
  }

  implicit def intToRational(x: Int): Rational = Rational(x, 1)

  val r = 1 / Rational(2, 3)
  println(r) // Rational(3,2)

  // Normal Int / Int still works — it's not ambiguous
  val normal = 6 / 2
  println(normal) // 3

}
