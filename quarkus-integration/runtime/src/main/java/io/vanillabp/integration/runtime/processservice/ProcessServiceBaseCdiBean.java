package io.vanillabp.integration.runtime.processservice;

import java.util.List;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.migration.processservice.ProcessServiceBase;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionSynchronizationRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class ProcessServiceBaseCdiBean<A> extends ProcessServiceBase<A> {

  @Inject
  MigrationAdapterProperties properties;

  /**
   * The persistences available at runtime. This is injected using {@link Instance} because the services
   * annotated by {@link io.vanillabp.spi.service.WorkflowService} may implement {@link AggregatePersistenceAware}
   * what causes cycle dependencies since this bean is typically also injected into the service.
   */
  @Inject
  @Any
  Instance<AggregatePersistenceAware<?>> persistences;

  /**
   * The process services of the VanillaBP adapters available at runtime. The
   * convention is one <i>element</i> bean per adapter (never a bean of type
   * <code>List&lt;MigratableProcessService&gt;</code>) so several adapter types
   * coexist in one application.
   */
  @Inject
  @Any
  Instance<io.vanillabp.integration.adapter.spi.MigratableProcessService<?>> migratableProcessServices;

  /**
   * Additionally accepted shape: beans of type
   * <code>List&lt;MigratableProcessService&lt;Object&gt;&gt;</code>, flattened into
   * the collected process services. Synthetic beans created from runtime
   * configuration (one process service per configured adapter id,
   * adapter config model) cannot be registered as individual element
   * beans on Quarkus - a single List bean per adapter is the documented shape
   * there. The element type parameter is LITERALLY {@code Object} by convention
   * (CDI's parameterized-type matching of nested wildcards is not reliable across
   * modes, so the platform looks the beans up with the exact type).
   */
  @Inject
  @Any
  Instance<List<io.vanillabp.integration.adapter.spi.MigratableProcessService<Object>>> migratableProcessServiceLists;

  /**
   * Which outbox an aggregate's transaction reaches (mixed persistence, dedicated
   * outboxes). The bean of {@link PhaseTwoOutboxResolverProducer} rather than a
   * resolver of this process service's own: an extension writing entries of its own
   * has to reach the same answer, and two constructions would be two answers as soon
   * as one of them changes.
   */
  @Inject
  PhaseTwoOutboxResolver phaseTwoOutboxResolver;

  /**
   * The logs of processed task deliveries available at runtime, resolved per aggregate
   * (mixed persistence) via {@link QuarkusTaskDeliveryLogResolver}. Unsatisfied if no
   * implementation is available (e.g. no datasource configured) - a BPMS repeating a
   * delivery then runs the handler again, which the startup validation reports.
   */
  @Inject
  @Any
  Instance<io.vanillabp.integration.spi.TaskDeliveryLog> taskDeliveryLogs;

  /**
   * Application-provided attributions of aggregates to delivery logs (required in
   * mixed-persistence setups, optional otherwise).
   */
  @Inject
  @Any
  Instance<io.vanillabp.integration.spi.TaskDeliveryLogAware<?>> taskDeliveryLogAwares;

  @Inject
  TransactionSynchronizationRegistry txRegistry;

  /**
   * Application-provided attributions of aggregates to transaction runners - the hook of
   * for a storage the platform does not manage.
   */
  @Inject
  @Any
  Instance<io.vanillabp.integration.spi.TransactionRunnerAware<?>> transactionRunnerAwares;

  /**
   * A transaction runner of the application serving every aggregate no
   * {@link io.vanillabp.integration.spi.TransactionRunnerAware} covers. VanillaBP's own
   * runner is not a bean, so whatever is found here belongs to the application.
   */
  @Inject
  @Any
  Instance<io.vanillabp.integration.spi.TransactionRunner> applicationTransactionRunners;

  /**
   * The aggregate persistences of the application, used by the startup check to tell
   * whether the transaction VanillaBP opens covers the store of an aggregate (a MongoDB
   * Panache one takes part in it, a persistence of the application cannot be judged).
   */
  @Inject
  @Any
  Instance<AggregatePersistenceAware<?>> aggregatePersistences;

  /**
   * Answers whether the MongoDB deployment is a replica set, which the startup check needs
   * for an aggregate MongoDB Panache manages. Unsatisfied in an application without the
   * MongoDB client extension - see {@link MongoDeploymentProbe}.
   */
  @Inject
  Instance<MongoDeploymentProbe> mongoDeploymentProbes;

  /**
   * The core-owned router dispatching committed phase-two outbox entries. This bean
   * registers itself (including the aggregate-ID converter) at bean creation.
   */
  @Inject
  Instance<PhaseTwoRouter> phaseTwoRouter;

  /**
   * The core-owned registry of <code>&#64;WorkflowTask</code> handlers. Resolved via
   * {@link Instance} for the same cycle reasons as the other collaborators.
   */
  @Inject
  Instance<io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry> workflowTaskRegistry;

  /**
   * The cache of workflow&rarr;adapter associations consulted by the BPMS election
   * (the platform's in-memory default or the application's own bean, e.g.
   * cluster-shared).
   */
  @Inject
  Instance<io.vanillabp.integration.spi.WorkflowAdapterCache> workflowAdapterCache;

  /**
   * The application-wide numbers of the election cache - every lookup of whatever
   * cache is in use is counted into this one instance.
   */
  @Inject
  Instance<io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheStatistics> workflowAdapterCacheStatistics;

  /**
   * What deliveries of this process are counted into; unsatisfied where
   * the application uses no Micrometer extension.
   */
  @Inject
  Instance<io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics> vanillaBpMetrics;

  @Getter
  MigrationProcessService<A> migrationProcessService;

  /**
   * The process services of every declared BPMN process id, the primary one first - see
   * {@link #getProcessServicesOfDeclaredIds()}.
   */
  private List<MigrationProcessService<A>> processServicesOfDeclaredIds;

  public abstract Class<AggregatePersistenceAware<A>> getAggregatePersistenceClass();

  public abstract Class<A> getWorkflowAggregateClass();

  public abstract String getBpmnProcessId();

  /**
   * All (workflow module, workflow service class, BPMN process ID) combinations of
   * the aggregate this process service serves, determined at build time: every
   * class declaring the aggregate contributes all its declared BPMN process IDs
   * ({@code @WorkflowService.bpmnProcess} and {@code secondaryBpmnProcesses}).
   * Encoding: entries {@code <module>|<class name>|<bpmn process id>} joined by
   * {@code ';'} (generated as a class-file constant - Gizmo-friendly).
   */
  public abstract String getWorkflowTaskRegistrations();

  @PostConstruct
  public void initialize() {

    // collect element beans plus flattened List beans (see field javadoc)
    @SuppressWarnings("unchecked")
    final List<io.vanillabp.integration.adapter.spi.MigratableProcessService<A>> processServices = java.util.stream.Stream
        .concat(
            migratableProcessServices.stream(),
            migratableProcessServiceLists
                .stream()
                .filter(java.util.Objects::nonNull)
                .flatMap(List::stream))
        .filter(java.util.Objects::nonNull)
        .map(processService -> (io.vanillabp.integration.adapter.spi.MigratableProcessService<A>) processService)
        .toList();

    final var outboxProperties = ConfigProvider
        .getConfig()
        .unwrap(SmallRyeConfig.class)
        .getConfigMapping(QuarkusMigrationAdapterProperties.class)
        .outbox();
    // which store an aggregate's transaction reaches: read off the persistence VanillaBP
    // resolved for it, so an application with two persistences attributes nothing itself
    final var persistenceTechnology = new QuarkusPersistenceTechnology(aggregatePersistences);
    final var transactionRunnerResolver = new QuarkusTransactionRunnerResolver(
        transactionRunnerAwares, applicationTransactionRunners, aggregatePersistences, mongoDeploymentProbes, new io.vanillabp.integration.runtime.workflowtask.QuarkusTransactionRunner(
            txRegistry));
    final var taskDeliveryLogResolver = new QuarkusTaskDeliveryLogResolver(
        taskDeliveryLogAwares, taskDeliveryLogs, persistenceTechnology, outboxProperties
            .jdbc()
            .enabled(), outboxProperties
                .mongo()
                .enabled());
    final var electionCache = io.vanillabp.integration.adapter.migration.processservice.InstrumentedWorkflowAdapterCache
        .instrument(
            workflowAdapterCache.isResolvable()
                ? workflowAdapterCache.get()
                : null,
            workflowAdapterCacheStatistics.isResolvable()
                ? workflowAdapterCacheStatistics.get()
                : null);
    this.migrationProcessService = MigrationProcessService
        .forBpmnProcess(getWorkflowModuleId(), getBpmnProcessId(), getWorkflowAggregateClass())
        .properties(properties)
        .aggregatePersistence(getAggregatePersistence())
        .processServices(processServices)
        .phaseTwoOutboxResolver(phaseTwoOutboxResolver)
        .workflowAdapterCache(electionCache)
        .taskDeliveryLogResolver(taskDeliveryLogResolver)
        .transactionRunnerResolver(transactionRunnerResolver)
        .build();
    this.migrationProcessService
        .setMetrics(PhaseTwoRouterProducer.vanillaBpMetricsOf(vanillaBpMetrics));

    // register as phase-two dispatch target: outbox entries for this workflow
    // module/BPMN process are routed here after the local transaction was committed
    if (phaseTwoRouter.isResolvable()) {
      phaseTwoRouter
          .get()
          .register(migrationProcessService);
    }

    registerWorkflowTaskHandlers(
        processServices, phaseTwoOutboxResolver, electionCache, taskDeliveryLogResolver, transactionRunnerResolver);

  }

  /**
   * Registers all workflow service classes of this aggregate under all BPMN process
   * IDs they declare (build-time facts, see {@link #getWorkflowTaskRegistrations()})
   * with the core's workflow-task registry. Secondary BPMN processes get their own
   * {@link MigrationProcessService} (registered for phase-two routing, too); the
   * primary one is reused.
   */
  private void registerWorkflowTaskHandlers(
      final List<io.vanillabp.integration.adapter.spi.MigratableProcessService<A>> processServices,
      final PhaseTwoOutboxResolver phaseTwoOutboxResolver,
      final io.vanillabp.integration.spi.WorkflowAdapterCache electionCache,
      final QuarkusTaskDeliveryLogResolver taskDeliveryLogResolver,
      final QuarkusTransactionRunnerResolver transactionRunnerResolver) {

    if (!workflowTaskRegistry.isResolvable()) {
      return;
    }
    final var registry = workflowTaskRegistry.get();
    // insertion ordered, and the primary service goes in first: the startup validations run
    // over this list and a message about the primary id is the one a reader expects first
    final var processServicesByKey = new java.util.LinkedHashMap<String, MigrationProcessService<A>>();
    processServicesByKey.put(
        "%s|%s".formatted(getWorkflowModuleId(), getBpmnProcessId()),
        migrationProcessService);
    // An awareness probe is asked about a workflow module AND every BPMN
    // process serving this aggregate there, secondary processes of the same
    // @WorkflowService included - they run on the same workflow
    final var processIdsByModule = new java.util.LinkedHashMap<String, java.util.List<String>>();
    final var moduleOfProcessService = new java.util.LinkedHashMap<MigrationProcessService<A>, String>();
    moduleOfProcessService.put(migrationProcessService, getWorkflowModuleId());
    processIdsByModule
        .computeIfAbsent(getWorkflowModuleId(), module -> new java.util.LinkedList<>())
        .add(getBpmnProcessId());
    for (final var registration : getWorkflowTaskRegistrations().split(";")) {
      final var parts = registration.split("\\|");
      final var moduleId = parts[0];
      final var serviceClassName = parts[1];
      final var bpmnProcessId = parts[2];
      final Class<?> serviceClass;
      try {
        serviceClass = Class.forName(
            serviceClassName,
            false,
            Thread.currentThread().getContextClassLoader());
      } catch (final ClassNotFoundException e) {
        throw new IllegalStateException(
            "Workflow service class '%s' recorded at build time was not found at runtime!"
                .formatted(serviceClassName), e);
      }
      final var processService = processServicesByKey.computeIfAbsent(
          "%s|%s".formatted(moduleId, bpmnProcessId),
          key -> {
            final var secondaryProcessService = MigrationProcessService
                .forBpmnProcess(moduleId, bpmnProcessId, getWorkflowAggregateClass())
                .properties(properties)
                .aggregatePersistence(getAggregatePersistence())
                .processServices(processServices)
                .phaseTwoOutboxResolver(phaseTwoOutboxResolver)
                .workflowAdapterCache(electionCache)
                .taskDeliveryLogResolver(taskDeliveryLogResolver)
                .transactionRunnerResolver(transactionRunnerResolver)
                .build();
            secondaryProcessService
                .setMetrics(PhaseTwoRouterProducer.vanillaBpMetricsOf(vanillaBpMetrics));
            if (phaseTwoRouter.isResolvable()) {
              phaseTwoRouter
                  .get()
                  .register(secondaryProcessService);
            }
            return secondaryProcessService;
          });
      moduleOfProcessService.put(processService, moduleId);
      final var declaredIds = processIdsByModule
          .computeIfAbsent(moduleId, module -> new java.util.LinkedList<>());
      if (!declaredIds.contains(bpmnProcessId)) {
        declaredIds.add(bpmnProcessId);
      }
      registry.registerWorkflowService(
          moduleId,
          bpmnProcessId,
          serviceClass,
          () -> jakarta.enterprise.inject.spi.CDI
              .current()
              .select(serviceClass)
              .get(),
          type -> {
            final var candidates = jakarta.enterprise.inject.spi.CDI
                .current()
                .select(type);
            return candidates.isResolvable()
                ? candidates.get()
                : null;
          },
          processService);
    }

    // everything configurable per workflow is configurable for a secondary or declared-only
    // id as well, so the startup validations get every declared id rather than the primary
    // one alone
    processServicesOfDeclaredIds = List.copyOf(processServicesByKey.values());

    // every process service of this aggregate answers for the processes of ITS workflow
    // module
    moduleOfProcessService
        .forEach((
            processService,
            moduleId) -> processService.setServedBpmnProcessIds(processIdsByModule.get(moduleId)));

  }

  /**
   * Startup validation (observer methods are inherited by the generated
   * process-service beans): if the first-priority adapter of this process requires
   * a two-phase commit, the phase-two outbox is resolved AT STARTUP - a missing
   * outbox fails the boot with a guiding message instead of surfacing at the first
   * workflow start. The log of processed task deliveries is resolved in the same pass: a
   * BPMS repeating deliveries without a log to remember them is reported at startup, not
   * at the first redelivery.
   *
   * @param event The startup event observed
   */
  public void onStart(
      @Observes final StartupEvent event) {

    // once per DECLARED BPMN process id, not once per bean: a secondary or declared-only id
    // has prioritized adapters, an outbox and persisted leftovers of its own, and a message
    // which names the id it is about is the point of asking
    getProcessServicesOfDeclaredIds()
        .forEach(processService -> {
          // first of all: an adapter which cannot serve an operation every adapter has to
          // serve is a gap nothing later would report except a workflow standing still
          processService.validateAdapterOperationsAtStartup();
          processService.validatePhaseTwoOutboxAtStartup();
          // after the outbox: an application which configured a remote BPMS without a store
          // hears about the store first, which is the more specific gap
          processService.validateTransactionRunnerAtStartup();
          processService.validateTaskDeliveryLogAtStartup();
          // last: it asks the stores the two checks above resolved, so an application which
          // needs neither is not made to materialize one for a question about it
          processService.validatePersistedAdapterIdsAtStartup();
        });

  }

  /**
   * The process service of EVERY BPMN process id the workflow service classes of this
   * aggregate declare, the primary one first.
   * <p>
   * Everything configurable per workflow is configurable for a secondary or declared-only id
   * too (its prioritized adapters, its outbox, what its leftovers were persisted under), and
   * each of those services asks its own questions. So the startup validations run over this
   * list rather than over the primary service alone - the id a rename leaves behind is exactly
   * the one whose leftovers nobody would otherwise look at.
   *
   * @return The process services, the primary one first; the primary one alone where no
   *         workflow task registry was resolvable
   */
  public List<MigrationProcessService<A>> getProcessServicesOfDeclaredIds() {

    return processServicesOfDeclaredIds != null
        ? processServicesOfDeclaredIds
        : List.of(migrationProcessService);

  }

  /**
   * Stops this process service on graceful shutdown - invoked by the deployment
   * runner after workflow processing of adapters and extensions was stopped
   * (parity with the Spring Boot integration's
   * <code>ProcessServiceSpringBean.stopService()</code>).
   */
  public void stopService() {

    log.info("Stopping process service: {}", migrationProcessService.getWorkflowModuleId());

  }

  @Override
  public A startWorkflow(
      final A workflowAggregate) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.startWorkflow(workflowAggregate);

  }

  @Override
  public A completeTask(
      final A workflowAggregate,
      final String taskId) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.completeTask(workflowAggregate, taskId);

  }

  @Override
  public A cancelTask(
      final A workflowAggregate,
      final String taskId,
      final String bpmnErrorCode) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.cancelTask(workflowAggregate, taskId, bpmnErrorCode);

  }

  @Override
  public A completeUserTask(
      final A workflowAggregate,
      final String taskId) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.completeUserTask(workflowAggregate, taskId);

  }

  @Override
  public A cancelUserTask(
      final A workflowAggregate,
      final String taskId,
      final String bpmnErrorCode) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.cancelUserTask(workflowAggregate, taskId, bpmnErrorCode);

  }

  @Override
  public void sendSignal(
      final String signalName) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionExceptionForSignal();
    }

    migrationProcessService.sendSignal(signalName);

  }

  @Override
  public A aggregateChanged(
      final A workflowAggregate) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.aggregateChanged(workflowAggregate, null);

  }

  @Override
  public A aggregateChanged(
      final A workflowAggregate,
      final String taskId) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }
    if ((taskId == null) || taskId.isBlank()) {
      throw new IllegalArgumentException(
          """
              No task-id given! Use aggregateChanged(aggregate) to push the aggregate at the \
              workflow's global scope, or pass the task-id reported to the @TaskId parameter of the \
              task whose scope should receive the values.""");
    }

    return migrationProcessService.aggregateChanged(workflowAggregate, taskId);

  }

  @Override
  public A correlateMessage(
      final A workflowAggregate,
      final String messageName) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.correlateMessage(workflowAggregate, messageName, null);

  }

  @Override
  public A correlateMessage(
      final A workflowAggregate,
      final String messageName,
      final String correlationId) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.correlateMessage(workflowAggregate, messageName, correlationId);

  }

  @Override
  public A startWorkflowByMessage(
      final A workflowAggregate,
      final String messageName) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.startWorkflowByMessage(workflowAggregate, messageName);

  }

  /**
   * The viewer/history API is READ-ONLY: no transaction is required (nothing is
   * persisted, the aggregate is only asked for its ID) - see
   * {@link MigrationProcessService#getProcessDefinitions(Object, String)}.
   */
  @Override
  public List<io.vanillabp.spi.process.ProcessDefinition> getProcessDefinitions(
      final A workflowAggregate,
      final String historyContext) {

    return migrationProcessService.getProcessDefinitions(workflowAggregate, historyContext);

  }

  @Override
  public java.io.InputStream getBpmnXml(
      final String processDefinitionId) {

    return migrationProcessService.getBpmnXml(processDefinitionId);

  }

  @Override
  public io.vanillabp.spi.process.WorkflowHistory getWorkflowHistory(
      final A workflowAggregate,
      final String historyContext) {

    return migrationProcessService.getWorkflowHistory(workflowAggregate, historyContext);

  }

  @SuppressWarnings("unchecked")
  private AggregatePersistenceAware<A> getAggregatePersistence() {

    for (AggregatePersistenceAware<?> persistence : persistences) {
      if (getAggregatePersistenceClass().isAssignableFrom(persistence.getClass())) {
        return (AggregatePersistenceAware<A>) persistence;
      }
    }

    throw new IllegalStateException(
        "No persistence for "
            + getAggregatePersistenceClass()
            + " found at runtime. Maybe the class is not defined as a CDI bean?");

  }

  public boolean transactionIsActive() {

    return !noTransactionIsActive();

  }

  /**
   * Whether nothing is open the aggregate could be persisted in. The question goes to the
   * runner serving this aggregate: an application storing its aggregates in a
   * system JTA does not cover has its own unit of work, and the JTA answer would be wrong
   * for it.
   */
  public boolean noTransactionIsActive() {

    final var runner = migrationProcessService.getTransactionRunner(null);
    return runner != null
        ? !runner.isTransactionActive()
        : txRegistry.getTransactionKey() == null;

  }

}
