case class SmartTemperatureConverter() {
  // def convertTemp(value: Double, scale: String): Double = {
  //   if (scale.equalsIgnoreCase("f")) {
  //     (value - 32.0) * 5.0 / 9.0
  //   } else if (scale.equalsIgnoreCase("c")) {
  //     value * 9.0 / 5.0 + 32.0
  //   } else {
  //     value
  //   }
  // }

  def convertTemp(value: Double, scale: String): Double = if (
    scale.equalsIgnoreCase("f")
  ) {
    (value - 32.0) * 5.0 / 9.0
  } else if (scale.equalsIgnoreCase("c")) {
    value * 9.0 / 5.0 + 32.0
  } else {
    value
  }
}

object SmartTemperatureConverterApp extends App {
  val converter = SmartTemperatureConverter()

  println(converter.convertTemp(0, "C")) // 32.0
  println(converter.convertTemp(212, "F")) // 100.0
  println(converter.convertTemp(50, "X")) // 50.0
}
