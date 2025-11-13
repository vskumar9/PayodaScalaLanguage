package library.items

/** Represents a general type of library item.
  *
  * All items in the library—such as books, magazines, and DVDs—extend this
  * trait. Each item type must define a `title` describing the item.
  */
sealed trait ItemType {

  /** The title or name of the library item. */
  def title: String
}

/** Represents a book available in the library.
  *
  * @param title
  *   The title of the book.
  */
case class Book(title: String) extends ItemType

/** Represents a magazine available in the library.
  *
  * @param title
  *   The title of the magazine.
  */
case class Magazine(title: String) extends ItemType

/** Represents a DVD available in the library.
  *
  * @param title
  *   The title of the DVD.
  */
case class DVD(title: String) extends ItemType
