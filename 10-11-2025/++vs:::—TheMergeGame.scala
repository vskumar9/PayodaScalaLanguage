object `++vs:::—TheMergeGame` extends App {
  // Method to merge two lists using ++ operator
  def mergeUsingPlusPlus[A](list1: List[A], list2: List[A]): List[A] = {
    list1 ++ list2
  }

  // Method to merge two lists using ::: operator
  def mergeUsingColonColonColon[A](list1: List[A], list2: List[A]): List[A] = {
    list1 ::: list2
  }

  val listA = List(1, 2)
  val listB = List(3, 4)

  // Merging using ++
  val mergedPlusPlus = mergeUsingPlusPlus(listA, listB)
  println(s"Merged using ++ : $mergedPlusPlus")

  // Merging using :::
  val mergedColonColonColon = mergeUsingColonColonColon(listA, listB)
  println(s"Merged using ::: : $mergedColonColonColon")
}
