import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object FutureSequencingParallelVSSequential {

  def task(name: String, delay: Int): Future[String] = Future {
    Thread.sleep(delay)
    s"$name done"
  }

  def time[A](label: String)(block: => Future[A]): Unit = {
    val start = System.currentTimeMillis()
    val result = Await.result(block, 10.seconds)
    val end = System.currentTimeMillis()
    println(s"[$label] Result: $result")
    println(s"[$label] Time taken: ${end - start} ms\n")
  }

  @main def run(): Unit = {

    println("Starting sequential analysis...")

    // 1. Sequential execution
    time("Sequential") {
      task("A", 1000).flatMap { a =>
        task("B", 1500).flatMap { b =>
          task("C", 1200).map { c =>
            List(a, b, c)
          }
        }
      }
    }

    println("Starting parallel analysis...")

    // 2. Parallel execution
    time("Parallel") {
      Future.sequence(List(
        task("A2", 1000),
        task("B2", 1500),
        task("C2", 1200)
      ))
    }
  }
}
