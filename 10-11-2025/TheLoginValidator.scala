object TheLoginValidator extends App {
  def validateLogin(
      username: String,
      password: String
  ): Either[String, String] = {
    if (username.isEmpty) Left("Username missing")
    else if (password.isEmpty) Left("Password missing")
    else Right("Login successful")
  }

  println(validateLogin("", "123"))
  println(validateLogin("user", ""))
  println(validateLogin("user", "123"))

}
