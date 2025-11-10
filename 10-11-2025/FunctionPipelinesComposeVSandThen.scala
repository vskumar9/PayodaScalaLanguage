object FunctionPipelinesComposeVSandThen extends App {
  val trim: String => String = _.trim
  val toInt: String => Int = _.toInt
  val doubleIt: Int => Int = _ * 2

  val pipeline1 = trim andThen toInt andThen doubleIt
  val pipeline2 = doubleIt compose toInt compose trim

  println(pipeline1(" 21 ")) // 42
  println(pipeline2(" 40 ")) // 80
}
