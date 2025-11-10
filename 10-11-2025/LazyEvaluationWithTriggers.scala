class LazyCounter {
  private var computeCount = 0

  lazy val value: Int = {
    computeCount += 1
    println("Computing value...")
    42
  }

  def getCount: Int = computeCount
}

object LazyEvaluationWithTriggers extends App {
  val c = new LazyCounter

  println("Before first access")
  println(c.value)

  println("Access again")
  println(c.value)

  println("Compute count: " + c.getCount)
//Before first access
// Computing value...
// 42
// Access again
// 42
// Compute count: 1
}
