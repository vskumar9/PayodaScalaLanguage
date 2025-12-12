package malformed

import org.apache.spark.sql.DataFrame
import java.util.Base64
import org.apache.avro.io.DecoderFactory
import org.apache.avro.generic.{GenericDatumReader, GenericRecord}

import utils.{AppLogging, AvroResources}

/**
 * Handler for malformed Kafka messages detected in the streaming pipeline.
 *
 * This component is designed to:
 * - Count malformed records per micro-batch.
 * - Log a small base64 sample set of the raw payloads for investigation.
 * - Optionally attempt Avro decoding to understand failure patterns.
 *
 * It is typically invoked from a Structured Streaming `foreachBatch` sink
 * wired to the "malformed" side of a valid/malformed split.
 */
object MalformedHandler extends AppLogging {

  /**
   * Shared Avro datum reader instantiated once for efficiency.
   *
   * Uses the canonical schema from [[AvroResources.schema]] to attempt
   * decoding of malformed payloads for diagnostic purposes.
   */
  private val avroDatumReader =
    new GenericDatumReader[GenericRecord](AvroResources.schema)

  /**
   * Per-micro-batch handler for malformed records.
   *
   * The method:
   * - Computes the number of malformed messages in the batch.
   * - Logs up to 5 sample records in base64 form.
   * - Attempts Avro decode for each sample and logs either the decoded record
   *   or the decoding error, helping to identify schema or data issues.
   *
   * @param batchDF DataFrame for the current malformed micro-batch.
   *                Expected to contain a `value` column with the raw bytes.
   * @param batchId Unique batch identifier provided by Spark Structured Streaming.
   */
  def foreachBatch(batchDF: DataFrame, batchId: Long): Unit = {

    val cnt = batchDF.count()

    if (cnt > 0) {
      warn(s"[Malformed] Batch $batchId — malformed messages detected: $cnt")

      // Collect up to 5 sample payloads from the batch.
      val sampleBytes: Array[Array[Byte]] =
        batchDF.select("value").limit(5).collect().flatMap { row =>
          Option(row.getAs[Array[Byte]]("value"))
        }

      // Base64-encode samples to make them log-safe.
      val base64Samples = sampleBytes.map(Base64.getEncoder.encodeToString)

      info(s"[Malformed] Batch $batchId — sample (base64) count: ${base64Samples.length}")

      base64Samples.zipWithIndex.foreach { case (b64, index) =>
        // Log only a prefix of the base64 string to avoid huge log lines.
        info(s"[Malformed] sample[$index] (first 200 chars): ${b64.take(200)}")

        // Attempt Avro decode (optional diagnostic step).
        try {
          val decoder = DecoderFactory.get().binaryDecoder(
            Base64.getDecoder.decode(b64),
            null
          )
          val record = avroDatumReader.read(null, decoder)
          info(s"[Malformed] decoded-sample[$index]: $record")
        } catch {
          case t: Throwable =>
            error(
              s"[Malformed] Failed to decode sample[$index] in batch $batchId: ${t.getMessage}",
              t
            )
        }
      }
    } else {
      debug(s"[Malformed] Batch $batchId — no malformed messages.")
    }
  }
}
