object MoodTransformer extends App {
  def moodChanger(prefix: String): String => String = {
    word => s"$prefix-$word-$prefix"
  }

  val happyMood = moodChanger("happy")
  val angryMood = moodChanger("angry")
  println(happyMood("day")) // happy-day-happy
  println(angryMood("crowd"))   // angry-crowd-angry
  println(moodChanger("joyful")("sunshine")) // joyful-sunshine-joyful

}
