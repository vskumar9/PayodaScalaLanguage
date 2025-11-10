object CreateVectorAlgebraDSL_UsingOperatorOverloading extends App {
  case class Vec2D(x: Int, y: Int) {
    def +(that: Vec2D): Vec2D = Vec2D(this.x + that.x, this.y + that.y)
    def -(that: Vec2D): Vec2D = Vec2D(this.x - that.x, this.y - that.y)
    def *(scalar: Int): Vec2D = Vec2D(this.x * scalar, this.y * scalar)
    override def toString: String = s"Vec2D($x,$y)"
  }

  implicit class IntOps(n: Int) {
    def *(v: Vec2D): Vec2D = v * n
  }

  val v1 = Vec2D(2, 3)
  val v2 = Vec2D(4, 1)

  println(v1 + v2) // Vec2D(6,4)
  println(v1 - v2) // Vec2D(-2,2)
  println(v1 * 3) // Vec2D(6,9)
  println(3 * v1) // Vec2D(6,9)

  val result = 2 * v1 + v2 * 3 - v1
  println(result) // Vec2D(14,6)
}
