package io.vanillabp.integration.processservice;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import io.vanillabp.integration.adapter.AdapterConfigurationBase;
import io.vanillabp.integration.adapter.migration.config.ClasspathFacts;
import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import io.vanillabp.integration.support.BpmsAdapters;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModules;
import io.vanillabp.integration.workflowtask.SpringTransactionRunner;
import lombok.extern.slf4j.Slf4j;

/**
 * Autoconfiguration of VanillaBP adapters. The
 * {@link io.vanillabp.spi.process.ProcessService} beans are registered by the
 * {@link ProcessServiceBeanRegistrar}, once {@link WorkflowServiceDiscovery} knows
 * which workflow services this application brings.
 */
@Slf4j
@AutoConfiguration(after = WorkflowModuleAutoConfiguration.class)
@EnableConfigurationProperties(VanillaBpConfigurationProperties.class)
public class SpringBootMigrationAdapterAutoConfiguration {

  static final String BEANNAME_MIGRATIONADAPERPROPERTIES = "VanillaBpMigrationAdapterProperties";

  /**
   * Finds the workflow services among the bean definitions of the application and
   * registers a {@link io.vanillabp.spi.process.ProcessService} bean per workflow
   * aggregate.
   * <p>
   * Declared static, because a bean-factory post-processor pulled from an instance
   * method would drag this configuration class - and everything it depends on - into
   * being created before the other post-processors ran.
   *
   * @return The discovery
   */
  @Bean
  public static WorkflowServiceDiscovery vanillaBpWorkflowServiceDiscovery() {

    return new WorkflowServiceDiscovery();

  }

  /**
   * Converts the values of <code>vanillabp.adapters.&lt;id&gt;.deployment-failure</code>
   * case-insensitively into the core enum. Registered as a
   * {@link ConfigurationPropertiesBinding} converter so an invalid value fails the
   * binding with a message naming the allowed values (Spring's bind failure adds the
   * offending property key and value).
   *
   * @return The converter
   */
  @Bean
  @ConfigurationPropertiesBinding
  public static Converter<String, DeploymentFailurePolicy> vanillaBpDeploymentFailurePolicyConverter() {

    return source -> {
      try {
        return DeploymentFailurePolicy.valueOf(source.trim().toUpperCase());
      } catch (final IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "must be one of 'fail' or 'warn'");
      }
    };

  }

  /**
   * Validates the directly bound {@link VanillaBpConfigurationProperties} (facts
   * from the classpath are passed into the core validation) and provides them as
   * the platform-neutral {@link MigrationAdapterProperties} bean used by the common
   * adapter implementation of module "migration-adapter".
   *
   * @param properties The bound core properties
   * @param environment The Spring environment (used to detect environment variables
   *          not taken over by the binding)
   * @param allWorkflowModules All workflow modules found in classpath
   * @param adapterConfigurations Configuration beans of adapters found in classpath
   * @return The validated properties bean
   */
  @Bean(BEANNAME_MIGRATIONADAPERPROPERTIES)
  @Primary
  public static MigrationAdapterProperties migrationAdapterProperties(
      final VanillaBpConfigurationProperties properties,
      final Environment environment,
      final WorkflowModules allWorkflowModules,
      final ObjectProvider<AdapterConfigurationBase> adapterConfigurations,
      final ApplicationContext applicationContext) {

    // ObjectProvider (not a required List): without any adapter on the classpath the
    // bean creation still runs and the guiding message about a missing adapter is
    // actually reachable
    final var adaptersLoaded = adapterConfigurations
        .stream()
        .map(AdapterConfigurationBase::getAdapterType)
        .toList();
    if (adaptersLoaded.isEmpty()) {
      // the neighbouring case - no integration at all, because the adapter which would
      // have brought it is missing - is reported by NoBpmsAdapterCheck of module
      // 'vanillabp-spring-boot-support'
      throw new IllegalStateException(
          """
              No VanillaBP BPMS adapter found in classpath! VanillaBP's Spring Boot integration is \
              loaded, but no adapter, so there is no BPMS which could run the workflows of a \
              workflow module.%s"""
              .formatted(BpmsAdapters.artifactsToAdd()));
    }

    // convention over configuration: the classpath facts are what the
    // conventions are derived from - which workflow module descriptor comes from
    // the application's MAIN artifact decides the conventional resources location
    final var mainArtifactRootPrefix = mainArtifactRootPrefix(applicationContext);
    final var workflowModules = allWorkflowModules
        .getWorkflowModules()
        .stream()
        .map(workflowModule -> new ClasspathFacts.WorkflowModuleInfo(
            workflowModule.getId(), (mainArtifactRootPrefix != null) && mainArtifactRootPrefix
                .equals(workflowModule.getSourceUri())))
        .toList();

    properties.validateProperties(
        new ClasspathFacts(adaptersLoaded, workflowModules),
        null);
    properties.validateEnvironmentVariableUsage(rawPropertyNames(environment));

    return properties;

  }

  /**
   * The core-owned sync model: turns a workflow aggregate into the
   * values shared with the BPMS, honoring
   * {@code @SyncWithBPMS}/{@code @NoSyncWithBPMS} and the adapter's default. One
   * instance per application - BPMS adapters receive it and decide what to do with
   * the values (push them as process variables, or write them as operator context
   * only).
   *
   * @return The sync support
   */
  @Bean
  @ConditionalOnMissingBean
  public static io.vanillabp.integration.adapter.spi.WorkflowAggregateSync vanillaBpWorkflowAggregateSync() {

    return new io.vanillabp.integration.adapter.migration.sync.AggregateSyncSupport();

  }

  /**
   * The classpath-root prefix of the application's MAIN artifact: the JAR or
   * directory the <code>&#64;SpringBootApplication</code> class was loaded from.
   * A workflow module descriptor found in that very root belongs to the
   * application itself (and not to a workflow module shipped as its own
   * artifact), which decides the conventional resources location.
   * <p>
   * Bean TYPES are inspected (never instances), so nothing is instantiated early.
   * An application without such a class (e.g. a plain
   * <code>&#64;Configuration</code> test context) yields <code>null</code> - every
   * workflow module is then treated as an own artifact, the conservative choice.
   *
   * @param applicationContext The application context
   * @return The classpath-root prefix or <code>null</code>
   */
  private static String mainArtifactRootPrefix(
      final ApplicationContext applicationContext) {

    return Arrays
        .stream(applicationContext.getBeanNamesForAnnotation(SpringBootApplication.class))
        .map(applicationContext::getType)
        .filter(java.util.Objects::nonNull)
        .map(ClassUtils::getUserClass)
        .map(WorkflowModuleAutoConfiguration::determineClasspathRootPrefix)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(null);

  }

  /**
   * Collects the raw names of all enumerable properties of the environment -
   * including the unconverted environment-variable names of the
   * <code>systemEnvironment</code> property source, which the core uses to detect
   * <code>VANILLABP_*</code> variables not taken over by the binding.
   *
   * @param environment The Spring environment
   * @return The raw property names
   */
  private static List<String> rawPropertyNames(
      final Environment environment) {

    final var result = new LinkedList<String>();
    if (environment instanceof AbstractEnvironment abstractEnvironment) {
      abstractEnvironment
          .getPropertySources()
          .stream()
          .filter(EnumerablePropertySource.class::isInstance)
          .map(EnumerablePropertySource.class::cast)
          .forEach(propertySource -> result.addAll(List.of(propertySource.getPropertyNames())));
    }
    return result;

  }

  /**
   * The core-owned router receiving phase-two calls dispatched by a
   * {@link io.vanillabp.integration.spi.PhaseTwoOutbox} implementation and
   * routing them to the {@link io.vanillabp.spi.process.ProcessService} bean
   * responsible for the workflow module and BPMN process given. The process-service
   * beans register themselves (including the aggregate-ID converter) at
   * bean-creation time.
   *
   * @return The phase-two router
   */
  @Bean
  @ConditionalOnMissingBean(PhaseTwoRouter.class)
  public PhaseTwoRouter vanillaBpPhaseTwoRouter(
      final org.springframework.beans.factory.ObjectProvider<io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics> metrics) {

    final var router = new PhaseTwoRouter();
    router.setMetrics(vanillaBpMetricsOf(metrics));
    return router;

  }

  /**
   * The metrics implementation to use: the Micrometer one where the application
   * brings Micrometer,
   * {@link io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics#NONE}
   * otherwise. Micrometer is optional, so the bean may legitimately be absent.
   *
   * @param metrics The provider of the metrics bean
   * @return What to record into, never <code>null</code>
   */
  public static io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics vanillaBpMetricsOf(
      final org.springframework.beans.factory.ObjectProvider<io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics> metrics) {

    return metrics
        .getIfAvailable(
            () -> io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.NONE);

  }

  /**
   * The router's registry of phase-two operations - injectable so an extension can
   * register operations of its own (VanillaBP's core operations are registered by
   * the router itself).
   * <p>
   * Deliberately NOT conditional on a missing bean: the registry an extension
   * registers in has to be the one the router dispatches from. An application
   * wanting its own registry gives it to its own {@link PhaseTwoRouter} bean, and
   * this bean follows.
   *
   * @param phaseTwoRouter The router owning the registry
   * @return The operation registry
   */
  @Bean
  public io.vanillabp.integration.spi.PhaseOperationRegistry vanillaBpPhaseOperationRegistry(
      final PhaseTwoRouter phaseTwoRouter) {

    return phaseTwoRouter.getOperations();

  }

  /**
   * The cache of workflow&rarr;adapter associations consulted by the BPMS election
   * for operations on existing workflows (complete/cancel task, user task, message
   * correlation): a bounded, expiring in-memory default, sized by
   * <code>vanillabp.workflow-adapter-cache.*</code>. Cluster setups share their
   * elections instead, either with the implementation VanillaBP ships for Hazelcast
   * or with a bean of their own implementing
   * {@link io.vanillabp.integration.spi.WorkflowAdapterCache} - either replaces this
   * default (entries are hints: a stale entry costs an extra probe, never
   * correctness).
   * <p>
   * What this cache knows about itself - its size, its evictions - it reports itself,
   * under a prefix of this implementation. It is not handed the application's
   * election statistics for that: a number only one implementation can produce does
   * not belong among the numbers of the election.
   *
   * @param properties The VanillaBP configuration (the cache's bounds)
   * @return The default in-memory cache
   */
  @Bean
  @ConditionalOnMissingBean(io.vanillabp.integration.spi.WorkflowAdapterCache.class)
  public io.vanillabp.integration.spi.WorkflowAdapterCache vanillaBpWorkflowAdapterCache(
      final MigrationAdapterProperties properties) {

    return new io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache(
        properties.getWorkflowAdapterCache());

  }

  /**
   * What the election asked of the cache: hits, misses and how often the end of a
   * workflow reached it. One instance per application, and the same three numbers for
   * every implementation - the process services wrap whatever cache is in use into an
   * {@code InstrumentedWorkflowAdapterCache} reporting here, so the numbers stay
   * comparable when an application plugs in its own cache.
   *
   * @return The statistics
   */
  @Bean
  @ConditionalOnMissingBean
  public io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheStatistics vanillaBpWorkflowAdapterCacheStatistics() {

    return new io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheStatistics();

  }

  /**
   * Publishes the election cache's statistics as Micrometer meters, if the
   * application brings Micrometer. The Actuator's metrics auto-configuration
   * applies {@link io.micrometer.core.instrument.binder.MeterBinder} beans to every
   * registry, so no endpoint of our own is needed; an application without
   * Micrometer boots unchanged and reports no metrics.
   */
  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  // by NAME, not by class literal: the annotation of a nested configuration class is
  // read reflectively, so a class literal of an absent optional dependency would
  // fail before the condition is ever evaluated
  @org.springframework.boot.autoconfigure.condition.ConditionalOnClass(
      name = "io.micrometer.core.instrument.MeterRegistry")
  public static class WorkflowAdapterCacheMetricsConfiguration {

    /**
     * @param statistics The application's cache statistics
     * @return The meter binder of the election cache
     */
    @Bean
    @ConditionalOnMissingBean
    public io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheMeters vanillaBpWorkflowAdapterCacheMeters(
        final io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheStatistics statistics) {

      return new io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheMeters(
          statistics);

    }

    /**
     * What the in-memory cache knows about itself, published under the prefix of that
     * implementation. The binder takes the cache the application ended up with and
     * registers nothing where that is a shared cache or one of the application's own:
     * a size which cannot be read is a meter which is not published, rather than one
     * reporting NaN for the rest of the application's life.
     *
     * @param caches The election caches of this application - the platform's default
     *          and the bean an application defines can coexist, and which of them the
     *          elections use is decided by
     *          {@link WorkflowAdapterCacheSelection} rather than by an injection
     * @return The meter binder of the in-memory cache
     */
    @Bean
    @ConditionalOnMissingBean
    public io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCacheMeters vanillaBpInMemoryWorkflowAdapterCacheMeters(
        final ObjectProvider<io.vanillabp.integration.spi.WorkflowAdapterCache> caches) {

      return new io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCacheMeters(
          WorkflowAdapterCacheSelection
              .theCacheInUse(caches
                  .orderedStream()
                  .toList()));

    }

    /**
     * What VanillaBP counts about its deliveries and its outbox. It is a
     * {@code MeterBinder} as well, so the Actuator hands it the registry the same way
     * it hands it to the election cache's meters - nothing of VanillaBP asks for a
     * registry itself.
     *
     * @param properties The VanillaBP configuration, carrying how long the measurement
     *          of a gauge which has to ask somebody is reused
     * @return The metrics of deliveries and outbox
     */
    @Bean
    @ConditionalOnMissingBean
    public io.vanillabp.integration.adapter.migration.observability.MicrometerVanillaBpMetrics vanillaBpMetrics(
        final io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties properties) {

      return new io.vanillabp.integration.adapter.migration.observability.MicrometerVanillaBpMetrics(
          properties
              .getMetrics()
              .resolvedGaugeCache());

    }

    /**
     * Publishes how many entries wait in each outbox store, for the stores which can
     * count them
     * ({@link io.vanillabp.integration.spi.PhaseTwoOutbox#pendingCalls()}). The
     * gauge is tagged with the bean name of the store, because an application may
     * run several of them (mixed persistence, a dedicated outbox for one aggregate).
     * <p>
     * The stores are resolved lazily: touching them while the beans are built would
     * materialize persistence infrastructure long before the application asked for
     * it.
     *
     * @param metrics The metrics to register the gauges with
     * @param outboxes All outbox stores of the application
     * @return The registration, run once the context is ready
     */
    @Bean
    public org.springframework.beans.factory.SmartInitializingSingleton vanillaBpOutboxPendingGauges(
        final io.vanillabp.integration.adapter.migration.observability.MicrometerVanillaBpMetrics metrics,
        final org.springframework.beans.factory.ObjectProvider<io.vanillabp.integration.spi.PhaseTwoOutbox> outboxes) {

      return () -> outboxes
          .stream()
          .filter(outbox -> outbox
              .pendingCalls()
              .isPresent())
          .forEach(outbox -> metrics
              .registerPendingOutboxEntries(
                  outbox
                      .getClass()
                      .getSimpleName(),
                  outbox::pendingCalls));

    }

  }

  /**
   * Asks every BPMS adapter what it can say about its BPMS - the platform-neutral
   * half of the health contribution. The adapters are resolved on every
   * call, so an adapter which materializes late is asked as well.
   *
   * @param deploymentServices The adapters' deployment services, one per configured
   *          adapter id
   * @return The report
   */
  @Bean
  @ConditionalOnMissingBean
  public io.vanillabp.integration.adapter.migration.health.AdapterHealthReport vanillaBpAdapterHealthReport(
      final org.springframework.beans.factory.ObjectProvider<io.vanillabp.integration.adapter.spi.AdapterDeploymentService<?, ?>> deploymentServices) {

    return new io.vanillabp.integration.adapter.migration.health.AdapterHealthReport(
        () -> deploymentServices
            .stream()
            .toList());

  }

  /**
   * Publishes the adapters' health under the component <code>vanillabp</code> of
   * Spring Boot's health endpoint, where the application brings the health support.
   * An application without it boots unchanged and publishes nothing.
   */
  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  // by NAME for the same reason the metrics configuration uses names: the annotation
  // of a nested configuration class is read reflectively
  @org.springframework.boot.autoconfigure.condition.ConditionalOnClass(
      name = "org.springframework.boot.health.contributor.HealthIndicator")
  public static class AdapterHealthConfiguration {

    /**
     * The bean name decides the name of the health component, so it has to stay
     * <code>vanillabp</code> + the suffix Spring Boot strips.
     *
     * @param report What the adapters answered
     * @return The health indicator
     */
    @Bean("vanillabpHealthIndicator")
    @ConditionalOnMissingBean(name = "vanillabpHealthIndicator")
    public io.vanillabp.integration.health.VanillaBpHealthIndicator vanillabpHealthIndicator(
        final io.vanillabp.integration.adapter.migration.health.AdapterHealthReport report) {

      return new io.vanillabp.integration.health.VanillaBpHealthIndicator(report);

    }

  }

  /**
   * The core-owned name-clash-avoidance model: resolves the mode per
   * workflow module/workflow and adapter and composes the identifiers a BPMS sees.
   * One instance per application - BPMS adapters receive it and apply it to their
   * model and their commands.
   * <p>
   * The adapters' deployment services are streamed LAZILY (they receive this bean, so
   * they cannot be injected here): the mode applying without configuration is the
   * adapter's own, and an unscoped workflow module is reported by the adapter itself.
   *
   * @param properties The VanillaBP configuration
   * @param deploymentServices The adapters' deployment services, resolved on first use
   * @return The name-clash-avoidance support
   */
  @Bean
  @ConditionalOnMissingBean
  public io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport vanillaBpNameClashAvoidanceSupport(
      final io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties properties,
      final org.springframework.beans.factory.ObjectProvider<io.vanillabp.integration.adapter.spi.AdapterDeploymentService<?, ?>> deploymentServices) {

    return new io.vanillabp.integration.adapter.migration.scoping.NameClashAvoidanceService(
        properties, () -> deploymentServices
            .stream()
            .toList());

  }

  /**
   * The core-owned registry of <code>&#64;WorkflowTask</code> handlers and
   * adapter-facing {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker}:
   * the process-service beans register every workflow service class under all BPMN
   * process IDs it declares; adapters validate the wiring during
   * <code>wireBpmn</code> and dispatch task invocations at runtime. Handlers run via
   * the Spring transaction runner (a lazily resolved
   * {@link org.springframework.transaction.PlatformTransactionManager} - an
   * application without transactional persistence still boots and gets a guiding
   * message when the first task is processed).
   *
   * @param platformTransactionRunner The platform's transaction runner, resolving the
   *          transaction manager lazily
   * @param aggregateSync The core's sync model - validated per registered
   *          workflow-aggregate class at startup and used to answer the
   *          shared values of an aggregate an adapter does not hold
   * @return The workflow-task registry
   */
  @Bean
  public WorkflowTaskRegistry vanillaBpWorkflowTaskRegistry(
      final SpringTransactionRunner platformTransactionRunner,
      final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync aggregateSync,
      @org.springframework.beans.factory.annotation.Qualifier(
        BEANNAME_MIGRATIONADAPERPROPERTIES) final MigrationAdapterProperties properties) {

    return new WorkflowTaskRegistry(
        platformTransactionRunner, aggregateSync, io.vanillabp.integration.workflowtask.SpringTransactionAnnotations
            .specs(), properties);

  }

  /**
   * The handler methods of the extensions - an extension registers its own annotation
   * here and invokes the methods carrying it. It is the registry's own object rather
   * than a second one, because the methods are scanned from the same registration call
   * the <code>&#64;WorkflowTask</code> methods are.
   *
   * @param workflowTaskRegistry The registry owning it
   * @return The extension-facing handler service
   */
  @Bean
  public io.vanillabp.integration.extension.spi.handler.ExtensionHandlers vanillaBpExtensionHandlers(
      final WorkflowTaskRegistry workflowTaskRegistry) {

    return workflowTaskRegistry.getExtensionHandlers();

  }

  /**
   * The election, offered to extensions: which BPMS holds a given workflow right now.
   * An extension addressing the first-priority adapter instead would talk to the wrong
   * BPMS for every workflow a migration has already moved.
   *
   * @param phaseTwoRouter The router holding the process services of this application
   * @return The extension-facing election
   */
  @Bean
  public io.vanillabp.integration.extension.spi.election.WorkflowElection vanillaBpWorkflowElection(
      final PhaseTwoRouter phaseTwoRouter) {

    return new io.vanillabp.integration.adapter.migration.processservice.ExtensionWorkflowElection(phaseTwoRouter);

  }

  /**
   * The platform's transaction runner, used for every workflow aggregate the application
   * did not contribute a runner for. One instance for the whole application,
   * because it also answers whether the transaction it is running was marked
   * rollback-only and that answer belongs to the instance which opened it.
   *
   * @param transactionManager The application's transaction manager, resolved lazily - an
   *          application without transactional persistence still boots, and the startup
   *          check of the process services names every way to give it one
   * @return The platform's runner
   */
  @Bean
  public SpringTransactionRunner vanillaBpPlatformTransactionRunner(
      final org.springframework.beans.factory.ObjectProvider<org.springframework.transaction.PlatformTransactionManager> transactionManager) {

    return new SpringTransactionRunner(transactionManager);

  }

  /**
   * Resolves the transaction runner per workflow aggregate - injected into the
   * process-service beans and invoked at startup by
   * {@link #vanillaBpProcessServiceStartupValidation}.
   *
   * @param applicationContext Used to look up runner/aware beans and repositories
   * @param platformTransactionRunner The platform's runner, the last of the four
   *          resolution steps
   * @return The resolver
   */
  @Bean
  public SpringTransactionRunnerResolver vanillaBpTransactionRunnerResolver(
      final org.springframework.context.ApplicationContext applicationContext,
      final SpringTransactionRunner platformTransactionRunner) {

    return new SpringTransactionRunnerResolver(applicationContext, platformTransactionRunner);

  }

  /**
   * The adapter-facing pre-commit hook: a BPMS adapter hands its phase-one check
   * here and it runs right before the transaction of the workflow aggregate commits, so the
   * window between the check and the phase-two dispatch stays as small as the platform
   * allows. The runner of the aggregate is resolved first, which is what makes an
   * application-owned unit of work the one being hooked into.
   *
   * @param transactionRunnerResolver Resolves the runner of a workflow aggregate
   * @return The pre-commit hook
   */
  @Bean
  @ConditionalOnMissingBean(io.vanillabp.integration.adapter.spi.PreCommitRegistrar.class)
  public io.vanillabp.integration.adapter.spi.PreCommitRegistrar vanillaBpPreCommitRegistrar(
      final SpringTransactionRunnerResolver transactionRunnerResolver) {

    return new SpringPreCommitRegistrar(transactionRunnerResolver);

  }

  /**
   * Resolves the phase-two outbox per workflow aggregate (mixed persistence,
   * dedicated outboxes) - injected into the process-service beans and invoked at
   * startup by {@link #vanillaBpProcessServiceStartupValidation}.
   *
   * @param applicationContext Used to look up outbox/aware beans and repositories
   * @return The resolver
   */
  @Bean
  public SpringPhaseTwoOutboxResolver vanillaBpPhaseTwoOutboxResolver(
      final org.springframework.context.ApplicationContext applicationContext) {

    return new SpringPhaseTwoOutboxResolver(applicationContext);

  }

  /**
   * Resolves the log of processed task deliveries per workflow aggregate (mixed
   * persistence, own stores) - injected into the process-service beans and invoked at
   * startup by {@link #vanillaBpProcessServiceStartupValidation}.
   *
   * @param applicationContext Used to look up log/aware beans and repositories
   * @return The resolver
   */
  @Bean
  public SpringTaskDeliveryLogResolver vanillaBpTaskDeliveryLogResolver(
      final org.springframework.context.ApplicationContext applicationContext) {

    return new SpringTaskDeliveryLogResolver(applicationContext);

  }

  /**
   * Startup validation of the process services, run once all singletons exist (so
   * no persistence infrastructure is materialized mid-bean-construction): the
   * phase-two outbox of every process service is resolved (per aggregate - mixed
   * persistence, dedicated outboxes) - a missing outbox fails the startup with a
   * guiding message instead of surfacing at the first workflow start. The log of processed task deliveries is
   * resolved in the same pass: a BPMS repeating deliveries without a log to remember
   * them is reported at startup, not at the first redelivery.
   *
   * @param beanFactory Used to iterate all process-service beans
   * @return The startup validation hook
   */
  @Bean
  public SmartInitializingSingleton vanillaBpProcessServiceStartupValidation(
      final ConfigurableListableBeanFactory beanFactory) {

    return () -> beanFactory
        .getBeanProvider(io.vanillabp.spi.process.ProcessService.class)
        .stream()
        .filter(ProcessServiceSpringBean.class::isInstance)
        .map(processServiceBean -> (ProcessServiceSpringBean<?>) processServiceBean)
        // once per DECLARED BPMN process id, not once per bean: a secondary or
        // declared-only id has prioritized adapters, an outbox and persisted leftovers of
        // its own, and a message which names the id it is about is the point of asking
        .flatMap(processService -> processService.getProcessServicesOfDeclaredIds().stream())
        .forEach(processService -> {
          // first of all: an adapter which cannot serve an operation every adapter has
          // to serve is a gap nothing later would report except a workflow standing
          // still
          processService.validateAdapterOperationsAtStartup();
          processService.validatePhaseTwoOutboxAtStartup();
          // after the outbox: an application without a store hears about the store
          // first, which is the more specific gap
          processService.validateTransactionRunnerAtStartup();
          processService.validateTaskDeliveryLogAtStartup();
          // last: it asks the stores the two checks above resolved, so an application
          // which needs neither is not made to materialize one for a question about it
          processService.validatePersistedAdapterIdsAtStartup();
        });

  }

}
