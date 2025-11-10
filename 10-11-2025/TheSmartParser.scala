object TheSmartParser extends App {
  def safeDivide(x: Int, y: Int): Option[Int] =
    if (y == 0) None else Some(x / y)

  def parseAndDivide(input: String): Either[String, Int] = {
    input.toIntOption match {
      case None =>
        Left("Invalid number")

      case Some(number) =>
        safeDivide(100, number) match {
          case None         => Left("Division by zero")
          case Some(result) => Right(result)
        }
    }
  }

  println(parseAndDivide("25")) // Right(4)
  println(parseAndDivide("0")) // Left(Division by zero)
  println(parseAndDivide("abc")) // Left(Invalid number)
  println(parseAndDivide("")) // Left(Invalid number)
}
