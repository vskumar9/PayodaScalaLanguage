import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

def riskyOperation(): Future[Int] = Future {
  val n = scala.util.Random.nextInt(3)
  if (n == 0) throw new RuntimeException("Failed!")
  n
}

@main def SafeCompositionRecoverDemo(): Unit = {
  // 1) recover: fallback VALUE -1
  val f1: Future[Int] =
    riskyOperation().recover { case _: Throwable => -1 }

  val r1 = Await.result(f1, 10.seconds)
  println(s"recover result = $r1")

  // 2) recoverWith: fallback FUTURE (retry once), then final fallback -1
  val f2: Future[Int] =
    riskyOperation()
      .recoverWith { case _: Throwable => riskyOperation() } // retry once
      .recover { case _: Throwable => -1 }                   // final fallback

  val r2 = Await.result(f2, 20.seconds)
  println(s"recoverWith (retry once) result = $r2")
}
