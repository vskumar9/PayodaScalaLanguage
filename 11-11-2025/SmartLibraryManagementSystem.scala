import library.items._
import library.users._
import library.operations.LibraryOps._

/**
 * Entry point for the Smart Library Management System.
 *
 * Demonstrates various library operations such as borrowing books,
 * magazines, and DVDs, as well as working with members and item descriptions.
 *
 * This object relies on:
 *  - `library.items` for item types like Book, Magazine, DVD
 *  - `library.users` for user types such as Member
 *  - `library.operations.LibraryOps` for borrowing logic and item utilities
 */
object SmartLibraryManagementSystem extends App {

  /** Example: Borrowing a book using an implicit default member. */
  borrow("The Scala Language")

  /** Creates a magazine instance and borrows it. */
  val mag = Magazine("Tech Monthly")
  borrow(mag)

  /** Creates a DVD instance and borrows it. */
  val dvd = DVD("Interstellar")

  /** Creates a library member explicitly. */
  val member: Member = new Member("Alice")

  /** Borrow a DVD using the default implicit member from scope. */
  borrow(dvd)

  /** Borrow a book using an explicitly provided Member instance. */
  borrow(Book("Harry Potter"))(using member)

  /** Prints a description of a DVD item. */
  println(itemDescription(dvd))

  /** Prints a description for a book title provided as a String. */
  println(itemDescription("Functional Programming with Scala"))
}
