case class FindMaxElementInAnArrayUsingTailRecursion() {
  def maxInArray(arr: Array[Int]): Int = {
    @annotation.tailrec
    def helper(index: Int, currentMax: Int): Int = {
      if (index == arr.length) currentMax
      else {
        val newMax = if (arr(index) > currentMax) arr(index) else currentMax
        helper(index + 1, newMax)
      }
    }
    if (arr.isEmpty) throw new IllegalArgumentException("Array cannot be empty")
    helper(1, arr(0))
  }
}

object FindMaxElementInAnArrayUsingTailRecursion {
  def main(args: Array[String]): Unit = {
    val finder = FindMaxElementInAnArrayUsingTailRecursion()
    val nums = Array(5, 9, 3, 7, 2)
    val maxElement = finder.maxInArray(nums)
    println(s"The maximum element in the array is: $maxElement")
  }
}
