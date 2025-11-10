object `TheMysteryOf:+And+:` extends App {
  val nums = List(2, 4, 6)
  val list1 = List(1, 2, 3)

  // Using `:+` to append an element to the end of a list
  val appendedList = nums :+ 8
  println(
    s"Appended List: $appendedList"
  ) // Output: Appended List: List(1, 2, 3, 8)

  // Using `+:` to prepend an element to the beginning of a list
  val prependedList = 0 +: nums
  println(
    s"Prepended List: $prependedList"
  ) // Output: Prepended List: List(0, 1, 2, 3)

  val appendAndprepend = 0 +: nums :+ 8
  println(
    s"Append and Prepend: $appendAndprepend"
  ) // Output: Append and Prepend: List(0, 2, 4, 6, 8)

  // Using `++` to concatenate two lists
  val concatenatedList = list1 ++ nums
  println(
    s"Concatenated List: $concatenatedList"
  ) // Output: Concatenated List: List(1, 2, 3, 4, 5, 6)

}
