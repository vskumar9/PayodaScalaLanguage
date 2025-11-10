object StringEnrichmentViaImplicitClassPlusOperator extends App {

  implicit class RichString(private val s: String) {
    def *(n: Int): String =
      if (n <= 0) "" else List.fill(n)(s).mkString
      // new StringOps(s).*(n)

    def ~(other: String): String = s + " " + other
  }

  println("Hi" * 3)          // HiHiHi
  println("Hello" ~ "World")  // Hello World
}
