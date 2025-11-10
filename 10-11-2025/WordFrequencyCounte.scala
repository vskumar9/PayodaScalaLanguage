object WordFrequencyCounte extends App {
  val lines = List(
    "Scala is powerful",
    "Scala is concise",
    "Functional programming is powerful"
  )

// 1) Flatten words via for-comprehension
  val words = for {
    line <- lines
    word <- line.split("\\s+")
  } yield word

// 2) Group and count
  val freq1: Map[String, Int] =
    words.groupBy(identity).view.mapValues(_.size).toMap

// Alternative approach using functional methods
  val freq2 = lines
    .flatMap(_.split("\\s+"))
    .groupBy(identity)
    .view
    .mapValues(_.size)
    .toMap

  // Print results
  println(freq1)
  println(freq2)
  // Map(Scala -> 2, is -> 3, powerful -> 2, concise -> 1, Functional -> 1, programming -> 1)

}
