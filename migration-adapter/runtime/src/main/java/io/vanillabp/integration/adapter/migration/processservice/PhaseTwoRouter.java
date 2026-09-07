package io.vanillabp.integration.adapter.migration.processservice;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseOperationDispatch;
import io.vanillabp.integration.spi.PhaseOperationRegistry;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.TransactionRunner;

/**
 * Core-owned router dispatching {@link PhaseTwoCall}s scheduled via
 * {@link PhaseTwoOutbox} to the {@link MigrationProcessService} of the workflow
 * module/BPMN process:
 *
 * <pre>
 * PhaseTwoOutbox (store)
 *     --&gt; PhaseTwoRouter.dispatch(call)
 *         --&gt; MigrationProcessService (adapter selection)
 *             --&gt; MigratableProcessService (BPMS adapter)
 * </pre>
 *
 * The platform integration registers every process-service bean at bean-creation
 * time. The serialized (String) workflow-aggregate ID of an outbox entry is
 * converted back into the aggregate's ID type by the process service itself
 * ({@link MigrationProcessService#convertAggregateId} - the ID type comes from the
 * aggregate's persistence support), so the conversion happens exactly once, here.
 * <p>
 * WHICH operations exist is not hardcoded here: the router owns a
 * {@link PhaseOperationRegistry} and registers the dispatch of VanillaBP's core
 * operations into it while it is built. An extension registers its own operations
 * in the same registry (see {@link #getOperations()}) and receives their calls in
 * its own {@link io.vanillabp.integration.spi.PhaseOperationDispatch} - the
 * process-service routing below applies to core operations only.
 * <p>
 * Why anything reaches this router at all instead of running in the caller's transaction is
 * decision 2 in the repository's DECISIONS.md; why every dispatch is wrapped in the transaction
 * runner of ITS aggregate is decision 11 in the repository's DECISIONS.md.
 */
// see decision 1 in the repository's DECISIONS.md
@SuppressWarnings("LombokGetterMayBeUsed")
public final class PhaseTwoRouter {

  private record RegistrationKey(
                                 String workflowModuleId,
                                 String bpmnProcessId) {
  }

  private final Map<RegistrationKey, MigrationProcessService<?>> registrations = new ConcurrentHashMap<>();

  private final PhaseOperationRegistry operations;

  /**
   * Provides the transaction (and whatever else the platform needs, e.g. an active
   * CDI request context on Quarkus) the dispatch runs in, or <code>null</code> if
   * the platform's outbox implementations bring their own (Spring Boot: gruelbox
   * dispatches inside a transaction it manages).
   */
  private final TransactionRunner transactionRunner;

  /**
   * What the application counts about its outbox. Handed in by the
   * platform integration;
   * {@link io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics#NONE}
   * for an application without a metrics backend.
   */
  private volatile io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics metrics = io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.NONE;

  /**
   * @param metrics What to count dispatches into, never <code>null</code>
   */
  public void setMetrics(
      final io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics metrics) {

    this.metrics = metrics == null
        ? io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.NONE
        : metrics;

  }

  public PhaseTwoRouter() {

    this(new PhaseOperationRegistry(), null);

  }

  /**
   * @param operations The registry to register the core operations in and to
   *        resolve dispatched operations from
   */
  public PhaseTwoRouter(
      final PhaseOperationRegistry operations) {

    this(operations, null);

  }

  /**
   * @param transactionRunner Provides the transaction dispatching runs in, see
   *        {@link #transactionRunner}
   */
  public PhaseTwoRouter(
      final TransactionRunner transactionRunner) {

    this(new PhaseOperationRegistry(), transactionRunner);

  }

  /**
   * @param operations The registry to register the core operations in and to
   *        resolve dispatched operations from
   * @param transactionRunner Provides the transaction dispatching runs in, see
   *        {@link #transactionRunner}
   */
  public PhaseTwoRouter(
      final PhaseOperationRegistry operations,
      final TransactionRunner transactionRunner) {

    this.operations = operations;
    this.transactionRunner = transactionRunner;
    registerCoreOperations();

  }

  /**
   * The registry of phase-two operations - extensions register their own
   * operations here (the platform integrations offer it as a bean).
   *
   * @return The operation registry used by this router
   */
  public PhaseOperationRegistry getOperations() {

    return operations;

  }

  /**
   * Register the process service of a workflow module/BPMN process as dispatch
   * target, called by the platform integration at bean-creation time.
   *
   * @param processService The process service to route calls to
   */
  public void register(
      final MigrationProcessService<?> processService) {

    registrations.put(
        new RegistrationKey(
            processService.getWorkflowModuleId(), processService.getBpmnProcessId()),
        processService);

  }

  /**
   * The process service of a workflow module's BPMN process - what a caller outside the
   * dispatch needs when it has a workflow module and a BPMN process and nothing else.
   * The router is the one place where all of them are collected.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The process service, or <code>null</code> if this application serves no such
   *         BPMN process
   */
  public MigrationProcessService<?> processServiceOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return registrations.get(new RegistrationKey(workflowModuleId, bpmnProcessId));

  }

  /**
   * The workflow modules and BPMN processes this application serves, for guiding
   * messages naming what a caller could have meant.
   *
   * @return One <code>&lt;module&gt;/&lt;process&gt;</code> per registration, sorted
   */
  public java.util.List<String> registeredWorkflows() {

    return registrations
        .keySet()
        .stream()
        .map(key -> "%s/%s".formatted(key.workflowModuleId(), key.bpmnProcessId()))
        .sorted()
        .toList();

  }

  /**
   * Dispatch the given phase-two call to the process service registered for its
   * workflow module/BPMN process.
   *
   * @param call The phase-two call to dispatch
   * @throws IllegalStateException If no process service is registered for the
   *         call's workflow module/BPMN process (e.g. the BPMN process is no longer
   *         part of this application) - the outbox entry stays visible in the
   *         outbox store for operations
   */
  public void dispatch(
      final PhaseTwoCall call) {

    dispatch(call, false);

  }

  /**
   * Dispatch the given phase-two call to the process service registered for its
   * workflow module/BPMN process.
   *
   * @param call The phase-two call to dispatch
   * @param previouslyAttempted Whether the outbox entry was dispatched before (a
   *        recovered or retried entry) - START operations then run the
   *        re-dispatch mitigation (probe the recorded adapter's workflow
   *        awareness first; a workflow already known there consumes the entry
   *        without a second start). Stores which cannot tell pass
   *        <code>false</code> - the mitigation is best-effort, the residual
   *        at-least-once window is accepted.
   * @throws IllegalStateException If no process service is registered for the
   *         call's workflow module/BPMN process (e.g. the BPMN process is no longer
   *         part of this application) - the outbox entry stays visible in the
   *         outbox store for operations
   */
  public void dispatch(
      final PhaseTwoCall call,
      final boolean previouslyAttempted) {

    final var dispatch = operations
        .dispatchFor(call.operation())
        .orElseThrow(() -> new IllegalStateException(
            """
                Cannot dispatch outbox entry (operation '%s', aggregate ID '%s'): no phase-two \
                operation of that name is registered! Registered operations: %s. Either the entry was \
                written by a newer version of your software, or the extension contributing the \
                operation is no longer part of this application - the entry stays in the outbox store \
                for operations until the operation is available again or the entry is removed."""
                .formatted(
                    call.operation(),
                    call.workflowAggregateId(),
                    String.join(", ", operations.registeredNames()))));

    // phase two runs on the outbox dispatcher's own thread and calls back into the
    // application (the aggregate has to be loaded to build what the BPMS is told).
    // Whatever that needs - a transaction, an active CDI request context - is
    // VanillaBP's to provide here, once for every outbox implementation, instead of
    // in each of them. Which transaction it is, belongs to the aggregate of the entry:
    // an application storing this aggregate in a system of its own gets
    // its own runner here as well, and an entry of an extension - which routes to no
    // process service - gets the platform's.
    final var runner = runnerFor(call);
    metrics.outboxDispatchStarted(call.operation(), previouslyAttempted);
    // Whatever the dispatch logs - and a broken BPMS connection logs a lot -
    // names the workflow it belongs to, exactly like a task delivery does
    try (var ignored = io.vanillabp.integration.adapter.migration.observability.DeliveryMdc
        .ofPhaseTwoDispatch(
            call.adapterId(),
            call.workflowModuleId(),
            call.bpmnProcessId(),
            call.workflowAggregateId())) {
      if (runner == null) {
        dispatch.dispatch(call, previouslyAttempted);
        return;
      }
      runner.requireTransaction(() -> {
        dispatch.dispatch(call, previouslyAttempted);
        return null;
      });
    } catch (final RuntimeException e) {
      metrics
          .outboxDispatchFailed(
              call.operation(),
              io.vanillabp.integration.spi.PhaseTwoPermanentFailure.isPermanent(e));
      throw e;
    }

  }

  /**
   * The runner providing the transaction of a dispatch: the one serving the aggregate
   * of the call where a process service is registered for it, the platform's otherwise.
   *
   * @param call The call about to be dispatched
   * @return The runner or <code>null</code> if none is available at all (an outbox
   *         dispatching inside a transaction of its own, e.g. gruelbox on Spring Boot)
   */
  private TransactionRunner runnerFor(
      final PhaseTwoCall call) {

    final var processService = registrations
        .get(new RegistrationKey(call.workflowModuleId(), call.bpmnProcessId()));
    if (processService == null) {
      return transactionRunner;
    }
    final var resolved = processService.getTransactionRunner(transactionRunner);
    return resolved != null
        ? resolved
        : transactionRunner;

  }

  /**
   * Registers the dispatch of every operation the core owns: each of them routes to the
   * process service of the call's workflow module and BPMN process.
   */
  private void registerCoreOperations() {

    PhaseOperation.CORE_OPERATIONS
        .forEach(this::registerOperation);

  }

  /**
   * Registers an operation which is executed by the BPMS adapters rather than by
   * whoever contributed it: the router resolves the process service of the call's
   * workflow module and BPMN process, converts the aggregate ID and lets the process
   * service run the operation's phase two through the elected adapter's handler.
   * <p>
   * The core registers its own operations this way while this router is built. An
   * extension whose operation addresses a workflow the way a core operation does - it
   * says so through its {@link PhaseOperation#election()} - registers it here as well
   * and contributes a handler per adapter, instead of registering a dispatch of its own
   * in {@link #getOperations()}.
   *
   * @param operation The operation to route to the process services
   */
  public void registerOperation(
      final PhaseOperation operation) {

    final PhaseOperationDispatch dispatch = (
        call,
        previouslyAttempted) -> dispatchToProcessService(operation, call, previouslyAttempted);
    if (PhaseOperation.coreOperation(operation.name()).isPresent()) {
      operations.registerCoreOperation(operation, dispatch);
    } else {
      operations.register(operation, dispatch);
    }

  }

  /**
   * Resolves the process service of the call's workflow module and BPMN process,
   * converts the serialized aggregate ID and lets it execute phase two - the shared
   * dispatch of every operation an adapter executes.
   *
   * @throws IllegalStateException If no process service is registered for the call's
   *         workflow module/BPMN process
   */
  private void dispatchToProcessService(
      final PhaseOperation operation,
      final PhaseTwoCall call,
      final boolean previouslyAttempted) {

    final var processService = registrations
        .get(new RegistrationKey(call.workflowModuleId(), call.bpmnProcessId()));
    if (processService == null) {
      throw new IllegalStateException(
          """
              Cannot dispatch outbox entry (%s): BPMN process '%s' of workflow module '%s' is not \
              (or no longer) part of this application! If the process was removed on purpose, \
              remove the entry from the outbox store - it stays visible there for operations."""
              .formatted(
                  operation.describe(call.args()),
                  call.bpmnProcessId(),
                  call.workflowModuleId()));
    }

    // an operation which is not about ONE workflow carries no aggregate ID - there is
    // nothing to convert, and the routing by (module, process) is all that applies
    final var workflowAggregateId = call.workflowAggregateId() == null
        ? null
        : processService.convertAggregateId(call.workflowAggregateId());

    processService
        .executePhaseTwo(operation, workflowAggregateId, call.adapterId(), call.args(), previouslyAttempted);

  }

}
