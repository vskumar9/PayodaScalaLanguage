import scala.io.StdIn._

case class DigitSumusingSimpleRecursion() {
  def digitSum(n: Int): Int = {
    if (n == 0) 0
    else (n % 10) + digitSum(n / 10)
  }
}

object DigitSumApp extends App {
  // Read input from user
  println("Enter a number to calculate the sum of its digits:")
  val number = readLine().toInt
  // Calculate digit sum
  val sum = DigitSumusingSimpleRecursion().digitSum(number)
  println(s"The sum of digits in $number is $sum")
}
