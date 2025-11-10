object ThePersonalizedCalculator extends App {
  def calculate(op: String)(x: Int, y: Int): Int = op match {
    case "add" => x + y
    case "sub" => x - y
    case "mul" => x * y
    case "div" => x / y
    case _     => throw new IllegalArgumentException("Unknown operation")
  }

  val add = calculate("add")
  val multiply = calculate("mul")

  println(add(10, 5))      // 15
  println(multiply(3, 4))  // 12
  println(calculate("sub")(20, 8)) // 12
  
}
