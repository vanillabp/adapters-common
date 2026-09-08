package io.vanillabp.integration.processservice;

import java.util.List;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.migration.processservice.ProcessServiceBase;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessServiceSpringBean<A> extends ProcessServiceBase<A> {

  @Getter
  private final MigrationProcessService<A> migrationProcessService;

  /**
   * The process service of EVERY BPMN process id the workflow service classes of this
   * aggregate declare, the primary one first - set by the
   * {@link ProcessServiceBeanRegistrar}, which is the only place seeing all declaring classes
   * at once.
   * <p>
   * Everything configurable per workflow is configurable for a secondary or declared-only id
   * too (its prioritized adapters, its outbox, what its leftovers were persisted under), and
   * each of those services asks its own questions. So the startup validations run over this
   * list rather than over the primary service alone - the id a rename leaves behind is exactly
   * the one whose leftovers nobody would otherwise look at.
   */
  private List<MigrationProcessService<A>> processServicesOfDeclaredIds;

  /**
   * Creates the bean without a transaction-runner resolver - kept for tests; the bean
   * registrar always passes one.
   */
  public ProcessServiceSpringBean(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<A> workflowAggregateClass,
      final MigrationAdapterProperties properties,
      final AggregatePersistenceAware<A> aggregatePersistenceAware,
      final List<MigratableProcessService<A>> migratableProcessServices,
      final PhaseTwoOutboxResolver phaseTwoOutboxResolver,
      final PhaseTwoRouter phaseTwoRouter,
      final WorkflowAdapterCache workflowAdapterCache,
      final io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver taskDeliveryLogResolver) {

    this(
        workflowModuleId, bpmnProcessId, workflowAggregateClass, properties, aggregatePersistenceAware, migratableProcessServices, phaseTwoOutboxResolver, phaseTwoRouter, workflowAdapterCache, taskDeliveryLogResolver, null);

  }

  public ProcessServiceSpringBean(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<A> workflowAggregateClass,
      final MigrationAdapterProperties properties,
      final AggregatePersistenceAware<A> aggregatePersistenceAware,
      final List<MigratableProcessService<A>> migratableProcessServices,
      final PhaseTwoOutboxResolver phaseTwoOutboxResolver,
      final PhaseTwoRouter phaseTwoRouter,
      final WorkflowAdapterCache workflowAdapterCache,
      final io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver taskDeliveryLogResolver,
      final io.vanillabp.integration.adapter.migration.processservice.TransactionRunnerResolver transactionRunnerResolver) {

    migrationProcessService = MigrationProcessService
        .<A>forBpmnProcess(workflowModuleId, bpmnProcessId, workflowAggregateClass)
        .properties(properties)
        .aggregatePersistence(aggregatePersistenceAware)
        .processServices(migratableProcessServices)
        .phaseTwoOutboxResolver(phaseTwoOutboxResolver)
        .workflowAdapterCache(workflowAdapterCache)
        .taskDeliveryLogResolver(taskDeliveryLogResolver)
        .transactionRunnerResolver(transactionRunnerResolver)
        .build();

    // register as phase-two dispatch target: outbox entries for this workflow
    // module/BPMN process are routed here after the local transaction was committed
    if (phaseTwoRouter != null) {
      phaseTwoRouter.register(migrationProcessService);
    }

  }

  /**
   * @param processServicesOfDeclaredIds The process services of every declared BPMN process
   *          id, the primary one first
   */
  public void setProcessServicesOfDeclaredIds(
      final java.util.Collection<MigrationProcessService<A>> processServicesOfDeclaredIds) {

    this.processServicesOfDeclaredIds = (processServicesOfDeclaredIds == null) || processServicesOfDeclaredIds.isEmpty()
        ? null
        : List.copyOf(processServicesOfDeclaredIds);

  }

  /**
   * @return The process services of every declared BPMN process id, the primary one first;
   *         the primary one alone where nothing was set (a test constructing this bean)
   */
  public List<MigrationProcessService<A>> getProcessServicesOfDeclaredIds() {

    return processServicesOfDeclaredIds != null
        ? processServicesOfDeclaredIds
        : List.of(migrationProcessService);

  }

  /**
   * Stops the process service. Called by
   * {@link io.vanillabp.integration.deployment.SpringBootDeploymentService} on
   * graceful shutdown of the application.
   */
  public void stopService() {

    log.info("Stopping process service: {}", migrationProcessService.getWorkflowModuleId());

  }

  @Override
  public String getWorkflowModuleId() {

    return migrationProcessService.getWorkflowModuleId();

  }

  public String getBpmnProcessId() {

    return migrationProcessService.getBpmnProcessId();

  }

  public Class<A> getWorkflowAggregateClass() {

    return migrationProcessService.getWorkflowAggregateClass();

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

  public boolean transactionIsActive() {

    return !noTransactionIsActive();

  }

  /**
   * Whether nothing is open the aggregate could be persisted in. The question goes to the
   * runner serving this aggregate: an application storing its aggregates in a
   * system Spring does not manage has its own unit of work, and Spring's answer would be
   * wrong for it. Without a resolver (tests) Spring answers.
   */
  private boolean noTransactionIsActive() {

    final var runner = migrationProcessService.getTransactionRunner(null);
    return runner != null
        ? !runner.isTransactionActive()
        : !TransactionSynchronizationManager.isActualTransactionActive();

  }

}
