import scala.io.StdIn._

case class Building2DMultiplicationTable() {
  def multiplicationTable(n: Int): List[String] = {
//     for {
//       i <- 1 to n
//       j <- 1 to n
//     } yield s"$i x $j = ${i * j}"

    (1 to n).flatMap(i => (1 to n).map(j => s"$i x $j = ${i * j}"))

  }.toList

}

object Building2DMultiplicationTable {
  def main(args: Array[String]): Unit = {
    val tableBuilder = Building2DMultiplicationTable()
    val n = readLine().toInt // Read size of multiplication table
    val table = tableBuilder.multiplicationTable(n)
    table.foreach(println)
  }
}
