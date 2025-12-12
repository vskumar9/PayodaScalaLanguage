package events.config

import com.typesafe.config.ConfigFactory

case class KafkaConfig(
                        topic: String,
                        bootstrap: String,
                        clientId: String
                      )

case class ProducerConfig(
                           eventsPerSecond: Int,
                           debug: Boolean,
                           acks: String,
                           lingerMs: Int,
                           batchSizeBytes: Int,
                           clientId: String
                         )

case class AvroConfig(
                       schemaFile: String
                     )

case class AppConfig(
                      kafka: KafkaConfig,
                      producer: ProducerConfig,
                      avro: AvroConfig
                    )

object ConfigLoader {
  def load(): AppConfig = {
    val config = ConfigFactory.load()

    val kafkaCfg = config.getConfig("app.kafka")
    val producerCfg = config.getConfig("app.producer")
    val avroCfg = config.getConfig("app.avro")

    AppConfig(
      kafka = KafkaConfig(
        topic     = kafkaCfg.getString("topic"),
        bootstrap = kafkaCfg.getString("bootstrap"),
        clientId  = kafkaCfg.getString("client-id")
      ),
      producer = ProducerConfig(
        eventsPerSecond = producerCfg.getInt("events-per-second"),
        debug           = producerCfg.getBoolean("debug"),
        acks            = producerCfg.getString("acks"),
        lingerMs        = producerCfg.getInt("linger-ms"),
        batchSizeBytes  = producerCfg.getInt("batch-size-bytes"),
        clientId        = producerCfg.getString("client-id")
      ),
      avro = AvroConfig(
        schemaFile = avroCfg.getString("schema-file")
      )
    )
  }
}
