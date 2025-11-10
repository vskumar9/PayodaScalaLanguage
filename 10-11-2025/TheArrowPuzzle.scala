object `The->ArrowPuzzle` extends App {
  val animals = Map(
    "dog" -> "bark",
    "cat" -> "meow",
    "cow" -> "moo"
  )

  // 1. Add lion
  val updatedAnimals = animals + ("lion" -> "roar")

  // 2. Retrieve cow sound
  println("Cow sound: " + updatedAnimals("cow"))

  // 3. Safe lookup for tiger
  println("Tiger sound: " + updatedAnimals.getOrElse("tiger", "Unknown animal"))

}
