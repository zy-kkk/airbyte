/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.cdk.integrations.destination.jdbc;

import static io.airbyte.cdk.integrations.base.errors.messages.ErrorMessage.getErrorMessage;

import com.fasterxml.jackson.databind.JsonNode;
import io.airbyte.cdk.db.factory.DataSourceFactory;
import io.airbyte.cdk.db.jdbc.DefaultJdbcDatabase;
import io.airbyte.cdk.db.jdbc.JdbcDatabase;
import io.airbyte.cdk.db.jdbc.JdbcUtils;
import io.airbyte.cdk.integrations.BaseConnector;
import io.airbyte.cdk.integrations.base.AirbyteMessageConsumer;
import io.airbyte.cdk.integrations.base.AirbyteTraceMessageUtility;
import io.airbyte.cdk.integrations.base.Destination;
import io.airbyte.cdk.integrations.base.SerializedAirbyteMessageConsumer;
import io.airbyte.cdk.integrations.destination.NamingConventionTransformer;
import io.airbyte.cdk.integrations.destination_async.partial_messages.PartialAirbyteMessage;
import io.airbyte.cdk.integrations.destination_async.partial_messages.PartialAirbyteRecordMessage;
import io.airbyte.commons.exceptions.ConnectionErrorException;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.map.MoreMaps;
import io.airbyte.protocol.models.v0.AirbyteConnectionStatus;
import io.airbyte.protocol.models.v0.AirbyteConnectionStatus.Status;
import io.airbyte.protocol.models.v0.AirbyteMessage;
import io.airbyte.protocol.models.v0.ConfiguredAirbyteCatalog;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.apache.commons.lang3.NotImplementedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractJdbcDestination extends BaseConnector implements Destination {

  private static final Logger LOGGER = LoggerFactory.getLogger(AbstractJdbcDestination.class);

  private final String driverClass;
  private final NamingConventionTransformer namingResolver;
  private final SqlOperations sqlOperations;

  protected NamingConventionTransformer getNamingResolver() {
    return namingResolver;
  }

  protected SqlOperations getSqlOperations() {
    return sqlOperations;
  }

  public AbstractJdbcDestination(final String driverClass,
                                 final NamingConventionTransformer namingResolver,
                                 final SqlOperations sqlOperations) {
    this.driverClass = driverClass;
    this.namingResolver = namingResolver;
    this.sqlOperations = sqlOperations;
  }

  @Override
  public AirbyteConnectionStatus check(final JsonNode config) {
    final DataSource dataSource = getDataSource(config);

    try {
      final JdbcDatabase database = getDatabase(dataSource);
      final String outputSchema = namingResolver.getIdentifier(config.get(JdbcUtils.SCHEMA_KEY).asText());
      attemptSQLCreateAndDropTableOperations(outputSchema, database, namingResolver, sqlOperations);
      return new AirbyteConnectionStatus().withStatus(Status.SUCCEEDED);
    } catch (final ConnectionErrorException ex) {
      final String message = getErrorMessage(ex.getStateCode(), ex.getErrorCode(), ex.getExceptionMessage(), ex);
      AirbyteTraceMessageUtility.emitConfigErrorTrace(ex, message);
      return new AirbyteConnectionStatus()
          .withStatus(Status.FAILED)
          .withMessage(message);
    } catch (final Exception e) {
      LOGGER.error("Exception while checking connection: ", e);
      return new AirbyteConnectionStatus()
          .withStatus(Status.FAILED)
          .withMessage("Could not connect with provided configuration. \n" + e.getMessage());
    } finally {
      try {
        DataSourceFactory.close(dataSource);
      } catch (final Exception e) {
        LOGGER.warn("Unable to close data source.", e);
      }
    }
  }

  /**
   * This method is deprecated. It verifies table creation, but not insert right to a newly created
   * table. Use attemptTableOperations with the attemptInsert argument instead.
   */
  @Deprecated
  public static void attemptSQLCreateAndDropTableOperations(final String outputSchema,
                                                            final JdbcDatabase database,
                                                            final NamingConventionTransformer namingResolver,
                                                            final SqlOperations sqlOps)
      throws Exception {
    attemptTableOperations(outputSchema, database, namingResolver, sqlOps, false);
  }

  /**
   * Verifies if provided creds has enough permissions. Steps are: 1. Create schema if not exists. 2.
   * Create test table. 3. Insert dummy record to newly created table if "attemptInsert" set to true.
   * 4. Delete table created on step 2.
   *
   * @param outputSchema - schema to tests against.
   * @param database - database to tests against.
   * @param namingResolver - naming resolver.
   * @param sqlOps - SqlOperations object
   * @param attemptInsert - set true if need to make attempt to insert dummy records to newly created
   *        table. Set false to skip insert step.
   */
  public static void attemptTableOperations(final String outputSchema,
                                            final JdbcDatabase database,
                                            final NamingConventionTransformer namingResolver,
                                            final SqlOperations sqlOps,
                                            final boolean attemptInsert)
      throws Exception {
    // verify we have write permissions on the target schema by creating a table with a random name,
    // then dropping that table
    try {
      // Get metadata from the database to see whether connection is possible
      database.bufferedResultSetQuery(conn -> conn.getMetaData().getCatalogs(), JdbcUtils.getDefaultSourceOperations()::rowToJson);

      // verify we have write permissions on the target schema by creating a table with a random name,
      // then dropping that table
      final String outputTableName = namingResolver.getIdentifier("_airbyte_connection_test_" + UUID.randomUUID().toString().replaceAll("-", ""));
      sqlOps.createSchemaIfNotExists(database, outputSchema);
      sqlOps.createTableIfNotExists(database, outputSchema, outputTableName);
      // verify if user has permission to make SQL INSERT queries
      try {
        if (attemptInsert) {
          sqlOps.insertRecords(database, List.of(getDummyRecord()), outputSchema, outputTableName);
        }
      } finally {
        sqlOps.dropTableIfExists(database, outputSchema, outputTableName);
      }
    } catch (final SQLException e) {
      if (Objects.isNull(e.getCause()) || !(e.getCause() instanceof SQLException)) {
        throw new ConnectionErrorException(e.getSQLState(), e.getErrorCode(), e.getMessage(), e);
      } else {
        final SQLException cause = (SQLException) e.getCause();
        throw new ConnectionErrorException(e.getSQLState(), cause.getErrorCode(), cause.getMessage(), e);
      }
    } catch (final Exception e) {
      throw new Exception(e);
    }
  }

  /**
   * Generates a dummy AirbyteRecordMessage with random values.
   *
   * @return AirbyteRecordMessage object with dummy values that may be used to test insert permission.
   */
  private static PartialAirbyteMessage getDummyRecord() {
    final JsonNode dummyDataToInsert = Jsons.deserialize("{ \"field1\": true }");
    return new PartialAirbyteMessage()
        .withRecord(new PartialAirbyteRecordMessage()
            .withStream("stream1")
            .withEmittedAt(1602637589000L))
        .withSerialized(dummyDataToInsert.toString());
  }

  protected DataSource getDataSource(final JsonNode config) {
    final JsonNode jdbcConfig = toJdbcConfig(config);
    return DataSourceFactory.create(
        jdbcConfig.get(JdbcUtils.USERNAME_KEY).asText(),
        jdbcConfig.has(JdbcUtils.PASSWORD_KEY) ? jdbcConfig.get(JdbcUtils.PASSWORD_KEY).asText() : null,
        driverClass,
        jdbcConfig.get(JdbcUtils.JDBC_URL_KEY).asText(),
        getConnectionProperties(config));
  }

  protected JdbcDatabase getDatabase(final DataSource dataSource) {
    return new DefaultJdbcDatabase(dataSource);
  }

  protected Map<String, String> getConnectionProperties(final JsonNode config) {
    final Map<String, String> customProperties = JdbcUtils.parseJdbcParameters(config, JdbcUtils.JDBC_URL_PARAMS_KEY);
    final Map<String, String> defaultProperties = getDefaultConnectionProperties(config);
    assertCustomParametersDontOverwriteDefaultParameters(customProperties, defaultProperties);
    return MoreMaps.merge(customProperties, defaultProperties);
  }

  private void assertCustomParametersDontOverwriteDefaultParameters(final Map<String, String> customParameters,
                                                                    final Map<String, String> defaultParameters) {
    for (final String key : defaultParameters.keySet()) {
      if (customParameters.containsKey(key) && !Objects.equals(customParameters.get(key), defaultParameters.get(key))) {
        throw new IllegalArgumentException("Cannot overwrite default JDBC parameter " + key);
      }
    }
  }

  protected abstract Map<String, String> getDefaultConnectionProperties(final JsonNode config);

  public abstract JsonNode toJdbcConfig(JsonNode config);

  @Override
  public AirbyteMessageConsumer getConsumer(final JsonNode config,
                                            final ConfiguredAirbyteCatalog catalog,
                                            final Consumer<AirbyteMessage> outputRecordCollector) {
    throw new NotImplementedException("Should use the getSerializedMessageConsumer instead");
  }

  @Override
  public SerializedAirbyteMessageConsumer getSerializedMessageConsumer(final JsonNode config,
                                                                       final ConfiguredAirbyteCatalog catalog,
                                                                       final Consumer<AirbyteMessage> outputRecordCollector)
      throws Exception {
    return JdbcBufferedConsumerFactory.createAsync(
        outputRecordCollector,
        getDatabase(getDataSource(config)),
        sqlOperations,
        namingResolver,
        config,
        catalog,
        null,
        // TODO populate the DV2 stuff
        false,
        null,
        null,
        null);
  }

}
