/**
 * Represents a simulated file download task.
 *
 * Each task runs in its own thread and prints download progress in
 * 10% increments. The download speed determines how long the thread
 * sleeps between progress updates.
 *
 * @param fileName      Name of the file being downloaded.
 * @param downloadSpeed Delay (in milliseconds) per 10% progress chunk.
 */
case class DownloadTask(fileName: String, downloadSpeed: Int) extends Runnable {

  /**
   * Executes the simulated download.
   *
   * The method:
   *  - Iterates from 10% to 100% in steps of 10%
   *  - Sleeps for `downloadSpeed` ms between each update
   *  - Prints progress and a final completion message
   */
  override def run(): Unit =
    for percent <- 10 to 100 by 10 do
      Thread.sleep(downloadSpeed)
      println(s"$fileName: $percent% downloaded")
    println(s"$fileName download completed!\n")
}

/**
 * Demonstrates concurrent file downloads using multiple threads.
 *
 * Features:
 *  - Creates several `DownloadTask` instances
 *  - Runs each task in its own thread
 *  - Assigns different thread priorities
 *  - Starts threads concurrently
 *  - Waits for all downloads to finish using `join()`
 */
object FileDownloadProgressSimulator:

  /**
   * Entry point of the simulation.
   *
   * @param args Command-line arguments (unused)
   */
  def main(args: Array[String]): Unit =
    println("Starting file downloads...\n")

    // Create multiple download threads
    val file1 = new Thread(new DownloadTask("Movie.mp4", 300))
    val file2 = new Thread(new DownloadTask("Music.mp3", 200))
    val file3 = new Thread(new DownloadTask("Document.pdf", 500))
    val file4 = new Thread(new DownloadTask("GameInstaller.exe", 150))

    // Setting priority for threads
    file1.setPriority(Thread.NORM_PRIORITY)
    file2.setPriority(Thread.NORM_PRIORITY)
    file3.setPriority(Thread.MAX_PRIORITY)
    file4.setPriority(Thread.MIN_PRIORITY)

    // Start all threads (run concurrently)
    file1.start()
    file2.start()
    file3.start()
    file4.start()

    // Wait for all threads to complete before exiting
    file1.join()
    file2.join()
    file3.join()
    file4.join()

    println("All downloads completed!")
