package modules

import com.google.inject.{AbstractModule, Provides, Singleton}
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import com.datastax.oss.driver.api.core.auth.ProgrammaticPlainTextAuthProvider
import play.api.Configuration
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient

import java.net.InetSocketAddress
import javax.net.ssl.SSLContext
import com.redis.RedisClient
import cache.RedisCache
import scala.concurrent.ExecutionContext
/**
 * Guice dependency injection module for infrastructure dependencies.
 *
 * Provides singleton instances of:
 * - Amazon Keyspaces (Cassandra) CqlSession
 * - AWS S3 AsyncClient for lakehouse Parquet access
 *
 * Configuration is loaded from Play application.conf under `keyspaces.*`.
 */
class InfrastructureModule extends AbstractModule {

  /**
   * Empty configure method - all bindings via @Provides methods.
   */
  override def configure(): Unit = ()

  /**
   * Creates a singleton CqlSession connected to Amazon Keyspaces (serverless Cassandra).
   *
   * Configuration properties:
   * - `keyspaces.host`     - Cassandra endpoint (unused with region-based discovery)
   * - `keyspaces.port`     - Port (unused with region-based discovery)
   * - `keyspaces.region`   - AWS region (used as local datacenter)
   * - `keyspaces.username` - SigV4 IAM auth username
   * - `keyspaces.password` - IAM auth password/secret
   * - `keyspaces.keyspace` - Target keyspace name
   *
   * Uses `keyspaces.conf` driver config from classpath and system SSL context.
   *
   * @param config Play application configuration
   * @return Singleton CqlSession instance
   */
  @Provides
  @Singleton
  def provideCqlSession(config: Configuration): CqlSession = {
    val host     = config.get[String]("keyspaces.host")
    val port     = config.get[Int]("keyspaces.port")
    val region   = config.get[String]("keyspaces.region")
    val username = config.get[String]("keyspaces.username")
    val password = config.get[String]("keyspaces.password")
    val keyspace = config.get[String]("keyspaces.keyspace")

    val loader = DriverConfigLoader.fromClasspath("keyspaces.conf")

    val authProvider = new ProgrammaticPlainTextAuthProvider(username, password)

    CqlSession.builder()
      .withConfigLoader(loader)
      // .addContactPoint(new InetSocketAddress(host, port))  // Disabled: region discovery
      .withLocalDatacenter(region)
      .withAuthProvider(authProvider)
      .withKeyspace(keyspace)
      .withSslContext(SSLContext.getDefault)
      .build()
  }

  /**
   * Creates a singleton S3AsyncClient for reading Parquet files from lakehouse bucket.
   *
   * Configuration properties:
   * - `keyspaces.accesskey`    - AWS access key ID
   * - `keyspaces.secretkey`    - AWS secret access key
   * - `keyspaces.regionS3`     - S3 bucket region
   *
   * Uses static credentials (not IAM roles/EC2 metadata).
   *
   * @param config Play application configuration
   * @return Singleton S3AsyncClient instance
   */
  @Provides
  @Singleton
  def provideS3AsyncClient(config: Configuration): S3AsyncClient = {
    val accessKey = config.get[String]("keyspaces.accesskey")
    val secretKey = config.get[String]("keyspaces.secretkey")
    val regionStr = config.get[String]("keyspaces.regionS3")

    val creds = StaticCredentialsProvider.create(
      AwsBasicCredentials.create(accessKey, secretKey)
    )

    S3AsyncClient.builder()
      .region(Region.of(regionStr))
      .credentialsProvider(creds)
      .build()
  }
  /**
   * Provides singleton RedisCache instance with configurable host/port.
   *
   * Always creates RedisClient but respects `app.redis.enabled` flag in RedisCache.
   * When disabled (dev mode), RedisCache gracefully returns cache misses.
   *
   * Configuration properties:
   * - `app.redis.enabled` - Enable Redis (default: false in dev)
   * - `redis.host`        - Redis hostname (default: "localhost")
   * - `redis.port`        - Redis port (default: 6379)
   *
   * Dev flow: No Redis server needed (cache miss → backend)
   * Prod flow: Redis L2 cache between L1 (LocalCache) and S3/Cassandra
   *
   * @param config Play application configuration
   * @param ec     Execution context for async operations
   * @return Singleton RedisCache instance
   */
  @Provides
  @Singleton
  def provideRedisCache(config: Configuration)(implicit ec: ExecutionContext): RedisCache = {
    val host = config.getOptional[String]("redis.host").getOrElse("localhost")
    val port = config.getOptional[Int]("redis.port").getOrElse(6379)

    val client = new RedisClient(host, port)
    new RedisCache(config, client)
  }

}
