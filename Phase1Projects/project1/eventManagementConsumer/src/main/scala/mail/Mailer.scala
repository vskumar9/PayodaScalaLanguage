package mail

import jakarta.mail._
import jakarta.mail.internet._
import java.util.Properties
import scala.concurrent.{ExecutionContext, Future}

/**
 * Simple SMTP mailer utility that sends plain-text and HTML emails using Jakarta Mail.
 *
 * This class is intentionally minimal and wraps blocking Jakarta Mail calls inside
 * `Future { ... }` so they run on the provided `ExecutionContext`. Callers should
 * provide an appropriate execution context (e.g., a dedicated thread pool for blocking IO)
 * to avoid starving default thread pools.
 *
 * Example usage:
 * {{{
 * implicit val ec: ExecutionContext = ... // a blocking IO context
 * val mailer = new Mailer("smtp.example.com", 587, "no-reply@example.com", "smtppassword")
 * mailer.sendHtml("user@example.com", "Subject", "<p>Hello</p>")
 * }}}
 *
 * Notes:
 *  - The underlying Jakarta Mail API may throw `MessagingException` (and subclasses).
 *    These exceptions will be captured inside the returned `Future` (i.e., the Future
 *    will be failed with that exception).
 *  - Because Jakarta Mail operations are blocking, pass an ExecutionContext intended for
 *    blocking IO (e.g., a separate thread pool) when constructing this class.
 *
 * @param host SMTP hostname (e.g. "smtp.gmail.com")
 * @param port SMTP port (e.g. 587)
 * @param user SMTP username / from address
 * @param pass SMTP password
 * @param ec   ExecutionContext used to run blocking mail operations
 */
class Mailer(host: String, port: Int, user: String, pass: String)(implicit ec: ExecutionContext) {

  // Jakarta Mail properties configured for typical STARTTLS SMTP.
  private val props = new Properties()
  props.put("mail.smtp.auth", "true")
  props.put("mail.smtp.starttls.enable", "true")
  props.put("mail.smtp.host", host)
  props.put("mail.smtp.port", port.toString)

  /**
   * Mail session created with an authenticator that supplies credentials.
   *
   * The session is reused for subsequent sends. The Jakarta Mail `Transport.send`
   * call will open and close a connection for each send (unless a transport is
   * explicitly reused).
   */
  private val session = Session.getInstance(props, new Authenticator {
    override def getPasswordAuthentication: PasswordAuthentication =
      new PasswordAuthentication(user, pass)
  })

  /**
   * Send a plain-text email.
   *
   * The operation is performed asynchronously inside a Future. On success the Future
   * completes with Unit. On failure the Future fails with the thrown exception
   * (commonly a subclass of MessagingException).
   *
   * Important: the call to Transport.send is blocking, so supply a blocking-capable
   * ExecutionContext when constructing this Mailer.
   *
   * @param to      recipient e-mail address (single recipient)
   * @param subject e-mail subject line
   * @param body    plain-text body
   * @return Future[Unit] completing when the send operation finishes or fails
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
   * Send an HTML email (multipart/alternative with an HTML part).
   *
   * This method builds a multipart message containing an HTML part and sends it.
   * Like [[send]], the operation is executed asynchronously and will capture any
   * thrown exceptions inside the returned Future.
   *
   * @param to      recipient e-mail address (single recipient)
   * @param subject e-mail subject line
   * @param html    HTML content for the message (UTF-8)
   * @return Future[Unit] completing when the send operation finishes or fails
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
