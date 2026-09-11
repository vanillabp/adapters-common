package io.vanillabp.integration.outbox.gruelbox;

import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.PlatformTransactionManager;

import com.gruelbox.transactionoutbox.DefaultPersistor;
import com.gruelbox.transactionoutbox.Dialect;
import com.gruelbox.transactionoutbox.Persistor;
import com.gruelbox.transactionoutbox.Submitter;
import com.gruelbox.transactionoutbox.TransactionOutbox;
import com.gruelbox.transactionoutbox.TransactionOutboxListener;
import com.gruelbox.transactionoutbox.spring.SpringInstantiator;
import com.gruelbox.transactionoutbox.spring.SpringTransactionManager;

import io.vanillabp.integration.adapter.migration.jdbc.JdbcSchema;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.utils.config.JpaSpringDataUtilConfiguration;
import jakarta.persistence.EntityManagerFactory;

/**
 * Auto-configuration of the default {@link PhaseTwoOutbox} for JPA-based aggregate
 * persistence, backed by the
 * <a href="https://github.com/gruelbox/transaction-outbox">gruelbox
 * transaction-outbox</a>. Active whenever Spring Data JPA is on the classpath and
 * exactly one {@link EntityManagerFactory} exists - it COEXISTS with the MongoDB
 * default: each workflow aggregate is served by the outbox matching its persistence
 * (selection per aggregate, see
 * {@link io.vanillabp.integration.spi.PhaseTwoOutboxAware}), so outbox
 * entries always ride the aggregate's own transaction even in mixed-persistence
 * applications. Disable via <code>vanillabp.outbox.jdbc.enabled</code> if the
 * default (including its table and background dispatcher) is unwanted.
 * <p>
 * The outbox table (<code>TXNO_OUTBOX</code>, override via
 * <code>vanillabp.outbox.jdbc.table</code>) is created automatically via gruelbox's
 * schema migration unless <code>vanillabp.outbox.create-schema</code> is set to
 * <code>false</code> (see the module's <code>README.md</code> for managing the schema
 * manually). Wherever that migration is off - which a custom table name does as well -
 * the table's existence is verified AT STARTUP (see
 * {@link #validateOutboxTableExists(DataSource, String)}), because this one table is
 * gruelbox's and therefore not covered by <code>io.vanillabp:vanillabp-schema</code>.
 * <p>
 * <strong>Contract mapping (deviations):</strong> the {@link PhaseTwoOutbox} contract
 * is mapped onto gruelbox's native capabilities: idempotency keys become
 * <code>uniqueRequestId</code>s (unique constraint of <code>TXNO_OUTBOX</code>), "DONE
 * instead of delete" becomes gruelbox's retention of processed entries with a unique
 * request ID (<code>vanillabp.outbox.retention</code> maps to gruelbox's retention
 * threshold; expired entries are deleted by the background flush), and blocking after
 * <code>vanillabp.outbox.block-after-attempts</code> failed attempts is gruelbox's
 * native blocklisting. What gruelbox has no idea of is VanillaBP's classification of a
 * failure, so a failure the adapter calls permanent is blocked by a listener of
 * VanillaBP's ({@link GruelboxPhaseTwoFailureListener}), which also gives a blocked entry
 * an ERROR naming the workflow instead of only the entry id. Two things this store cannot
 * do and the own stores can: its
 * retry policy knows ONE fixed distance, so <code>max-attempt-frequency</code> and the
 * doubling it caps have no effect here, and a blocklisted entry holds its
 * <code>uniqueRequestId</code> until the row is removed, so the operation it failed at
 * cannot be scheduled again in the meantime. What it also cannot express is the short
 * due time of a workflow which is not searchable yet, and that one is answered rather
 * than accepted: {@link GruelboxPhaseTwoDispatchBean} waits the window out on the
 * dispatching thread. The {@link PhaseTwoCall#args()} map travels in its serialized
 * form because gruelbox's invocation serializer only accepts scalar parameter types
 * (see {@link GruelboxPhaseTwoDispatch}).
 */
@AutoConfiguration(
    after = JpaSpringDataUtilConfiguration.class,
    afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration", "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration", "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
    })
@ConditionalOnClass({
    JpaRepository.class, TransactionOutbox.class
})
@ConditionalOnSingleCandidate(EntityManagerFactory.class)
@ConditionalOnBean({
    DataSource.class, PlatformTransactionManager.class
})
@ConditionalOnBooleanProperty(name = "vanillabp.outbox.jdbc.enabled", matchIfMissing = true)
@EnableConfigurationProperties(VanillaBpConfigurationProperties.class)
public class GruelboxPhaseTwoOutboxAutoConfiguration {

  /**
   * The name of the default JPA/gruelbox outbox bean - used by the resolver to
   * attribute JPA-persisted aggregates to THE default when several outbox beans
   * exist.
   */
  public static final String DEFAULT_OUTBOX_BEAN_NAME = "vanillaBpGruelboxPhaseTwoOutbox";

  /**
   * The name of the default gruelbox {@link TransactionOutbox} bean. The default's
   * beans reference each other BY NAME so an application may define additional
   * gruelbox instances (e.g. a dedicated outbox on its own table for a high-load
   * process) without suppressing or confusing the default.
   */
  public static final String DEFAULT_TRANSACTION_OUTBOX_BEAN_NAME = "vanillaBpTransactionOutbox";

  /**
   * The table gruelbox stores outbox entries in unless
   * <code>vanillabp.outbox.jdbc.table</code> names another one - and the only table
   * gruelbox's own schema migration ever creates.
   */
  public static final String DEFAULT_OUTBOX_TABLE_NAME = "TXNO_OUTBOX";

  /**
   * The gruelbox {@link TransactionOutbox} enlisting entries in Spring-managed JDBC
   * transactions and instantiating the scheduled {@link GruelboxPhaseTwoDispatch}
   * from the application context.
   *
   * @param applicationContext Used to resolve the scheduled bean at dispatch time
   * @param transactionManagers All Spring transaction managers; entries are
   *          enlisted with the JDBC/JPA one (see
   *          {@link #selectJdbcTransactionManager(Map)})
   * @param dataSource The data source storing the outbox table
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree carrying the
   *          <code>vanillabp.outbox</code> section (registered here as well so the
   *          outbox works in contexts without the full VanillaBP auto-configuration)
   * @param metrics Provider of what a blocked entry is counted into; Micrometer is
   *          optional, so the bean may legitimately be absent
   * @param applicationListeners The outbox listeners the application brings, which keep
   *          being called next to VanillaBP's own one
   * @return The transaction outbox
   */
  @Bean(DEFAULT_TRANSACTION_OUTBOX_BEAN_NAME)
  @ConditionalOnMissingBean(name = DEFAULT_TRANSACTION_OUTBOX_BEAN_NAME)
  public TransactionOutbox vanillaBpTransactionOutbox(
      final ApplicationContext applicationContext,
      final Map<String, PlatformTransactionManager> transactionManagers,
      final DataSource dataSource,
      final VanillaBpConfigurationProperties vanillaBpProperties,
      final ObjectProvider<io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics> metrics,
      final ObjectProvider<TransactionOutboxListener> applicationListeners) {

    final var properties = vanillaBpProperties.getOutbox();
    // the gruelbox migration always targets the DEFAULT table (TXNO_OUTBOX) - a
    // custom table name therefore requires the table to be created manually (see
    // 'vanillabp.outbox.jdbc.table')
    final var customTable = properties.getJdbc().getTable();
    final var migrate = properties.isCreateSchema() && (customTable == null);
    if (!migrate) {
      validateOutboxTableExists(dataSource, customTable);
    }
    final var persistorBuilder = DefaultPersistor
        .builder()
        .dialect(detectDialect(dataSource))
        .migrate(migrate);
    if (customTable != null) {
      persistorBuilder.tableName(customTable);
    }
    // the persistor and the transaction manager are held as locals because the listener
    // needs both to write the blocked flag of an entry gruelbox would keep retrying
    final var persistor = persistorBuilder.build();
    final var transactionManager = new SpringTransactionManager(
        selectJdbcTransactionManager(transactionManagers), dataSource);
    return TransactionOutbox
        .builder()
        .transactionManager(transactionManager)
        .instantiator(new SpringInstantiator(applicationContext))
        .persistor(persistor)
        .listener(outboxListener(persistor, transactionManager, metrics, applicationListeners))
        // carries "this entry was attempted before" to the dispatch bean - the
        // START re-dispatch mitigation's signal (see the submitter's javadoc)
        .submitter(new GruelboxRedispatchAwareSubmitter(Submitter.withDefaultExecutor()))
        .attemptFrequency(properties.getAttemptFrequency())
        .blockAfterAttempts(properties.getBlockAfterAttempts())
        .retentionThreshold(properties.getRetention())
        .initializeImmediately(true)
        .build();

  }

  /**
   * The listener the outbox reports its failures to: VanillaBP's own one, followed by
   * whatever listeners the application defined. Gruelbox takes exactly one, so the
   * application's are chained behind VanillaBP's rather than replacing it - an
   * application which listens to its outbox keeps hearing everything it heard before.
   *
   * @param persistor The persistor of this outbox
   * @param transactionManager The transaction manager of this outbox
   * @param metrics Provider of what a blocked entry is counted into
   * @param applicationListeners The listeners the application brings
   * @return The listener to hand to the outbox
   */
  private static TransactionOutboxListener outboxListener(
      final Persistor persistor,
      final SpringTransactionManager transactionManager,
      final ObjectProvider<io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics> metrics,
      final ObjectProvider<TransactionOutboxListener> applicationListeners) {

    TransactionOutboxListener listener = new GruelboxPhaseTwoFailureListener(
        persistor, transactionManager, () -> io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration
            .vanillaBpMetricsOf(metrics));
    for (final var applicationListener : applicationListeners) {
      listener = listener.andThen(applicationListener);
    }
    return listener;

  }

  /**
   * @param transactionOutbox The gruelbox transaction outbox
   * @param dataSource The data source holding gruelbox' table, used to count the
   *          entries waiting for their dispatch
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree, naming the
   *          table where the application configured one of its own
   * @return The {@link PhaseTwoOutbox} used by the process services
   */
  @Bean(DEFAULT_OUTBOX_BEAN_NAME)
  public GruelboxPhaseTwoOutbox vanillaBpGruelboxPhaseTwoOutbox(
      @Qualifier(DEFAULT_TRANSACTION_OUTBOX_BEAN_NAME) final TransactionOutbox transactionOutbox,
      final DataSource dataSource,
      final VanillaBpConfigurationProperties vanillaBpProperties) {

    final var customTable = vanillaBpProperties
        .getOutbox()
        .getJdbc()
        .getTable();
    return new GruelboxPhaseTwoOutbox(
        transactionOutbox, dataSource, customTable == null
            ? DEFAULT_OUTBOX_TABLE_NAME
            : customTable);

  }

  /**
   * The bean invoked by the outbox at dispatch time (resolved by gruelbox's
   * <code>SpringInstantiator</code> as the unique bean of type
   * {@link GruelboxPhaseTwoDispatch}). It rebuilds the {@link PhaseTwoCall} and
   * routes it through the core's {@link PhaseTwoRouter}.
   *
   * @param phaseTwoRouter Provider of the router dispatched to
   * @return The dispatch bean
   */
  @Bean
  public GruelboxPhaseTwoDispatch vanillaBpGruelboxPhaseTwoDispatch(
      final ObjectProvider<PhaseTwoRouter> phaseTwoRouter) {

    return (
        operation,
        workflowModuleId,
        bpmnProcessId,
        workflowAggregateId,
        adapterId,
        serializedArgs) -> new GruelboxPhaseTwoDispatchBean(phaseTwoRouter.getObject())
            .dispatch(
                operation, workflowModuleId, bpmnProcessId, workflowAggregateId, adapterId, serializedArgs);

  }

  /**
   * @param transactionOutbox The gruelbox transaction outbox
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree carrying the
   *          <code>vanillabp.outbox</code> section (registered here as well so the
   *          outbox works in contexts without the full VanillaBP auto-configuration)
   * @return The dispatcher polling the outbox for recovery, retries and retention
   *         cleanup (private single-thread executor - no
   *         {@link org.springframework.scheduling.TaskScheduler} involved)
   */
  @Bean
  public GruelboxPhaseTwoOutboxDispatcher vanillaBpGruelboxPhaseTwoOutboxDispatcher(
      @Qualifier(DEFAULT_TRANSACTION_OUTBOX_BEAN_NAME) final TransactionOutbox transactionOutbox,
      final VanillaBpConfigurationProperties vanillaBpProperties) {

    return new GruelboxPhaseTwoOutboxDispatcher(transactionOutbox, vanillaBpProperties.getOutbox());

  }

  /**
   * Selects the transaction manager the outbox entries are enlisted with: the JDBC
   * one - the same transaction persisting JPA workflow aggregates. In
   * mixed-persistence applications a second (e.g. MongoDB) transaction manager
   * exists; the JDBC/JPA one is identified by Spring Boot's conventional bean name
   * <code>transactionManager</code>.
   *
   * @param transactionManagers All transaction managers, keyed by bean name
   * @return The JDBC/JPA transaction manager
   * @throws IllegalStateException If several transaction managers exist and none is
   *           named <code>transactionManager</code>
   */
  static PlatformTransactionManager selectJdbcTransactionManager(
      final Map<String, PlatformTransactionManager> transactionManagers) {

    if (transactionManagers.size() == 1) {
      return transactionManagers
          .values()
          .iterator()
          .next();
    }
    final var conventional = transactionManagers.get("transactionManager");
    if (conventional != null) {
      return conventional;
    }
    throw new IllegalStateException(
        """
            Several transaction managers exist (%s), but none is named 'transactionManager'! The \
            JDBC-based phase-two outbox enlists its entries with the transaction manager persisting \
            the JPA workflow aggregates - name that one 'transactionManager' (Spring Boot's \
            convention) or define your own gruelbox TransactionOutbox bean named '%s'."""
            .formatted(transactionManagers.keySet(), DEFAULT_TRANSACTION_OUTBOX_BEAN_NAME));

  }

  /**
   * Verifies that the outbox table exists whenever gruelbox's schema migration is switched
   * off - by <code>vanillabp.outbox.create-schema</code> for an application applying its
   * schema itself, or by a custom table name, which switches the migration off
   * as well since it only ever targets {@value #DEFAULT_OUTBOX_TABLE_NAME}.
   * <p>
   * Unlike VanillaBP's own tables this one belongs to gruelbox, so the message points to
   * gruelbox for the statements instead of to
   * <code>io.vanillabp:vanillabp-schema</code>. Without the check a missing table surfaces
   * at the first workflow started on a remote BPMS, hours after a deployment which booted
   * cleanly.
   *
   * @param dataSource The data source holding the outbox table
   * @param customTable The configured table name, <code>null</code> for the default
   * @throws IllegalStateException If the table is missing
   */
  private static void validateOutboxTableExists(
      final DataSource dataSource,
      final String customTable) {

    final var tableName = customTable == null ? DEFAULT_OUTBOX_TABLE_NAME : customTable;
    try (var connection = dataSource.getConnection()) {
      if (JdbcSchema.tableExists(connection, tableName)) {
        return;
      }
    } catch (final SQLException e) {
      throw new IllegalStateException(
          "Could not check whether the phase-two outbox table '%s' exists!".formatted(tableName), e);
    }
    final var remedies = customTable == null
        ? """
            - apply gruelbox's schema with your migration tool: \
            'com.gruelbox.transactionoutbox.DefaultPersistor.writeSchema(Writer)' writes the \
            statements for the database you configure, or
            - let gruelbox create the table by setting 'vanillabp.outbox.create-schema' to \
            'true' (the default)."""
        : """
            - create the table yourself, structured like gruelbox's default table '%s': \
            'com.gruelbox.transactionoutbox.DefaultPersistor.writeSchema(Writer)' writes the \
            statements for the database you configure, or
            - remove 'vanillabp.outbox.jdbc.table' and let gruelbox create '%s' (which needs \
            'vanillabp.outbox.create-schema' to be 'true', the default)."""
            .formatted(DEFAULT_OUTBOX_TABLE_NAME, DEFAULT_OUTBOX_TABLE_NAME);
    throw new IllegalStateException(
        """
            The phase-two outbox table '%s' does not exist! Starting a workflow on a remote BPMS \
            writes an entry into it inside the caller's transaction, so without the table nothing \
            can be started. This table is gruelbox's own, not VanillaBP's: it is NOT part of \
            'io.vanillabp:vanillabp-schema' and gruelbox's schema migration is switched off here. \
            Either
            %s
            The wiki page 'Spring Boot integration', section 'Creating the tables with Liquibase or \
            Flyway', describes the whole procedure."""
            .formatted(tableName, remedies));

  }

  /**
   * Detects the gruelbox SQL dialect from the data source's metadata.
   *
   * Package-private so the mapping and the message for an unsupported database can be
   * asserted without a database of each product.
   *
   * @param dataSource The data source used for the outbox table
   * @return The dialect
   * @throws IllegalStateException If the database is not supported by gruelbox
   */
  static Dialect detectDialect(
      final DataSource dataSource) {

    final String productName;
    try (var connection = dataSource.getConnection()) {
      productName = connection.getMetaData().getDatabaseProductName();
    } catch (SQLException e) {
      throw new IllegalStateException(
          "Could not determine the database product name used to configure the VanillaBP phase-two outbox!", e);
    }
    final var product = productName.toLowerCase();
    if (product.contains("h2")) {
      return Dialect.H2;
    }
    if (product.contains("postgres")) {
      return Dialect.POSTGRESQL_9;
    }
    if (product.contains("mysql") || product.contains("mariadb")) {
      return Dialect.MY_SQL_8;
    }
    if (product.contains("oracle")) {
      return Dialect.ORACLE;
    }
    if (product.contains("microsoft")) {
      return Dialect.MS_SQL_SERVER;
    }
    throw new IllegalStateException(
        """
            Database '%s' is not supported by the gruelbox-based VanillaBP phase-two outbox! \
            Define your own com.gruelbox.transactionoutbox.TransactionOutbox bean or provide a custom \
            implementation of io.vanillabp.integration.spi.PhaseTwoOutbox."""
            .formatted(productName));

  }

}
