import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

object CoordinatedRetryAsynchronousResilience extends App {

  def fetchDataFromServer(
      server: String
  )(implicit ec: ExecutionContext): Future[String] = Future {
    if (Random.nextBoolean())
      throw new RuntimeException(s"Failed to fetch from $server")
    else s"Data from $server"
  }

  def fetchWithRetry(server: String, maxRetries: Int)(implicit
      ec: ExecutionContext
  ): Future[String] = {
    fetchDataFromServer(server).recoverWith {
      case _ if maxRetries > 0 =>
        println(s"Retrying... remaining attempts: $maxRetries")
        fetchWithRetry(server, maxRetries - 1)
    }
  }

  fetchWithRetry("Server-1", 3).onComplete(println)

}
