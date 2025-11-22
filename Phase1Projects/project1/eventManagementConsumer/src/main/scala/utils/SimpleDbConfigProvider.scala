package utils

import javax.inject._
import play.api.db.slick.DatabaseConfigProvider
import slick.basic.DatabaseConfig
import slick.jdbc.MySQLProfile

/**
 * A simple implementation of [[DatabaseConfigProvider]] used primarily
 * for dependency injection in test environments, small modules, or
 * manually constructed application components.
 *
 * This class allows you to directly inject a pre-built
 * [[slick.basic.DatabaseConfig]] instance, instead of letting Play
 * automatically load it from configuration files.
 *
 * Typical use cases:
 *   - Unit testing repositories with an in-memory or mock database
 *   - Custom module wiring where a different DBConfig is desired
 *   - Multi-database setups where configurations are created manually
 *
 * @param dbConfig A Slick database configuration for the [[MySQLProfile]].
 */
@Singleton
class SimpleDbConfigProvider @Inject()(
                                        val dbConfig: DatabaseConfig[MySQLProfile]
                                      ) extends DatabaseConfigProvider {

  /**
   * Returns the underlying Slick [[DatabaseConfig]] instance, cast to
   * the requested profile type.
   *
   * @tparam P A Slick profile type extending [[slick.basic.BasicProfile]].
   * @return   A type-safe database configuration instance.
   */
  override def get[P <: slick.basic.BasicProfile]: DatabaseConfig[P] =
    dbConfig.asInstanceOf[DatabaseConfig[P]]
}
