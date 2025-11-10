object MapFlatMapCompositionCleanDataPipeline extends App {
  val data = List("10", "20", "x", "30")

  val result = data
    .map(x => scala.util.Try(x.toInt))
    .flatMap(_.toOption)
    .map(n => n * n)

  println(result) // List(100, 400, 900)
}
