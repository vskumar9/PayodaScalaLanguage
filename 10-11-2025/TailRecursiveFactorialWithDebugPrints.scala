case class TailRecursiveFactorialWithDebugPrints() {
  def factorial(n: Int): Int = {
    @annotation.tailrec
    def factorial(acc: Int, n: Int): Int = {
      println(s"[acc=$acc, n=$n]")
      if (n <= 1) acc
      else factorial(acc * n, n - 1)
    }
    factorial(1, n)
  }
}

object TailRecursiveFactorialApp extends App {
  val number = 5
  val result = TailRecursiveFactorialWithDebugPrints().factorial(number)
}
