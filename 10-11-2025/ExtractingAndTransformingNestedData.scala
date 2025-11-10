object ExtractingAndTransformingNestedData extends App {

  val departments = List(
    ("IT", List("Ravi", "Meena")),
    ("HR", List("Anita")),
    ("Finance", List("Vijay", "Kiran"))
  )

  // Method 1: For-comprehension
  val result1 = for {
    (dept, employees) <- departments
    emp <- employees
  } yield s"$dept: $emp"

  println("Result using for-comprehension:")
  result1.foreach(println)

  // Method 2: flatMap + map
  val result2 = departments.flatMap { case (dept, employees) =>
    employees.map(emp => s"$dept: $emp")
  }

  println("\nResult using flatMap + map:")
  result2.foreach(println)
}
