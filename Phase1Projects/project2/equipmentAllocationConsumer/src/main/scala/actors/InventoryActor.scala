package actors

import akka.actor.{Actor, ActorLogging, Props}
import play.api.libs.json._
import scala.concurrent.Future
import scala.util.Try
import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter

import models._
import repositories._
import mail.Mailer

/**
 * Factory and messages for InventoryActor.
 *
 * The actor listens for inventory-related events (wrapped as `EquipmentEvent`)
 * and sends notification emails to relevant recipients (assignee, allocatedBy, admins).
 * It performs asynchronous repository lookups and uses the provided Mailer to send
 * HTML emails with a plain-text fallback on failure.
 */
object InventoryActor {

  /**
   * Create Props for the InventoryActor.
   *
   * @param itemRepo repository for equipment items
   * @param allocRepo repository for equipment allocations
   * @param employeeRepo repository for employee lookups
   * @param userRepo repository for user lookups (to locate admin emails)
   * @param emailService mailer used to send HTML/plain emails
   * @return Props to create this actor
   */
  def props(
             itemRepo: EquipmentItemRepository,
             allocRepo: EquipmentAllocationRepository,
             employeeRepo: EmployeeRepository,
             userRepo: UserRepository,
             emailService: Mailer
           ): Props =
    Props(new InventoryActor(itemRepo, allocRepo, employeeRepo, userRepo, emailService))

  /**
   * Message instructing the actor to handle an EquipmentEvent payload.
   *
   * The payload should be a JSON object with at least an `"eventType"` and
   * an identifying field such as `"equipmentId"`, `"equipment_id"` or `"id"`.
   *
   * @param ev the EquipmentEvent containing the JSON payload
   */
  final case class HandleEvent(ev: EquipmentEvent)
}

/**
 * Actor responsible for reacting to inventory events and notifying users.
 *
 * Behaviour summary:
 *  - Receives `InventoryActor.HandleEvent(EquipmentEvent)` messages.
 *  - Recognises `eventType` values such as:
 *      • CREATE, UPDATE, DELETE (equipment item events)
 *      • EQUIPMENT_TYPE_CREATED, EQUIPMENT_TYPE_UPDATED, EQUIPMENT_TYPE_DELETED
 *  - Loads related data (item, allocation, users) and composes short, user-friendly
 *    notification emails. HTML preferred; falls back to plain text upon HTML send failure.
 *  - All I/O is performed asynchronously; errors are logged and recovered to avoid
 *    crashing the actor.
 *
 * @param itemRepo equipment item repository
 * @param allocRepo equipment allocation repository
 * @param employeeRepo employee repository
 * @param userRepo user repository
 * @param emailService mailer used to send messages
 */
class InventoryActor(
                      itemRepo: EquipmentItemRepository,
                      allocRepo: EquipmentAllocationRepository,
                      employeeRepo: EmployeeRepository,
                      userRepo: UserRepository,
                      emailService: Mailer
                    ) extends Actor with ActorLogging {

  import InventoryActor._
  import context.dispatcher

  // ---------- date/time formatter (human-friendly) ----------
  private val displayZone: ZoneId = ZoneId.of("Asia/Kolkata")
  private val displayFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(displayZone)

  private def fmtInstant(i: Instant): String = displayFmt.format(i)
  private def fmtOptInstant(opt: Option[Instant]): String = opt.map(displayFmt.format).getOrElse("N/A")
  private def fmtNullableInstant(i: Instant): String = Option(i).map(displayFmt.format).getOrElse("N/A")

  /**
   * Actor message handling loop.
   *
   * - Logs received payloads for traceability.
   * - Dispatches to handlers depending on the eventType.
   * - Handlers return `Future[Unit]` and are recovered here so exceptions are logged
   *   and do not fail the actor.
   */
  override def receive: Receive = {
    case HandleEvent(ev) =>
      val p = ev.payload
      val payloadEventType = (p \ "eventType").asOpt[String].map(_.trim.toUpperCase)
      log.info(s"[InventoryActor] Received payload eventType=$payloadEventType payload=${safePretty(p)}")

      payloadEventType match {
        case Some(t) if Set("CREATE", "UPDATE", "DELETE").contains(t) =>
          // fire-and-forget, process and log errors internally
          handleEquipmentEvent(t, p).recover { case ex =>
            log.error(ex, s"[InventoryActor] handleEquipmentEvent failed for event=$t payload=${safePretty(p)}")
          }

        case Some(t) if Set("EQUIPMENT_TYPE_CREATED", "EQUIPMENT_TYPE_UPDATED", "EQUIPMENT_TYPE_DELETED").contains(t) =>
          handleEquipmentTypeEvent(t, p).recover { case ex =>
            log.error(ex, s"[InventoryActor] handleEquipmentTypeEvent failed for event=$t payload=${safePretty(p)}")
          }

        case Some(other) =>
          log.warning(s"[InventoryActor] Unhandled inventory eventType=$other payload=${safePretty(p)}")

        case None =>
          log.warning(s"[InventoryActor] Missing eventType inside payload=${safePretty(p)}")
      }

    case other =>
      log.debug(s"[InventoryActor] Ignoring unknown message: $other")
  }

  /**
   * Handle item-level events: CREATE | UPDATE | DELETE.
   *
   * Behaviour:
   *  - Attempts to determine an equipment id from fields `"equipmentId"`, `"equipment_id"`, or `"id"`.
   *  - Loads the EquipmentItem and any active Allocation for that item.
   *  - Resolves recipient emails (Assignee, AllocatedBy, Admins) and deduplicates by email (first-seen wins).
   *  - Prepares short HTML and plain messages and attempts HTML sends with a plain fallback.
   *
   * Errors are logged and recovered; the returned Future completes when all sends complete.
   *
   * @param eventType upper-cased event type (CREATE/UPDATE/DELETE)
   * @param payload the JSON payload for the event
   * @return Future[Unit] completed when processing/sends finish
   */
  private def handleEquipmentEvent(eventType: String, payload: JsObject): Future[Unit] = {
    val equipmentIdOpt = getInt(payload, "equipmentId")
      .orElse(getInt(payload, "equipment_id"))
      .orElse(getInt(payload, "id"))

    equipmentIdOpt match {
      case Some(eid) =>
        val equipF = itemRepo.findById(eid)
        val allocF = allocRepo.findActiveByEquipment(eid)

        // compose combined future -> Future[(Option[EquipmentItem], Option[EquipmentAllocation])]
        val combinedF = for {
          equipOpt <- equipF
          allocOpt <- allocF
        } yield (equipOpt, allocOpt)

        combinedF.flatMap { case (equipOpt, allocOpt) =>
          // find assignee / allocatedBy users (if any)
          val assigneeUserF: Future[Option[User]] = allocOpt match {
            case Some(allocation) =>
              employeeRepo.findById(allocation.employeeId).flatMap {
                case Some(emp) => userRepo.findById(emp.userId)
                case None => Future.successful(None)
              }
            case None => Future.successful(None)
          }

          val allocatedByUserF: Future[Option[User]] = allocOpt match {
            case Some(allocation) =>
              employeeRepo.findById(allocation.allocatedByEmployeeId).flatMap {
                case Some(emp) => userRepo.findById(emp.userId)
                case None => Future.successful(None)
              }
            case None => Future.successful(None)
          }

          val adminsF: Future[Seq[User]] = userRepo.findAll().map(_.filter(u => u.roles.exists(_.equalsIgnoreCase("admin"))))

          for {
            assigneeUserOpt    <- assigneeUserF
            allocatedByUserOpt <- allocatedByUserF
            adminUsers         <- adminsF
            result             <- {
              val baseRecipients = Seq(
                assigneeUserOpt.flatMap(u => Option(u.email)).map(e => ("Assignee", e)),
                allocatedByUserOpt.flatMap(u => Option(u.email)).map(e => ("AllocatedBy", e))
              ).flatten

              val adminRecipients = adminUsers.flatMap(u => Option(u.email).map(e => ("Admin", e)))

              val rawRecipients = baseRecipients ++ adminRecipients

              val recipientsDistinct: Seq[(String, String)] = rawRecipients.foldLeft(Seq.empty[(String, String)]) {
                case (acc, entry @ (_, email)) =>
                  if (acc.exists(_._2 == email)) acc else acc :+ entry
              }

              if (recipientsDistinct.isEmpty) {
                log.info(s"[InventoryActor] No recipient emails found for equipment=$eid event=$eventType payload=${safePretty(payload)}")
                Future.successful(())
              } else {
                val subject = eventType match {
                  case "CREATE" => s"[Inventory] Equipment Created: id=$eid"
                  case "UPDATE" => s"[Inventory] Equipment Updated: id=$eid"
                  case "DELETE" => s"[Inventory] Equipment Deleted: id=$eid"
                  case other    => s"[Inventory] Notification: $other id=$eid"
                }

                val equipmentDesc = equipOpt.map(e => s"${e.assetTag} / SN:${e.serialNumber} (typeId=${e.typeId})")
                  .getOrElse(s"(equipment id=$eid not found)")

                val allocationDesc = allocOpt.map { a =>
                  val assigneeStr = a.employeeId
                  val status = Option(a.status).getOrElse("N/A")
                  s"allocationId=${a.allocationId} assignee=${assigneeStr} status=${status}"
                }.getOrElse("no active allocation")

                val allocatedAtStr = allocOpt.flatMap(a => Option(a.allocatedAt)).map(fmtInstant).getOrElse("N/A")

                val plainLines = Seq(
                  s"EventType : $eventType",
                  s"Equipment : $equipmentDesc",
                  s"Details   : $allocationDesc",
                  s"GeneratedAt: ${fmtInstant(Instant.now())}"
                )
                val plainBody = plainLines.mkString("\n")

                val htmlBody =
                  s"""
                     |<html><body style="font-family:sans-serif;">
                     |  <h3>Inventory Notification</h3>
                     |  <p><strong>Event:</strong> ${escapeHtml(eventType)}</p>
                     |  <p><strong>Equipment:</strong> ${escapeHtml(equipmentDesc)}</p>
                     |  <p><strong>Details:</strong> ${escapeHtml(allocationDesc)}</p>
                     |  <p style="font-size:smaller;color:#666;">GeneratedAt: ${escapeHtml(fmtInstant(Instant.now()))}</p>
                     |</body></html>
                   """.stripMargin

                log.info(s"[InventoryActor] Prepared email -> to=[${recipientsDistinct.map(_._2).mkString(", ")}] subject='$subject' bodyPreview='${plainBody.take(512)}'")

                val sendFs: Seq[Future[Unit]] = recipientsDistinct.map { case (role, email) =>
                  emailService.sendHtml(email, subject, htmlBody).map { _ =>
                    log.info(s"[InventoryActor] Email sent successfully to=$email role=$role equipment=$eid event=$eventType")
                  }.recoverWith { case ex =>
                    log.error(ex, s"[InventoryActor] HTML send failed for $email role=$role; trying plain text")
                    emailService.send(email, subject, plainBody).map { _ =>
                      log.info(s"[InventoryActor] Plain email sent successfully to=$email role=$role equipment=$eid event=$eventType")
                    }.recover { case ex2 =>
                      log.error(ex2, s"[InventoryActor] Plain send failed for $email role=$role equipment=$eid event=$eventType")
                    }
                  }
                }

                Future.sequence(sendFs).map(_ => ())
              }
            }
          } yield result

        }.recover {
          case ex =>
            log.error(ex, s"[InventoryActor] DB error loading equipment/allocation for equipmentId=$eid payload=${safePretty(payload)}")
            ()
        }

      case None =>
        Future.successful(log.warning(s"[InventoryActor] equipment event payload missing equipmentId: payload=${safePretty(payload)}"))
    }
  }


  /**
   * Handle equipment type lifecycle events (created/updated/deleted).
   *
   * Behaviour:
   *  - Finds admin users and sends them a short notification about a change in equipment types.
   *  - If no admins are present, logs and returns successfully.
   *
   * All failures are logged and recovered.
   *
   * @param eventType e.g. EQUIPMENT_TYPE_CREATED, EQUIPMENT_TYPE_UPDATED, EQUIPMENT_TYPE_DELETED
   * @param payload JSON payload for diagnostics (not embedded into email body)
   * @return Future[Unit] completed when all sends finish
   */
  private def handleEquipmentTypeEvent(eventType: String, payload: JsObject): Future[Unit] = {
    userRepo.findAll().flatMap { users =>
      val adminEmails = users.filter(u => u.roles.exists(_.equalsIgnoreCase("admin"))).flatMap(u => Option(u.email)).distinct
      if (adminEmails.isEmpty) {
        log.info(s"[InventoryActor] No admin emails found for equipment-type event=$eventType payload=${safePretty(payload)}")
        Future.successful(())
      } else {
        val subject = eventType match {
          case "EQUIPMENT_TYPE_CREATED" => s"[Inventory] Equipment Type Created"
          case "EQUIPMENT_TYPE_UPDATED" => s"[Inventory] Equipment Type Updated"
          case "EQUIPMENT_TYPE_DELETED" => s"[Inventory] Equipment Type Deleted"
          case other => s"[Inventory] Equipment Type Notification: $other"
        }

        val plain = Seq(
          s"EventType: $eventType",
          s"GeneratedAt: ${fmtInstant(Instant.now())}"
        ).mkString("\n")

        val html =
          s"<html><body style='font-family:sans-serif;'><h3>${escapeHtml(subject)}</h3><pre style='background:#f5f5f5;padding:10px;border-radius:4px;'>${escapeHtml(plain)}</pre></body></html>"

        log.info(s"[InventoryActor] Prepared equipment-type email -> to=[${adminEmails.mkString(", ")}] subject='$subject' bodyPreview='${plain.take(512)}'")

        val sends = adminEmails.map { email =>
          emailService.sendHtml(email, subject, html).map { _ =>
            log.info(s"[InventoryActor] Email sent to admin=$email event=$eventType")
          }.recoverWith { case ex =>
            log.error(ex, s"[InventoryActor] HTML send failed to admin=$email; trying plain")
            emailService.send(email, subject, plain).recover { case ex2 =>
              log.error(ex2, s"[InventoryActor] Plain send failed to admin=$email event=$eventType")
            }
          }
        }

        Future.sequence(sends).map(_ => ())
      }
    }.recover {
      case ex =>
        log.error(ex, s"[InventoryActor] Error fetching admins for equipment-type event=$eventType payload=${safePretty(payload)}")
        ()
    }
  }

  /**
   * Extract an integer from a JSON object field that might be numeric or a string.
   *
   * Supports values like:
   *  - { "equipmentId": 123 }
   *  - { "equipmentId": "123" }
   *
   * @param js JSON object to inspect
   * @param field field name to extract
   * @return Some(parsedInt) or None if missing/invalid
   */
  private def getInt(js: JsObject, field: String): Option[Int] =
    (js \ field).asOpt[JsValue].flatMap {
      case JsNumber(n) => Try(n.toIntExact).toOption
      case JsString(s) => Try(s.trim.toInt).toOption
      case _ => None
    }

  /**
   * Compact and truncate JSON for safe logging.
   *
   * @param js JSON value
   * @param maxLen maximum characters to include (default 1024)
   * @return compact string (possibly truncated)
   */
  private def safePretty(js: JsValue, maxLen: Int = 1024): String = {
    val s = Json.stringify(js)
    if (s.length <= maxLen) s else s.take(maxLen) + "...(truncated)"
  }

  /**
   * Minimal HTML escaping utility to avoid breaking simple HTML templates.
   *
   * Note: This is intentionally lightweight and sufficient for small insertions
   * into the email bodies used above.
   *
   * @param s raw string
   * @return escaped string
   */
  private def escapeHtml(s: String): String =
    Option(s).getOrElse("").replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll("\"", "&quot;")
}
