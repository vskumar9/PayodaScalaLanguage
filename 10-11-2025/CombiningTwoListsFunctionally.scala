case class CombiningTwoListsFunctionally() {
  def CartesianProduct1(
      students: List[String],
      subjects: List[String]
  ): List[(String, String)] = {
    for {
      s <- students
      sub <- subjects
      if s.length >= sub.length
    } yield (s, sub)
  }

  def CartesianProduct2(
      students: List[String],
      subjects: List[String]
  ): List[(String, String)] = {
    students.flatMap(s =>
      subjects
        .withFilter(sub => s.length >= sub.length)
        .map(sub => (s, sub))
    )
  }
}

object CombiningTwoListsFunctionally {
  def main(args: Array[String]): Unit = {
    val combiner = CombiningTwoListsFunctionally()
    val students = List("Asha", "Bala", "Chitra")
    val subjects = List("Math", "Physics")
    val result = combiner.CartesianProduct1(students, subjects)
    println("Cartesian Product of Students and Subjects:")
    result.foreach(println)

    val functionalResult = combiner.CartesianProduct2(students, subjects)
    println("Cartesian Product: 2 Approach:")
    functionalResult.foreach(println)
  }
}
