object ImplicitOrderingWithCustomOperator extends App {
  case class Person(name: String, age: Int)

  implicit class PersonOrdering(p: Person) {
    def <(other: Person): Boolean = p.age < other.age
    def >(other: Person): Boolean = p.age > other.age
    def <=(other: Person): Boolean = p.age <= other.age
    def >=(other: Person): Boolean = p.age >= other.age
  }

  val p1 = Person("Ravi", 25)
  val p2 = Person("Meena", 30)

  println(p1 < p2) // true
  println(p1 >= p2) // false

  // shows normal if-condition usage
  if (p1 < p2) {
    println("Meena is older")
  }
}
