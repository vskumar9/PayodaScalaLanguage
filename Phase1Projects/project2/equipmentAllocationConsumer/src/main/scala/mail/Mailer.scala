package mail

import jakarta.mail._
import jakarta.mail.internet._

import java.util.Properties
import scala.concurrent.{ExecutionContext, Future}

/**
 * Simple mail delivery helper backed by Jakarta Mail (JavaMail) `Session`.
 *
 * This wrapper provides two asynchronous helpers:
 *  - `send`     : plain-text email
 *  - `sendHtml` : HTML email (sent as "alternative" multipart with an HTML part)
 *
 * Notes:
 *  - Both delivery methods return a `Future[Unit]` that completes when the underlying
 *    `Transport.send` call returns. Failures during sending will fail the returned Future.
 *  - This class requires an implicit `ExecutionContext` for running blocking I/O (`Transport.send`)
 *    in a `Future`. In production, prefer a dedicated blocking dispatcher to avoid starving CPU-bound pools.
 *  - SMTP connection/auth configuration is provided via constructor parameters (`host`, `port`, `user`, `pass`).
 *    The `pass` value is used in a plain `PasswordAuthentication` — treat it as secret.
 *
 * Example:
 * {{{
 * implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global
 * val mailer = new Mailer("smtp.example.com", 587, "noreply@example.com", "s3cr3t")
 * mailer.send("alice@example.com", "Hello", "This is a test").onComplete { ... }
 * }}}
 *
 * @param host SMTP server host (e.g. "smtp.example.com")
 * @param port SMTP server port (e.g. 587)
 * @param user SMTP username (also used as the From address)
 * @param pass SMTP password used for authentication (kept in memory by this instance)
 * @param ec   Implicit ExecutionContext used to run blocking send operations
 */
class Mailer(host: String, port: Int, user: String, pass: String)(implicit ec: ExecutionContext) {

  // SMTP properties used to construct the Jakarta Mail Session
  private val props = new Properties()
  props.put("mail.smtp.auth", "true")
  props.put("mail.smtp.starttls.enable", "true")
  props.put("mail.smtp.host", host)
  props.put("mail.smtp.port", port.toString)

  /**
   * The Jakarta Mail Session used for sending messages.
   *
   * A simple `Authenticator` is provided so the session will authenticate using the supplied
   * username/password when the SMTP server requires it.
   */
  private val session = Session.getInstance(props, new Authenticator {
    override def getPasswordAuthentication: PasswordAuthentication =
      new PasswordAuthentication(user, pass)
  })

  /**
   * Send a plain-text email asynchronously.
   *
   * Behavior:
   *  - Constructs a `MimeMessage` with the supplied `to`, `subject` and plain `body`.
   *  - Calls `Transport.send(message)` inside a `Future` so callers do not block the calling thread.
   *  - The returned `Future` completes successfully when the send finishes, or fails with the
   *    underlying exception (e.g. SMTP connection/auth errors).
   *
   * Important:
   *  - Because `Transport.send` is blocking, run this method on a dedicated blocking ExecutionContext
   *    where possible.
   *
   * @param to      Recipient email address (single recipient).
   * @param subject Mail subject line.
   * @param body    Plain-text body content.
   * @return Future[Unit] completing when send finishes (or fails).
   */
  def send(to: String, subject: String, body: String): Future[Unit] = Future {
    val message = new MimeMessage(session)
    message.setFrom(new InternetAddress(user))
    message.setRecipients(Message.RecipientType.TO, to)
    message.setSubject(subject)
    message.setText(body)

    Transport.send(message)
  }

  /**
   * Send an HTML email asynchronously.
   *
   * Behavior:
   *  - Constructs a `MimeMessage` and a `MimeMultipart("alternative")` containing an HTML part.
   *  - Sets the message content to the multipart and calls `Transport.send`.
   *  - The returned `Future` completes when the send finishes or fails if an exception occurs.
   *
   * Notes:
   *  - This implementation only adds an HTML part. If you need a plain-text alternative part
   *    (recommended for maximum compatibility), extend the multipart with a plain-text `MimeBodyPart`.
   *
   * @param to   Recipient email address (single recipient).
   * @param subject Mail subject line.
   * @param html HTML string content (must be a valid HTML fragment).
   * @return Future[Unit] completing when send finishes (or fails).
   */
  def sendHtml(to: String, subject: String, html: String): Future[Unit] = Future {
    val message = new MimeMessage(session)
    message.setFrom(new InternetAddress(user))
    message.setRecipients(Message.RecipientType.TO, to)
    message.setSubject(subject)

    val multipart = new MimeMultipart("alternative")
    val htmlPart = new MimeBodyPart()
    htmlPart.setContent(html, "text/html; charset=utf-8")

    multipart.addBodyPart(htmlPart)
    message.setContent(multipart)

    Transport.send(message)
  }

}
