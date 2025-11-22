package utils

import play.api.db.slick.DatabaseConfigProvider
import slick.basic.DatabaseConfig
import slick.jdbc.MySQLProfile

import javax.inject._

/**
 * A minimal implementation of Play's [[play.api.db.slick.DatabaseConfigProvider]]
 * used for manually supplying a Slick [[DatabaseConfig]] instance.
 *
 * This class is particularly useful in:
 *   - Standalone modules or utilities that are not managed by Play's DI.
 *   - Unit tests where a custom or in-memory database configuration is required.
 *   - Background services, worker processes, or actors that need direct DB config injection.
 *
 * ### Purpose
 * Play normally provides a `DatabaseConfigProvider` automatically via dependency injection.
 * However, in some cases you may want to:
 *
 *   - Construct a `DatabaseConfig` manually
 *   - Inject a non-default database configuration
 *   - Use a database configuration outside of the standard Play environment
 *
 * `SimpleDbConfigProvider` allows these scenarios by simply wrapping an existing
 * `DatabaseConfig` and exposing it via `.get`.
 *
 * ### Behavior
 * - The provider **does not create or manage** the database configuration.
 * - It only **returns the exact config instance** provided during construction.
 * - No environment lookup, config loading, or profile resolution is performed.
 *
 * @param dbConfig The Slick database configuration instance to expose
 */
@Singleton
class SimpleDbConfigProvider @Inject()(
                                        val dbConfig: DatabaseConfig[MySQLProfile]
                                      ) extends DatabaseConfigProvider {

  /**
   * Returns the injected Slick [[DatabaseConfig]] instance, cast to the expected profile.
   *
   * @tparam P The Slick profile required by the caller.
   * @return The database configuration as the requested profile type.
   */
  override def get[P <: slick.basic.BasicProfile]: DatabaseConfig[P] =
    dbConfig.asInstanceOf[DatabaseConfig[P]]
}
