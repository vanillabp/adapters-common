package io.vanillabp.integration.runtime.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.smallrye.config.SmallRyeConfigBuilder;
import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
import io.vanillabp.integration.adapter.migration.config.OutfadedVersionsInUsePolicy;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterPropertiesMapper;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Round-trip test pinning the GENERATED {@code toCore()} mapping: every property of
 * the Quarkus {@code @ConfigMapping} interface has to arrive in the core model.
 * Adding a property to only one side already fails the BUILD (the mapper is
 * generated with {@code unmappedSourcePolicy}/{@code unmappedTargetPolicy} ERROR);
 * this test additionally pins the VALUE semantics (Optional unwrapping, empty-list
 * defaults, nested maps).
 */
@ExtendWith(SuppressOutputExtension.class)
public class QuarkusMigrationAdapterPropertiesMapperTest {

  private record AdapterConfiguration(
                                      Optional<String> type,
                                      Optional<DeploymentFailurePolicy> deploymentFailure,
                                      Optional<String> resourcesLocation,
                                      Optional<io.vanillabp.integration.adapter.spi.NameClashAvoidance> nameClashAvoidance,
                                      Optional<Boolean> prefixTaskDefinitionsPerProcess,
                                      Optional<Boolean> deduplicateDeliveries,
                                      Optional<List<String>> outfadedVersions,
                                      Optional<OutfadedVersionsInUsePolicy> outfadedVersionsInUse) implements QuarkusMigrationAdapterProperties.AdapterConfiguration {
  }

  private record AdapterProperties(
                                   Optional<String> resourcesLocation,
                                   Optional<io.vanillabp.integration.adapter.spi.NameClashAvoidance> nameClashAvoidance,
                                   Optional<Boolean> prefixTaskDefinitionsPerProcess,
                                   Optional<Boolean> deduplicateDeliveries,
                                   Optional<List<String>> outfadedVersions,
                                   Optional<OutfadedVersionsInUsePolicy> outfadedVersionsInUse) implements QuarkusMigrationAdapterProperties.AdapterProperties {
  }

  private record TaskProperties(
                                Map<String, QuarkusMigrationAdapterProperties.AdapterProperties> adapters,
                                QuarkusMigrationAdapterProperties.DeliveryProperties delivery) implements QuarkusMigrationAdapterProperties.TaskProperties {

    /**
     * Without a delivery section, which is what most of these fixtures need.
     */
    private TaskProperties(
        final Map<String, QuarkusMigrationAdapterProperties.AdapterProperties> adapters) {

      this(adapters, null);

    }

  }

  private record WorkflowProperties(
                                    Optional<List<String>> prioritizedAdapters,
                                    Map<String, QuarkusMigrationAdapterProperties.AdapterProperties> adapters,
                                    Map<String, QuarkusMigrationAdapterProperties.TaskProperties> tasks,
                                    QuarkusMigrationAdapterProperties.DeliveryProperties delivery) implements QuarkusMigrationAdapterProperties.WorkflowProperties {

    /**
     * Without a delivery section, which is what most of these fixtures need.
     */
    private WorkflowProperties(
        final Optional<List<String>> prioritizedAdapters,
        final Map<String, QuarkusMigrationAdapterProperties.AdapterProperties> adapters,
        final Map<String, QuarkusMigrationAdapterProperties.TaskProperties> tasks) {

      this(prioritizedAdapters, adapters, tasks, null);

    }

  }

  private record WorkflowModuleProperties(
                                          Optional<List<String>> prioritizedAdapters,
                                          Map<String, QuarkusMigrationAdapterProperties.AdapterProperties> adapters,
                                          Map<String, QuarkusMigrationAdapterProperties.WorkflowProperties> workflows,
                                          QuarkusMigrationAdapterProperties.TransactionsProperties transactions,
                                          QuarkusMigrationAdapterProperties.DeliveryProperties delivery) implements QuarkusMigrationAdapterProperties.WorkflowModuleProperties {

    /**
     * None of these fixtures configures the election, so the module inherits whatever
     * applies globally.
     */
    @Override
    public QuarkusMigrationAdapterProperties.ElectionProperties election() {

      return null;

    }

    /**
     * None of these fixtures overrides an extension's settings for one module.
     */
    @Override
    public Map<String, Map<String, String>> extensions() {

      return Map.of();

    }

    /**
     * Without a transaction and without a delivery section, which is what most of these
     * fixtures need.
     */
    private WorkflowModuleProperties(
        final Optional<List<String>> prioritizedAdapters,
        final Map<String, QuarkusMigrationAdapterProperties.AdapterProperties> adapters,
        final Map<String, QuarkusMigrationAdapterProperties.WorkflowProperties> workflows) {

      this(prioritizedAdapters, adapters, workflows, null, null);

    }

    /**
     * Without a delivery section.
     */
    private WorkflowModuleProperties(
        final Optional<List<String>> prioritizedAdapters,
        final Map<String, QuarkusMigrationAdapterProperties.AdapterProperties> adapters,
        final Map<String, QuarkusMigrationAdapterProperties.WorkflowProperties> workflows,
        final QuarkusMigrationAdapterProperties.TransactionsProperties transactions) {

      this(prioritizedAdapters, adapters, workflows, transactions, null);

    }

  }

  private record TransactionsProperties(
                                        Optional<io.vanillabp.integration.adapter.migration.config.TransactionsProperties.UnguardedAggregateWrites> unguardedAggregateWrites) implements QuarkusMigrationAdapterProperties.TransactionsProperties {
  }

  private record DeliveryProperties(
                                    Optional<Boolean> releaseOnWorkflowEnd,
                                    Optional<Duration> maxTaskAge,
                                    Optional<Duration> retention) implements QuarkusMigrationAdapterProperties.DeliveryProperties {

    /**
     * Only the release setting, which is what the fixtures written before the maximum
     * age existed need.
     */
    private DeliveryProperties(
        final Optional<Boolean> releaseOnWorkflowEnd) {

      this(releaseOnWorkflowEnd, Optional.empty(), Optional.empty());

    }

    /**
     * The release setting and the maximum age, which is what the fixtures written before
     * the retention was split off the outbox' one need.
     */
    private DeliveryProperties(
        final Optional<Boolean> releaseOnWorkflowEnd,
        final Optional<Duration> maxTaskAge) {

      this(releaseOnWorkflowEnd, maxTaskAge, Optional.empty());

    }

  }

  private record JdbcOutboxProperties(
                                      boolean enabled,
                                      Optional<String> table) implements QuarkusMigrationAdapterProperties.JdbcOutboxProperties {
  }

  private record MongoOutboxProperties(
                                       boolean enabled,
                                       String collection) implements QuarkusMigrationAdapterProperties.MongoOutboxProperties {
  }

  private record OutboxProperties(
                                  Duration pollInterval,
                                  Duration attemptFrequency,
                                  Duration maxAttemptFrequency,
                                  int blockAfterAttempts,
                                  boolean createSchema,
                                  Duration retention,
                                  QuarkusMigrationAdapterProperties.JdbcOutboxProperties jdbc,
                                  QuarkusMigrationAdapterProperties.MongoOutboxProperties mongo) implements QuarkusMigrationAdapterProperties.PhaseTwoOutboxProperties {
  }

  private record WorkflowAdapterCacheProperties(
                                                int maxEntries,
                                                Duration timeToLive,
                                                Duration endedTimeToLive,
                                                boolean releaseOnWorkflowEnd) implements QuarkusMigrationAdapterProperties.WorkflowAdapterCacheProperties {
  }

  private record MetricsProperties(
                                   Duration gaugeCache) implements QuarkusMigrationAdapterProperties.MetricsProperties {
  }

  private record Properties(
                            Optional<List<String>> prioritizedAdapters,
                            Optional<String> resourcesLocation,
                            Map<String, QuarkusMigrationAdapterProperties.AdapterConfiguration> adapters,
                            Map<String, QuarkusMigrationAdapterProperties.WorkflowModuleProperties> workflowModules,
                            QuarkusMigrationAdapterProperties.PhaseTwoOutboxProperties outbox,
                            QuarkusMigrationAdapterProperties.WorkflowAdapterCacheProperties workflowAdapterCache,
                            QuarkusMigrationAdapterProperties.TransactionsProperties transactions,
                            QuarkusMigrationAdapterProperties.DeliveryProperties delivery,
                            QuarkusMigrationAdapterProperties.MetricsProperties metrics) implements QuarkusMigrationAdapterProperties {

    /**
     * None of these fixtures configures the election - the mapper's job here is the rest
     * of the tree.
     */
    @Override
    public QuarkusMigrationAdapterProperties.ElectionProperties election() {

      return null;

    }

    /**
     * The adapter ids retired deliberately - none of these fixtures names any,
     * so the mapper's list is empty and the startup check has nothing to keep quiet about.
     */
    @Override
    public Optional<List<String>> retiredAdapters() {

      return Optional.empty();

    }

    /**
     * None of these fixtures configures an extension.
     */
    @Override
    public Map<String, Map<String, String>> extensions() {

      return Map.of();

    }

    /**
     * Without a transaction and without a delivery section.
     */
    private Properties(
        final Optional<List<String>> prioritizedAdapters,
        final Optional<String> resourcesLocation,
        final Map<String, QuarkusMigrationAdapterProperties.AdapterConfiguration> adapters,
        final Map<String, QuarkusMigrationAdapterProperties.WorkflowModuleProperties> workflowModules,
        final QuarkusMigrationAdapterProperties.PhaseTwoOutboxProperties outbox,
        final QuarkusMigrationAdapterProperties.WorkflowAdapterCacheProperties workflowAdapterCache) {

      this(
          prioritizedAdapters, resourcesLocation, adapters, workflowModules, outbox, workflowAdapterCache, null, null, null);

    }

    /**
     * Without a delivery section.
     */
    private Properties(
        final Optional<List<String>> prioritizedAdapters,
        final Optional<String> resourcesLocation,
        final Map<String, QuarkusMigrationAdapterProperties.AdapterConfiguration> adapters,
        final Map<String, QuarkusMigrationAdapterProperties.WorkflowModuleProperties> workflowModules,
        final QuarkusMigrationAdapterProperties.PhaseTwoOutboxProperties outbox,
        final QuarkusMigrationAdapterProperties.WorkflowAdapterCacheProperties workflowAdapterCache,
        final QuarkusMigrationAdapterProperties.TransactionsProperties transactions) {

      this(
          prioritizedAdapters, resourcesLocation, adapters, workflowModules, outbox, workflowAdapterCache, transactions, null, null);

    }

    /**
     * Without a metrics section.
     */
    private Properties(
        final Optional<List<String>> prioritizedAdapters,
        final Optional<String> resourcesLocation,
        final Map<String, QuarkusMigrationAdapterProperties.AdapterConfiguration> adapters,
        final Map<String, QuarkusMigrationAdapterProperties.WorkflowModuleProperties> workflowModules,
        final QuarkusMigrationAdapterProperties.PhaseTwoOutboxProperties outbox,
        final QuarkusMigrationAdapterProperties.WorkflowAdapterCacheProperties workflowAdapterCache,
        final QuarkusMigrationAdapterProperties.TransactionsProperties transactions,
        final QuarkusMigrationAdapterProperties.DeliveryProperties delivery) {

      this(
          prioritizedAdapters, resourcesLocation, adapters, workflowModules, outbox, workflowAdapterCache, transactions, delivery, null);

    }

  }

  @Test
  @DisplayName("Every interface property arrives in the core model")
  public void fullTreeArrivesInCoreModel() {

    final var properties = new Properties(
        Optional.of(List.of("c8-cloud", "c7")), Optional.of("classpath*:vanillabp-processes"), Map.of(
            "c8-cloud", new AdapterConfiguration(
                Optional.of("camunda8"), Optional.of(DeploymentFailurePolicy.WARN), Optional
                    .of("adapter-level"), Optional
                        .of(io.vanillabp.integration.adapter.spi.NameClashAvoidance.USE_PREFIX), Optional
                            .of(false), Optional.of(false), Optional.of(List.of("<3")), Optional
                                .of(OutfadedVersionsInUsePolicy.FAIL)),
            "c7", new AdapterConfiguration(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional
                    .empty(), Optional.empty(), Optional.empty())), Map.of(
                        "loan-approval", new WorkflowModuleProperties(
                            Optional.of(List.of("c7")), Map.of("c7",
                                new AdapterProperties(Optional.of("classpath:c7-bpmn"), Optional
                                    .of(io.vanillabp.integration.adapter.spi.NameClashAvoidance.NONE), Optional
                                        .empty(), Optional.empty(), Optional.empty(), Optional
                                            .empty())), Map
                                                .of("LoanApproval",
                                                    new WorkflowProperties(
                                                        Optional.of(List.of("c8-cloud")), Map
                                                            .of("c8-cloud", new AdapterProperties(Optional
                                                                .of("workflow-level"), Optional
                                                                    .empty(), Optional.of(true), Optional
                                                                        .of(true), Optional
                                                                            .of(List.of("1-2", "legacy")), Optional
                                                                                .empty())), Map
                                                                                    .of("assessRisk",
                                                                                        new TaskProperties(Map
                                                                                            .of("c8-cloud",
                                                                                                new AdapterProperties(Optional
                                                                                                    .of("task-level"), Optional
                                                                                                        .empty(), Optional
                                                                                                            .empty(), Optional
                                                                                                                .of(false), Optional
                                                                                                                    .empty(), Optional
                                                                                                                        .empty())))))))), new OutboxProperties(
                                                                                                                            Duration
                                                                                                                                .ofSeconds(
                                                                                                                                    1), Duration
                                                                                                                                        .ofSeconds(
                                                                                                                                            2), Duration
                                                                                                                                                .ofSeconds(
                                                                                                                                                    20), 3, false, Duration
                                                                                                                                                        .ofDays(
                                                                                                                                                            1), new JdbcOutboxProperties(false, Optional
                                                                                                                                                                .of("HOT_OUTBOX")), new MongoOutboxProperties(
                                                                                                                                                                    false, "hot-outbox")), new WorkflowAdapterCacheProperties(
                                                                                                                                                                        50_000, Duration
                                                                                                                                                                            .ofMinutes(
                                                                                                                                                                                30), Duration
                                                                                                                                                                                    .ofMinutes(
                                                                                                                                                                                        2), true));

    final var core = QuarkusMigrationAdapterPropertiesMapper.INSTANCE.toCore(properties);

    assertEquals(List.of("c8-cloud", "c7"), core.getPrioritizedAdapters());
    assertEquals("classpath*:vanillabp-processes", core.getResourcesLocation());
    assertEquals(
        Map.of(
            "c8-cloud", "camunda8",
            "c7", "c7"),
        core.adapterTypes());
    assertEquals(DeploymentFailurePolicy.WARN, core.getDeploymentFailureFor("c8-cloud"));
    assertEquals(DeploymentFailurePolicy.FAIL, core.getDeploymentFailureFor("c7"));

    final var module = core.getWorkflowModules().get("loan-approval");
    assertEquals(List.of("c7"), module.getPrioritizedAdapters());
    assertEquals("classpath:c7-bpmn", module.getAdapters().get("c7").getResourcesLocation());
    final var workflow = module.getWorkflows().get("LoanApproval");
    assertEquals(List.of("c8-cloud"), workflow.getPrioritizedAdapters());
    assertEquals("workflow-level", workflow.getAdapters().get("c8-cloud").getResourcesLocation());
    assertEquals(
        "task-level",
        workflow.getTasks().get("assessRisk").getAdapters().get("c8-cloud").getResourcesLocation());
    assertEquals("adapter-level", core.getAdapters().get("c8-cloud").getResourcesLocation());
    // the switch travels the same four levels as every adapter-scoped key
    assertEquals(
        Boolean.FALSE,
        core.getAdapters().get("c8-cloud").getDeduplicateDeliveries());
    assertEquals(
        Boolean.TRUE,
        workflow.getAdapters().get("c8-cloud").getDeduplicateDeliveries());
    assertEquals(
        Boolean.FALSE,
        workflow.getTasks().get("assessRisk").getAdapters().get("c8-cloud").getDeduplicateDeliveries());
    // The outfaded versions and their policy travel the same levels; an
    // empty Optional has to arrive as null, so the next level decides
    assertEquals(
        List.of("<3"),
        core.getAdapters().get("c8-cloud").getOutfadedVersions());
    assertEquals(
        OutfadedVersionsInUsePolicy.FAIL,
        core.getAdapters().get("c8-cloud").getOutfadedVersionsInUse());
    assertEquals(
        List.of("1-2", "legacy"),
        workflow.getAdapters().get("c8-cloud").getOutfadedVersions());
    assertNull(workflow.getAdapters().get("c8-cloud").getOutfadedVersionsInUse());
    assertNull(workflow.getTasks().get("assessRisk").getAdapters().get("c8-cloud").getOutfadedVersions());

    assertEquals(Duration.ofSeconds(1), core.getOutbox().getPollInterval());
    assertEquals(Duration.ofSeconds(2), core.getOutbox().getAttemptFrequency());
    assertEquals(Duration.ofSeconds(20), core.getOutbox().getMaxAttemptFrequency());
    assertEquals(3, core.getOutbox().getBlockAfterAttempts());
    assertFalse(core.getOutbox().isCreateSchema());
    assertEquals(Duration.ofDays(1), core.getOutbox().getRetention());
    assertFalse(core.getOutbox().getJdbc().isEnabled());
    assertEquals("HOT_OUTBOX", core.getOutbox().getJdbc().getTable());
    assertFalse(core.getOutbox().getMongo().isEnabled());
    assertEquals("hot-outbox", core.getOutbox().getMongo().getCollection());

    assertEquals(50_000, core.getWorkflowAdapterCache().getMaxEntries());
    assertEquals(Duration.ofMinutes(30), core.getWorkflowAdapterCache().getTimeToLive());
    assertEquals(Duration.ofMinutes(2), core.getWorkflowAdapterCache().getEndedTimeToLive());
    assertTrue(core.getWorkflowAdapterCache().isReleaseOnWorkflowEnd());

  }

  @Test
  @DisplayName("The interface's @WithDefault election-cache values equal the core defaults")
  public void workflowAdapterCacheDefaultsMatchCoreDefaults() {

    final var config = new SmallRyeConfigBuilder()
        .withMapping(QuarkusMigrationAdapterProperties.class)
        .build();
    final var mappedDefaults = QuarkusMigrationAdapterPropertiesMapper.INSTANCE.toCore(
        config
            .getConfigMapping(QuarkusMigrationAdapterProperties.class)
            .workflowAdapterCache());

    final var coreDefaults = new io.vanillabp.integration.adapter.migration.config.WorkflowAdapterCacheProperties();
    assertEquals(coreDefaults.getMaxEntries(), mappedDefaults.getMaxEntries());
    assertEquals(coreDefaults.getTimeToLive(), mappedDefaults.getTimeToLive());
    assertEquals(coreDefaults.getEndedTimeToLive(), mappedDefaults.getEndedTimeToLive());
    assertEquals(coreDefaults.isReleaseOnWorkflowEnd(), mappedDefaults.isReleaseOnWorkflowEnd());

  }

  @Test
  @DisplayName("The interface's @WithDefault gauge-cache equals the core default")
  public void metricsDefaultsMatchCoreDefaults() {

    final var config = new SmallRyeConfigBuilder()
        .withMapping(QuarkusMigrationAdapterProperties.class)
        .build();
    final var mappedDefaults = QuarkusMigrationAdapterPropertiesMapper.INSTANCE
        .toCore(
            config
                .getConfigMapping(QuarkusMigrationAdapterProperties.class)
                .metrics());

    final var coreDefaults = new io.vanillabp.integration.adapter.migration.config.MetricsProperties();
    assertEquals(coreDefaults.getGaugeCache(), mappedDefaults.getGaugeCache());

  }

  @Test
  @DisplayName("A configured gauge-cache travels into the core model")
  public void configuredGaugeCacheTravels() {

    final var properties = new Properties(
        Optional.empty(), Optional.empty(), Map.of(), Map
            .of(), null, null, null, null, new MetricsProperties(Duration.ofSeconds(30)));

    final var core = QuarkusMigrationAdapterPropertiesMapper.INSTANCE.toCore(properties);

    assertEquals(Duration.ofSeconds(30), core.getMetrics().getGaugeCache());

  }

  @Test
  @DisplayName("The interface's @WithDefault outbox values equal the core defaults")
  public void outboxDefaultsMatchCoreDefaults() {

    // instantiate the REAL mapping with an empty configuration so SmallRye fills
    // the @WithDefault values, map onto the core and compare against the core's
    // field initializers - pins the necessarily duplicated defaults against drift
    final var config = new SmallRyeConfigBuilder()
        .withMapping(QuarkusMigrationAdapterProperties.class)
        .build();
    final var mappedDefaults = QuarkusMigrationAdapterPropertiesMapper.INSTANCE.toCore(
        config
            .getConfigMapping(QuarkusMigrationAdapterProperties.class)
            .outbox());

    final var coreDefaults = new PhaseTwoOutboxProperties();
    assertEquals(coreDefaults.getPollInterval(), mappedDefaults.getPollInterval());
    assertEquals(coreDefaults.getAttemptFrequency(), mappedDefaults.getAttemptFrequency());
    assertEquals(coreDefaults.getMaxAttemptFrequency(), mappedDefaults.getMaxAttemptFrequency());
    assertEquals(coreDefaults.getBlockAfterAttempts(), mappedDefaults.getBlockAfterAttempts());
    assertEquals(coreDefaults.isCreateSchema(), mappedDefaults.isCreateSchema());
    assertEquals(coreDefaults.getRetention(), mappedDefaults.getRetention());
    assertEquals(coreDefaults.getJdbc().isEnabled(), mappedDefaults.getJdbc().isEnabled());
    assertEquals(coreDefaults.getJdbc().getTable(), mappedDefaults.getJdbc().getTable());
    assertEquals(coreDefaults.getMongo().isEnabled(), mappedDefaults.getMongo().isEnabled());
    assertEquals(coreDefaults.getMongo().getCollection(), mappedDefaults.getMongo().getCollection());

  }

  @Test
  @DisplayName("The delivery retention travels, and is absent where it is not configured")
  public void theDeliveryRetentionTravels() {

    // the delivery log resolves this against the outbox retention, so "not configured"
    // has to arrive as null rather than as a zero or a default of its own
    final var configured = QuarkusMigrationAdapterPropertiesMapper.INSTANCE
        .toCore(new DeliveryProperties(Optional.empty(), Optional.empty(), Optional.of(Duration.ofDays(30))));
    assertEquals(Duration.ofDays(30), configured.getRetention());

    final var unconfigured = QuarkusMigrationAdapterPropertiesMapper.INSTANCE
        .toCore(new DeliveryProperties(Optional.empty(), Optional.empty(), Optional.empty()));
    assertEquals(null, unconfigured.getRetention());
    assertEquals(
        Duration.ofDays(7),
        io.vanillabp.integration.adapter.migration.config.DeliveryProperties
            .resolveRetention(unconfigured, Duration.ofDays(7)),
        "and the resolution then answers the outbox number");

  }

  @Test
  @DisplayName("The unguarded-writes setting travels globally and per workflow module")
  public void transactionsSettingTravelsGloballyAndPerModule() {

    final var accepted = io.vanillabp.integration.adapter.migration.config.TransactionsProperties.UnguardedAggregateWrites.ACCEPTED;
    final var rejected = io.vanillabp.integration.adapter.migration.config.TransactionsProperties.UnguardedAggregateWrites.REJECTED;

    final var properties = new Properties(
        Optional.empty(), Optional.empty(), Map.of(), Map
            .of(
                "loan-approval",
                new WorkflowModuleProperties(
                    Optional.empty(), Map.of(), Map.of(), new TransactionsProperties(Optional.of(accepted))),
                "payments",
                new WorkflowModuleProperties(
                    Optional.empty(), Map.of(), Map.of(), new TransactionsProperties(Optional
                        .empty()))), null, null, new TransactionsProperties(Optional.of(rejected)));

    final var core = QuarkusMigrationAdapterPropertiesMapper.INSTANCE.toCore(properties);

    assertEquals(rejected, core.getTransactions().getUnguardedAggregateWrites());
    // the module's own value wins; a module saying nothing, and a module without any
    // section at all, inherit what the application configured globally
    assertTrue(core.acceptsUnguardedAggregateWrites("loan-approval"));
    assertFalse(core.acceptsUnguardedAggregateWrites("payments"));
    assertFalse(core.acceptsUnguardedAggregateWrites("unconfigured-module"));

  }

  @Test
  @DisplayName("The release-on-workflow-end setting travels globally and per workflow module")
  public void deliverySettingTravelsGloballyAndPerModule() {

    final var properties = new Properties(
        Optional.empty(), Optional.empty(), Map.of(), Map
            .of(
                "loan-approval",
                new WorkflowModuleProperties(
                    Optional.empty(), Map.of(), Map.of(), null, new DeliveryProperties(Optional.of(true))),
                "payments",
                new WorkflowModuleProperties(
                    Optional.empty(), Map.of(), Map.of(), null, new DeliveryProperties(Optional
                        .empty()))), null, null, null, new DeliveryProperties(Optional.of(false)));

    final var core = QuarkusMigrationAdapterPropertiesMapper.INSTANCE.toCore(properties);

    assertEquals(Boolean.FALSE, core.getDelivery().getReleaseOnWorkflowEnd());
    // the module's own value wins; a module saying nothing, and a module without any
    // section at all, inherit what the application configured globally
    assertTrue(core.releasesDeliveryRecordsOnWorkflowEnd("loan-approval"));
    assertFalse(core.releasesDeliveryRecordsOnWorkflowEnd("payments"));
    assertFalse(core.releasesDeliveryRecordsOnWorkflowEnd("unconfigured-module"));

  }

  @Test
  @DisplayName("The maximum age of an open task travels through all four levels")
  public void maxTaskAgeTravelsThroughEveryLevel() {

    final var task = new TaskProperties(
        Map.of(), new DeliveryProperties(Optional.empty(), Optional.of(Duration.ofDays(40))));
    final var workflow = new WorkflowProperties(
        Optional.empty(), Map.of(), Map.of("awaitSignature", task), new DeliveryProperties(
            Optional.empty(), Optional.of(Duration.ofDays(30))));
    final var module = new WorkflowModuleProperties(
        Optional.empty(), Map.of(), Map.of("LoanApproval", workflow), null, new DeliveryProperties(
            Optional.empty(), Optional.of(Duration.ofDays(20))));
    final var properties = new Properties(
        Optional.empty(), Optional.empty(), Map.of(), Map.of("loan-approval",
            module), null, null, null, new DeliveryProperties(Optional.empty(), Optional.of(Duration.ofDays(10))));

    final var core = QuarkusMigrationAdapterPropertiesMapper.INSTANCE.toCore(properties);
    core.validateAndLink();

    assertEquals(Duration.ofDays(10), core.maxTaskAge("payments", null, null));
    assertEquals(Duration.ofDays(20), core.maxTaskAge("loan-approval", "OtherProcess", null));
    assertEquals(Duration.ofDays(30), core.maxTaskAge("loan-approval", "LoanApproval", "otherTask"));
    assertEquals(Duration.ofDays(40), core.maxTaskAge("loan-approval", "LoanApproval", "awaitSignature"));

  }

  @Test
  @DisplayName("Empty optionals map to the core model's defaults")
  public void emptyOptionalsMapToDefaults() {

    final var properties = new Properties(
        Optional.empty(), Optional.empty(), Map.of(), Map.of(), null, null);

    final var core = QuarkusMigrationAdapterPropertiesMapper.INSTANCE.toCore(properties);

    assertTrue(core.getPrioritizedAdapters().isEmpty());
    assertNull(core.getResourcesLocation());
    assertTrue(core.getAdapters().isEmpty());
    assertTrue(core.getWorkflowModules().isEmpty());

  }

}
