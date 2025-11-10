import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Success, Failure}

def getUser(id: Int): Future[String] =
  Future { s"User$id" }

def getOrders(user: String): Future[List[String]] =
  Future { List(s"$user-order1", s"$user-order2") }

def getOrderTotal(order: String): Future[Double] =
  Future { scala.util.Random.between(10.0, 100.0) }

@main def ChainingDependentFuturesDemo(): Unit = {

  // Blocking
  // for-comprehension version
  val totalF: Future[Double] = for {
    user <- getUser(42)
    orders <- getOrders(user)
    totals <- Future.traverse(orders)(getOrderTotal)
  } yield totals.sum

  val total1 = Await.result(totalF, 5.seconds)
  println(s"Total amount for user 42 (for-comp) = $total1")

  // flatMap version
  val totalF2: Future[Double] =
    getUser(42).flatMap { user =>
      getOrders(user).flatMap { orders =>
        Future.traverse(orders)(getOrderTotal).map(_.sum)
      }
    }

  val total2 = Await.result(totalF2, 5.seconds)
  println(s"Total amount for user 42 (flatMap) = $total2")

  // Non-blocking (callbacks)
  totalF2.onComplete {
    case Success(total) => println(s"Total amount for user 42 = $total")
    case Failure(e)     => println(s"Failed to compute total: ${e.getMessage}")
  }

  // Keep the app alive long enough for the callback (demo-only)
  Await.ready(totalF, 5.seconds)
}
