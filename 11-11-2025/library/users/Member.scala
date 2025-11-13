package library.users

import library.items.ItemType

/** Represents a library member who can borrow library items.
  *
  * @param name
  *   The name of the member.
  */
class Member(val name: String) {

  /** Allows the member to borrow a specified library item.
    *
    * Prints a message indicating which item the member has borrowed.
    *
    * @param item
    *   The library item being borrowed.
    */
  def borrowItem(item: ItemType): Unit = {
    println(s"$name borrowed '${item.title}'")
  }
}
