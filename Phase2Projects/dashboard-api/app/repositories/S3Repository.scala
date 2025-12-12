package repositories

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._

import play.api.libs.json._
import play.api.{Configuration, Logger}

import javax.inject.Inject

import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model._

import java.util.concurrent.CompletableFuture
import java.nio.file.{Files, Path}
import java.util.UUID

import com.github.mjakubowski84.parquet4s.{ParquetReader, Path => P4SPath}
import model.{DailySummary, Event}
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.{Instant, LocalDate, LocalTime, ZoneOffset}


/**
 * Repository for reading Parquet files from S3 lakehouse using AWS SDK v2 async client.
 *
 * Supports two data partitions:
 * - `lake/txn_summary/date=YYYY-MM-DD/` - Daily customer transaction aggregates
 * - `lake/events/event_date=YYYY-MM-DD/` - Behavioral event streams
 *
 * Downloads Parquet files to temp files, reads with parquet4s, converts to Play JSON.
 * Handles pagination, temp file cleanup, and Parquet INT96 timestamp decoding.
 */
class S3Repository @Inject() (
                               s3: S3AsyncClient,
                               config: Configuration
                             )(implicit ec: ExecutionContext) {

  /** Logger for S3 operations and Parquet reading. */
  private val logger = Logger(this.getClass)

  /** S3 bucket name for lakehouse data. */
  private val bucket: String               = config.get[String]("keyspaces.s3Bucket")

  /** Base prefix for daily transaction summary partitions. */
  private val lakeTxnSummaryPrefix: String = config.get[String]("keyspaces.lakeTxnSummaryPrefix")

  /** Base prefix for behavioral events partitions. */
  private val lakeEventsPrefix: String     = config.get[String]("keyspaces.lakeEventsPrefix")

  // ---------- list objects (handles pagination) ----------
  /**
   * Lists all S3 object keys under a prefix with automatic pagination.
   *
   * @param prefix S3 prefix path (e.g. "lake/txn_summary/date=2025-12-10/")
   * @return Future of all matching object keys
   */
  private def listKeys(prefix: String): Future[Seq[String]] = {
    val p   = Promise[Seq[String]]()
    val acc = scala.collection.mutable.ArrayBuffer.empty[String]

    def request(token: Option[String]): Unit = {
      val b = ListObjectsV2Request.builder()
        .bucket(bucket)
        .prefix(prefix)
      token.foreach(b.continuationToken)
      val req = b.build()
      val cf: CompletableFuture[ListObjectsV2Response] = s3.listObjectsV2(req)

      cf.handle[Unit] { (resp: ListObjectsV2Response, err: Throwable) =>
        if (err != null) {
          p.tryFailure(err)
        } else {
          acc ++= resp.contents().asScala.map(_.key())
          if (resp.isTruncated) request(Some(resp.nextContinuationToken()))
          else p.trySuccess(acc.toSeq)
        }
        ()
      }
      ()
    }

    try request(None)
    catch { case t: Throwable => p.failure(t) }

    p.future
  }

  // ---------- prefix helpers ----------
  /**
   * Constructs Hive-style partition path for daily summaries.
   * e.g. "lake/txn_summary/" → "lake/txn_summary/date=2025-12-10/"
   */
  private def datePartitionPrefix(basePrefix: String, date: String): String = {
    val base = if (basePrefix.endsWith("/")) basePrefix else basePrefix + "/"
    s"$base" + s"date=$date/"
  }

  /**
   * Constructs Hive-style partition path for events.
   * e.g. "lake/events/" → "lake/events/event_date=2025-12-10/"
   */
  private def eventsDatePartitionPrefix(basePrefix: String, date: String): String = {
    val base = if (basePrefix.endsWith("/")) basePrefix else basePrefix + "/"
    s"${base}event_date=$date/"
  }

  // ---------- read full object as bytes ----------
  /**
   * Downloads complete S3 object as byte array using async client.
   *
   * @param key Full S3 object key
   * @return Future containing raw bytes
   */
  private def readObjectBytes(key: String): Future[Array[Byte]] = {
    val p = Promise[Array[Byte]]()

    val req = GetObjectRequest.builder()
      .bucket(bucket)
      .key(key)
      .build()

    val cf: CompletableFuture[ResponseBytes[GetObjectResponse]] =
      s3.getObject(req, AsyncResponseTransformer.toBytes[GetObjectResponse]())

    cf.handle[Unit] { (respBytes: ResponseBytes[GetObjectResponse], err: Throwable) =>
      if (err != null) p.tryFailure(err)
      else p.trySuccess(respBytes.asByteArray())
      ()
    }

    p.future
  }

  /**
   * Writes raw Parquet bytes to a temporary file for parquet4s processing.
   *
   * Files are automatically cleaned up in `finally` blocks after reading.
   *
   * @param bytes Raw Parquet file contents from S3
   * @return Temporary Path object for parquet4s
   */
  private def writeTempParquet(bytes: Array[Byte]): Path = {
    val tmp = Files.createTempFile("summary-" + UUID.randomUUID().toString, ".parquet")
    Files.write(tmp, bytes)
    tmp
  }

  /**
   * Retrieves daily transaction summary for a specific customer.
   *
   * Scans `lake/txn_summary/date=YYYY-MM-DD/` partition, filters by customer_id,
   * returns first matching `DailySummary` as JSON or None if not found.
   *
   * @param date       Summary date (YYYY-MM-DD)
   * @param customerId Customer identifier
   * @return Future[Option[JsValue]] containing summary JSON or None
   */
  def getSummary(date: String, customerId: Long): Future[Option[JsValue]] = {
    val prefix = datePartitionPrefix(lakeTxnSummaryPrefix, date)

    listKeys(prefix).flatMap { keys =>
      if (keys.isEmpty) Future.successful(None)
      else {
        Future
          .traverse(keys) { key =>
            readObjectBytes(key).map(bytes => writeTempParquet(bytes))
          }
          .map { paths =>
            try {
              val cid = customerId.toInt

              implicit val options: ParquetReader.Options =
                ParquetReader.Options() // no custom projection

              val rows: Seq[DailySummary] =
                paths.flatMap { nioPath =>
                  val p4sPath: P4SPath = P4SPath(nioPath)

                  // IMPORTANT: use builder API, not ParquetReader.read
                  ParquetReader
                    .as[DailySummary]        // derive schema from case class
                    .options(options)
                    .read(p4sPath)
                    .toSeq
                }.filter(_.customer_id == cid)

              rows.headOption.map { r =>
                Json.obj(
                  "date"              -> date,
                  "customer_id"       -> r.customer_id,
                  "total_amount"      -> r.total_amount,
                  "total_items"       -> r.total_items,
                  "distinct_products" -> r.distinct_products,
                  "top_category"      -> r.top_category
                )
              }
            } finally {
              paths.foreach(p => try Files.deleteIfExists(p) catch { case _: Throwable => () })
            }
          }
      }
    }.recover { ex =>
      logger.error(s"getSummary failed for prefix=$prefix customerId=$customerId", ex)
      None
    }
  }

  /**
   * Retrieves customer behavioral events across multiple dates with row limit.
   *
   * Scans `lake/events/event_date=YYYY-MM-DD/` partitions sequentially until
   * `limit` events are collected or all dates exhausted. Skips failed days.
   *
   * @param dates      Date range to scan (YYYY-MM-DD format)
   * @param customerId Customer identifier to filter
   * @param limit      Maximum events to return
   * @return Future[Seq[JsValue]] containing event JSONs (up to limit)
   */
  def getEvents(dates: Seq[String], customerId: Long, limit: Int): Future[Seq[JsValue]] = {
    val acc = scala.collection.mutable.ArrayBuffer.empty[JsValue]

    implicit val options: ParquetReader.Options = ParquetReader.Options()

    def loop(idx: Int): Future[Seq[JsValue]] = {
      if (acc.size >= limit || idx >= dates.length) {
        Future.successful(acc.take(limit).toSeq)
      } else {
        val date   = dates(idx)
        val prefix = eventsDatePartitionPrefix(lakeEventsPrefix, date)

        listKeys(prefix).flatMap { keys =>
          // download and read until we fill 'limit' or exhaust keys
          Future
            .traverse(keys) { key =>
              readObjectBytes(key).map(bytes => writeTempParquet(bytes))
            }
            .map { paths =>
              try {
                val cid = customerId

                val rows: Seq[Event] =
                  paths.flatMap { nioPath =>
                    val p4sPath: P4SPath = P4SPath(nioPath)
                    ParquetReader
                      .as[Event]
                      .options(options)
                      .read(p4sPath)
                      .toSeq
                  }.filter(_.customer_id == customerId.toInt)

                val remaining = limit - acc.size
                val takeRows  = rows.take(remaining)

                acc ++= takeRows.map { e =>
                  Json.obj(
                    "date"                -> date,
                    "event_id"            -> e.event_id,
                    "customer_id"         -> e.customer_id,
                    "event_type"          -> e.event_type,
                    "product_id"          -> e.product_id,
                    "event_timestamp_hex" -> int96ToIsoString(e.event_timestamp),
                    "ingestion_timestamp_hex" -> int96ToIsoString(e.ingestion_timestamp)
                  )
                }
              } finally {
                // cleanup temp files
                // ignore failures
                paths.foreach(p => try Files.deleteIfExists(p) catch { case _: Throwable => () })
              }

              if (acc.size >= limit) acc.take(limit).toSeq
              else Seq.empty[JsValue]
            }
            .flatMap { res =>
              if (res.nonEmpty) Future.successful(res)
              else loop(idx + 1)
            }
        }.recoverWith { case ex =>
          logger.warn(s"getEvents: S3 events read failed for prefix=$prefix ; skipping day. Cause: ${ex.getMessage}")
          loop(idx + 1)
        }
      }
    }

    loop(0).recover { case ex =>
      logger.error(s"getEvents failed customerId=$customerId dates=$dates", ex)
      Seq.empty
    }
  }

  /**
   * Decodes Parquet INT96 timestamp (12-byte binary) to java.time.Instant.
   *
   * INT96 format (little-endian):
   * - bytes 0-7: nanoseconds since midnight
   * - bytes 8-11: Julian day number
   *
   * @param bytes 12-byte INT96 timestamp
   * @return Instant in UTC
   * @throws IllegalArgumentException if not exactly 12 bytes
   */
  private def int96ToInstant(bytes: Array[Byte]): Instant = {
    require(bytes.length == 12, s"INT96 must be 12 bytes, got ${bytes.length}")

    val buf = ByteBuffer.wrap(bytes.clone()).order(ByteOrder.LITTLE_ENDIAN)

    // little-endian: 8 bytes nanos, then 4 bytes julian day
    val nanosOfDay = buf.getLong(0)
    val julianDay  = buf.getInt(8)

    // Julian day to LocalDate: 2440588 is 1970-01-01
    val epochDay = julianDay - 2440588L
    val date     = LocalDate.ofEpochDay(epochDay)

    val secs  = nanosOfDay / 1_000_000_000L
    val nanos = (nanosOfDay % 1_000_000_000L).toInt
    val time  = LocalTime.ofSecondOfDay(secs).withNano(nanos)

    val dt = date.atTime(time)
    dt.toInstant(ZoneOffset.UTC)
  }

  /**
   * Converts INT96 timestamp bytes to ISO-8601 string.
   *
   * @param bytes INT96 timestamp bytes
   * @return ISO string like "2025-12-09T12:34:56.789Z"
   */
  private def int96ToIsoString(bytes: Array[Byte]): String =
    int96ToInstant(bytes).toString  // e.g. "2025-12-09T12:34:56.789Z"

  /**
   * Retrieves all daily transaction summaries for a date (limited).
   *
   * Scans entire `lake/txn_summary/date=YYYY-MM-DD/` partition and returns
   * up to `limit` summaries across all customers.
   *
   * @param date  Summary date (YYYY-MM-DD)
   * @param limit Maximum summaries to return
   * @return Future[Seq[JsValue]] containing summary JSONs (up to limit)
   */
  def getDailySummaries(date: String, limit: Int): Future[Seq[JsValue]] = {
    val prefix = datePartitionPrefix(lakeTxnSummaryPrefix, date)

    listKeys(prefix).flatMap { keys =>
      if (keys.isEmpty) Future.successful(Seq.empty)
      else {
        Future
          .traverse(keys) { key =>
            readObjectBytes(key).map(bytes => writeTempParquet(bytes))
          }
          .map { paths =>
            try {
              implicit val options: ParquetReader.Options = ParquetReader.Options()

              val rowsIter: Iterator[DailySummary] =
                paths.iterator.flatMap { nioPath =>
                  val p4sPath: P4SPath = P4SPath(nioPath)
                  ParquetReader
                    .as[DailySummary]
                    .options(options)
                    .read(p4sPath)
                    .iterator
                }

              rowsIter
                .take(limit)
                .map { r =>
                  Json.obj(
                    "date"              -> date,
                    "customer_id"       -> r.customer_id,
                    "total_amount"      -> r.total_amount,
                    "total_items"       -> r.total_items,
                    "distinct_products" -> r.distinct_products,
                    "top_category"      -> r.top_category
                  )
                }
                .toSeq
            } finally {
              paths.foreach(p => try Files.deleteIfExists(p) catch { case _: Throwable => () })
            }
          }
      }
    }.recover { ex =>
      logger.error(s"getDailySummaries failed for prefix=$prefix", ex)
      Seq.empty
    }
  }
}
