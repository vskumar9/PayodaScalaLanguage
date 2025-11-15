import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat

case class Person(sno: Int, name: String, city: String)

object JsonFormats {
  implicit val personFormat: RootJsonFormat[Person] = jsonFormat3(Person)
}
