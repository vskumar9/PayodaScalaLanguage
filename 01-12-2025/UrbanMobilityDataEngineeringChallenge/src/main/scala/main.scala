import java.io._
import scala.util.Random
import java.time._

object UrbanMoveGenerator {

  def main(args: Array[String]): Unit = {

    val outputFile = "urbanmove_trips.csv"
    val file = new PrintWriter(new File(outputFile))

    val areas = Array(
      "MG Road", "Indira Nagar", "Koramangala", "Whitefield",
      "Marathahalli", "HSR Layout", "BTM", "Jayanagar"
    )

    val vehicleTypes = Array("AUTO", "TAXI", "BIKE")
    val paymentMethods = Array("CASH", "UPI", "CARD")
    val rand = new Random()

    def randomDateTime(): LocalDateTime = {
      val now = LocalDateTime.now()
      now.minusMinutes(rand.nextInt(100000))
    }

    def fmt(v: Double): String = f"$v%.2f"

    file.write(
      "tripId,driverId,vehicleType,startTime,endTime,startLocation,endLocation," +
        "distanceKm,fareAmount,paymentMethod,customerRating\n"
    )

    for (i <- 1 to 1000000) {
      val start = randomDateTime()
      val end = start.plusMinutes(rand.nextInt(50) + 5)

      val distance = rand.nextDouble() * 15 + 1
      val fareRate = rand.nextInt(10) + 10
      val fare = distance * fareRate

      file.write(
        s"$i,${rand.nextInt(5000)},${vehicleTypes(rand.nextInt(3))}," +
          s"$start,$end,${areas(rand.nextInt(areas.length))}," +
          s"${areas(rand.nextInt(areas.length))},${fmt(distance)}," +
          s"${fmt(fare)},${paymentMethods(rand.nextInt(3))}," +
          s"${fmt(1 + rand.nextDouble() * 4)}\n"
      )
    }

    file.close()
    println("File generated: " + outputFile)
  }
}
