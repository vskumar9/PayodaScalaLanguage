package Exercise5

import java.io.FileInputStream
import java.util.Properties

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.serialization.{ByteArraySerializer, StringSerializer}

import com.google.protobuf.{DescriptorProtos, Descriptors, DynamicMessage}
import scala.collection.JavaConverters._
import scala.util.Random

object UserEventsProducer {

  def main(args: Array[String]): Unit = {

    val bootstrapServers = "localhost:9092"
    val topic            = "user-events"

    // Path to descriptor + type
    val descriptorFile   = "UserEvent.desc"
    val messageTypeName  = "protobuf.UserEvent"

    // ----- 1) Load & cache descriptor + field descriptors -----
    val descriptor      = loadMessageDescriptor(descriptorFile, messageTypeName)
    val userIdField     = descriptor.findFieldByName("userId")
    val actionField     = descriptor.findFieldByName("action")
    val valueField      = descriptor.findFieldByName("value")

    // ----- 2) Kafka producer configuration (throughput-friendly) -----
    val props = new Properties()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   classOf[StringSerializer].getName)     // key = userId
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[ByteArraySerializer].getName)
    props.put(ProducerConfig.ACKS_CONFIG, "all")
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")       // safer retries
    props.put(ProducerConfig.RETRIES_CONFIG, "3")
    props.put(ProducerConfig.LINGER_MS_CONFIG, "20")                  // small batching window
    props.put(ProducerConfig.BATCH_SIZE_CONFIG, (32 * 1024).toString) // ~32 KB batch
    props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4")          // smaller messages over network

    val producer = new KafkaProducer[String, Array[Byte]](props)

    try {
      // ----- 3) Generate 50+ random events (single Random instance) -----
      val rand    = new Random()
      val actions = IndexedSeq("click", "view", "purchase", "signup", "logout")

      val randomEvents =
        (1 to 50).map { _ =>
          val userId = s"user${rand.nextInt(10)}"           // user0..user9
          val action = actions(rand.nextInt(actions.length))
          val value  = rand.nextDouble() * 500.0            // 0–500
          (userId, action, value)
        }

      // ----- 4) Convert to Protobuf bytes (using cached field descriptors) -----
      val eventsBytes: Seq[(String, Array[Byte])] =
        randomEvents.map { case (userId, action, value) =>
          val bytes = buildUserEventBytes(descriptor, userIdField, actionField, valueField, userId, action, value)
          (userId, bytes)   // (key, value)
        }

      // ----- 5) Send asynchronously -----
      eventsBytes.foreach { case (key, bytes) =>
        val record = new ProducerRecord[String, Array[Byte]](
          topic,
          key,    // key = userId (partitioning by user)
          bytes
        )
        producer.send(record)
      }

      // Ensure all sends complete before exit
      producer.flush()

      println(s"Sent ${eventsBytes.size} UserEvent messages to topic '$topic'")
      println("Sample logical events:")
      randomEvents.take(5).foreach(println)

    } finally {
      producer.close()
    }
  }

  // -----------------------------
  // Loads descriptor from .desc
  // -----------------------------
  private def loadMessageDescriptor(
                                     descPath: String,
                                     fullMessageName: String
                                   ): Descriptors.Descriptor = {

    val in  = new FileInputStream(descPath)
    val set =
      try DescriptorProtos.FileDescriptorSet.parseFrom(in)
      finally in.close()

    val fileDescriptors =
      set.getFileList.asScala.map { fdp =>
        Descriptors.FileDescriptor.buildFrom(
          fdp,
          Array.empty[Descriptors.FileDescriptor]
        )
      }

    fileDescriptors
      .iterator
      .flatMap(fd => fd.getMessageTypes.asScala.iterator)
      .find(_.getFullName == fullMessageName)
      .getOrElse(
        throw new IllegalArgumentException(
          s"Message type '$fullMessageName' not found inside descriptor set '$descPath'"
        )
      )
  }

  // -----------------------------
  // Creates a DynamicMessage
  //  (using cached field descriptors)
  // -----------------------------
  private def buildUserEventBytes(
                                   descriptor: Descriptors.Descriptor,
                                   userIdField: Descriptors.FieldDescriptor,
                                   actionField: Descriptors.FieldDescriptor,
                                   valueField: Descriptors.FieldDescriptor,
                                   userId: String,
                                   action: String,
                                   value: Double
                                 ): Array[Byte] = {

    val builder = DynamicMessage.newBuilder(descriptor)

    builder.setField(userIdField, userId)
    builder.setField(actionField, action)
    builder.setField(valueField, Double.box(value))

    builder.build().toByteArray
  }
}
