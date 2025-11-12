object ArrowPuzzle extends App {
  def digitSum(n: Int): Int = {
    if (n == 0) 0                      // Base case: no more digits
    else (n % 10) + digitSum(n / 10)  // Last digit + sum of remaining digits
  }

  // Example usage:
  println(digitSum(1345))  // Output: 13
}
