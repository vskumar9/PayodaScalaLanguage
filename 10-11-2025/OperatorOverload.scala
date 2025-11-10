case class Counter(value: Int) {

  // Add two Counter objects
  def +(that: Counter): Int = this.value + that.value

  // Add Counter with Int
  def +(that: Int): Int = this.value + that
}

object OperatorOverload extends App {

  val a = Counter(5)
  val b = Counter(7)

  println(a + b)  // 12
  println(a + 10) // 15
}
