object CollectionsPartialFunctionsSelectiveTransformation extends App {
  val items = List(1, "apple", 3.5, "banana", 42)

  val doubledInts = items.collect { case i: Int =>
    i * 2
  }

  println(doubledInts)
}
