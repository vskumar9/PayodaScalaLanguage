object TheSafeDivide extends App {
  def safeDivide(x: Int, y: Int): Option[Int] = {
    if (y == 0) None
    else Some(x / y)
  }

  val result = safeDivide(10, 0).getOrElse(-1)
  println(result) // -1
  val result2 = safeDivide(10, 2).getOrElse(-1)
  println(result2) // 5

}
