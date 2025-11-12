object ArrayMirrorPuzzle extends App {
  def mirrorArray(arr: Array[Int]): Array[Int] = {
    val n = arr.length
    val mirrored = for (i <- 0 until 2 * n) yield {
      if (i < n) arr(i)           // first half: original elements
      else arr(2 * n - 1 - i)     // second half: mirrored elements
    }
    mirrored.toArray
  }

  val input = Array(1, 2, 3)
  val mirrored = mirrorArray(input)
  println(mirrored.mkString("Array(", ", ", ")"))  // Array(1, 2, 3, 3, 2, 1)
}
