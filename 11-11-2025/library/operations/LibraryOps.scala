package library.operations

import library.items._
import library.users._

/** Contains core operations used in the Smart Library Management System.
  *
  * Provides functionality for:
  *   - Borrowing items
  *   - Describing library items
  *   - Converting Strings to Book instances
  *
  * Also defines an implicit default member used when no explicit member is
  * provided.
  */
object LibraryOps {

  /** An implicit default library member used automatically when no member is
    * passed to borrowing operations.
    */
  implicit val defaultMember: Member = new Member("Default Member")

  /** Allows a library member to borrow a specific item.
    *
    * This method requires an implicit [[library.users.Member]]. If none is
    * provided, the `defaultMember` is used.
    *
    * @param item
    *   The library item to be borrowed.
    * @param member
    *   The member borrowing the item (implicit).
    */
  def borrow(item: ItemType)(implicit member: Member): Unit =
    member.borrowItem(item)

  /** Produces a readable description of a given library item.
    *
    * @param item
    *   The item whose description is needed.
    * @return
    *   A string describing the item based on its type.
    */
  def itemDescription(item: ItemType): String = item match {
    case Book(title)     => s"Book: '$title'"
    case Magazine(title) => s"Magazine: '$title'"
    case DVD(title)      => s"DVD: '$title'"
  }

  /** Implicit conversion from a `String` to a `Book`.
    *
    * This enables borrowing or describing items by simply providing a title
    * string—for example: `borrow("The Scala Language")`.
    *
    * @param title
    *   The title of the book.
    * @return
    *   A `Book` instance with the given title.
    */
  implicit def stringToBook(title: String): Book = Book(title)
}
