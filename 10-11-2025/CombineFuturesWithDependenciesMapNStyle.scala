import scala.concurrent._
import scala.concurrent.ExecutionContext

object CombineFuturesWithDependenciesMapNStyle extends App {
  implicit val ec: ExecutionContext = ExecutionContext.Implicits.global

  val f1 = Future { Thread.sleep(1000); 10 }
  val f2 = Future { Thread.sleep(800); 20 }
  val f3 = Future { Thread.sleep(500); 30 }

  // 1: zip + map (cats-style mapN without libraries)
  val combined1: Future[String] =
    f1.zip(f2).zip(f3).map { case ((a, b), c) =>
      val sum = a + b + c
      val avg = sum / 3
      s"Sum = $sum, Average = $avg"
    }
  combined1.foreach(println)

  // 2: Future.sequence + map
  val combined2: Future[String] =
    Future.sequence(List(f1, f2, f3)).map { xs =>
      val sum = xs.sum
      val avg = sum / xs.size
      s"Sum = $sum, Average = $avg"
    }
  combined2.foreach(println)
  Thread.sleep(1500)
}
