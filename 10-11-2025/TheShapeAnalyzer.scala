object TheShapeAnalyzer extends App {
  case class Circle(r: Double)
  case class Rectangle(w: Double, h: Double)
  case class Triangle(a: Double, b: Double, c: Double)
  case class Square(s: Double)
  case class Ellipse(a: Double, b: Double)

  def area(shape: Any): Double = shape match {
    case Circle(r)       => Math.PI * r * r
    case Rectangle(w, h) => w * h
    case Triangle(a, b, c) =>
      val s = (a + b + c) / 2
      Math.sqrt(s * (s - a) * (s - b) * (s - c))
    case Square(s)     => s * s
    case Ellipse(a, b) => Math.PI * a * b
    case _             => -1.0
  }

  println(area(Circle(3))) // 28.274333882308138
  println(area(Rectangle(4, 5))) // 20.0
  println(area(Triangle(3, 4, 5))) // 6.0
  println(area(Square(4))) // 16.0
  println(area(Ellipse(3, 4))) // 37.69911184307752
  println(area("unknown")) // -1.0

}
