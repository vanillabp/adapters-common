package io.vanillabp.integration.adapter.migration.workflowstart;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartResult;
import io.vanillabp.integration.spi.TransactionRunner;

/**
 * Builds the workflow aggregate of a workflow the BPMS started on its own, in one
 * transaction: derive the ID, reuse an aggregate already carrying it, otherwise
 * instantiate one, write the ID and the process variables into it, let the optional
 * <code>&#64;WorkflowStartedByBpms</code> method have its say and save.
 */
public final class BpmsInitiatedStartExecution {

  private static final Logger log = LoggerFactory.getLogger(BpmsInitiatedStartExecution.class);

  private BpmsInitiatedStartExecution() {
  }

  /**
   * @param <A> The workflow-aggregate type
   * @param processService The process service of the BPMN process (persistence, ID
   *          type, aggregate class)
   * @param handler The application's method building or enriching the aggregate, or
   *          <code>null</code> if it does not have one
   * @param context The adapter's notification
   * @param transactionRunner The platform's transaction runner
   * @return The aggregate's ID and the variables the adapter writes back (the
   *         aggregate-ID variable; shared aggregate values are added by the caller)
   */
  public static <A> BpmsInitiatedStartResult run(
      final MigrationProcessService<A> processService,
      final BpmsInitiatedStartHandler handler,
      final BpmsInitiatedStartContext context,
      final TransactionRunner transactionRunner) {

    final Supplier<BpmsInitiatedStartResult> transactionalWork = () -> build(
        processService,
        handler,
        context);

    // the started instance is the activation the application's handler runs in, so
    // what it plans is told apart from what a second firing of the same start event
    // plans (see io.vanillabp.integration.spi.RunningActivation)
    try (var activation = io.vanillabp.integration.spi.RunningActivation
        .of(context.getNativeInstanceId())) {
      return io.vanillabp.integration.adapter.migration.transaction.AggregateWrite
          .inTransaction(
              transactionRunner,
              io.vanillabp.integration.adapter.migration.transaction.TransactionForm
                  .askedForBy(context.runInCurrentTransaction()),
              processService.getWorkflowModuleId(),
              processService.getBpmnProcessId(),
              context.getNaturalIdentity(),
              "the BPMS-initiated start at start event '%s'".formatted(context.getStartEventId()),
              transactionalWork);
    }

  }

  private static <A> BpmsInitiatedStartResult build(
      final MigrationProcessService<A> processService,
      final BpmsInitiatedStartHandler handler,
      final BpmsInitiatedStartContext context) {

    final var aggregateClass = processService.getWorkflowAggregateClass();
    final var derivedId = BpmsInitiatedStartId
        .derive(
            context.getKind(),
            context.getStartInstant(),
            context.getNaturalIdentity(),
            processService.getAggregateIdType(),
            aggregateClass);

    if (derivedId.isPresent()) {
      final var existing = processService.loadWorkflowAggregateById(derivedId.get());
      if (existing != null) {
        // at-least-once: the BPMS reported this start before (a retried listener
        // job, a replayed engine transaction) - the workflow already has its
        // aggregate, and building a second one would overwrite business data
        log
            .debug(
                "The workflow aggregate '{}' of the BPMS-initiated start of BPMN process '{}' "
                    + "(workflow module '{}', start event '{}') exists already - nothing is created",
                derivedId.get(),
                processService.getBpmnProcessId(),
                processService.getWorkflowModuleId(),
                context.getStartEventId());
        return result(processService, existing, false);
      }
    }

    final var built = instantiate(processService, context);
    if (derivedId.isPresent()) {
      AggregatePropertyWriter
          .write(
              built,
              processService.getAggregateIdName(),
              derivedId.get(),
              "the ID of the workflow aggregate '%s'".formatted(aggregateClass.getName()));
    }
    writeVariables(processService, built, context);
    var workflowAggregate = built;

    if (handler != null) {
      final var returned = handler.invoke(workflowAggregate, context);
      if (handler.isReturningAggregate()) {
        if (returned == null) {
          throw new IllegalStateException(
              """
                  The @WorkflowStartedByBpms method '%s' returned null! Return the workflow aggregate \
                  of the workflow the BPMS started (BPMN process '%s' of workflow module '%s', start \
                  event '%s') - without it the workflow has no data at all."""
                  .formatted(
                      handler.describe(),
                      processService.getBpmnProcessId(),
                      processService.getWorkflowModuleId(),
                      context.getStartEventId()));
        }
        workflowAggregate = aggregateClass.cast(returned);
        // an aggregate built by the application may carry no ID yet - the derived
        // one applies unless the application assigned its own
        if ((processService.getWorkflowAggregateId(workflowAggregate) == null) && derivedId.isPresent()) {
          AggregatePropertyWriter
              .write(
                  workflowAggregate,
                  processService.getAggregateIdName(),
                  derivedId.get(),
                  "the ID of the workflow aggregate '%s'".formatted(aggregateClass.getName()));
        }
      }
    }

    final var attached = processService.saveWorkflowAggregate(workflowAggregate);
    final var aggregateId = processService.getWorkflowAggregateId(attached);
    if ((aggregateId == null) || aggregateId.toString().isBlank()) {
      throw new IllegalStateException(
          """
              The ID of the workflow aggregate of class '%s' is null or blank after saving the \
              workflow the BPMS started (BPMN process '%s' of workflow module '%s', start event \
              '%s')! The ID identifies the workflow in the BPMS - use a generated ID which is \
              assigned on save, or assign one in a @WorkflowStartedByBpms method."""
              .formatted(
                  aggregateClass.getName(),
                  processService.getBpmnProcessId(),
                  processService.getWorkflowModuleId(),
                  context.getStartEventId()));
    }
    return result(processService, attached, true);

  }

  private static <A> A instantiate(
      final MigrationProcessService<A> processService,
      final BpmsInitiatedStartContext context) {

    final var aggregateClass = processService.getWorkflowAggregateClass();
    try {
      final var constructor = aggregateClass.getDeclaredConstructor();
      constructor.trySetAccessible();
      return constructor.newInstance();
    } catch (final ReflectiveOperationException | RuntimeException e) {
      throw new IllegalStateException(
          """
              The workflow aggregate '%s' cannot be instantiated for the workflow the BPMS started \
              (BPMN process '%s' of workflow module '%s', start event '%s')! Give the class an \
              accessible constructor without arguments, or add a @WorkflowStartedByBpms method to \
              its workflow service which returns the aggregate it built itself."""
              .formatted(
                  aggregateClass.getName(),
                  processService.getBpmnProcessId(),
                  processService.getWorkflowModuleId(),
                  context.getStartEventId()), e);
    }

  }

  /**
   * Copies the process variables into equally-named attributes of the aggregate.
   * Variables the aggregate does not model are skipped: a BPMN model may carry
   * values which are none of the application's business.
   */
  private static <A> void writeVariables(
      final MigrationProcessService<A> processService,
      final A workflowAggregate,
      final BpmsInitiatedStartContext context) {

    final var aggregateIdName = processService.getAggregateIdName();
    for (final var variable : context.getVariables().entrySet()) {
      if (variable.getKey().equals(aggregateIdName)) {
        continue;
      }
      final var written = AggregatePropertyWriter
          .write(
              workflowAggregate,
              variable.getKey(),
              variable.getValue(),
              "the process variable '%s' into the workflow aggregate '%s'".formatted(
                  variable.getKey(),
                  processService.getWorkflowAggregateClass().getName()));
      if (!written) {
        log
            .debug(
                "The workflow aggregate '{}' has no attribute '{}' - the process variable of the "
                    + "BPMS-initiated start of BPMN process '{}' (workflow module '{}') is not copied",
                processService.getWorkflowAggregateClass().getName(),
                variable.getKey(),
                processService.getBpmnProcessId(),
                processService.getWorkflowModuleId());
      }
    }

  }

  private static <A> BpmsInitiatedStartResult result(
      final MigrationProcessService<A> processService,
      final A workflowAggregate,
      final boolean created) {

    final var aggregateIdName = processService.getAggregateIdName();
    final var serializedId = String.valueOf(processService.getWorkflowAggregateId(workflowAggregate));
    final Map<String, Object> variables = new LinkedHashMap<>();
    variables.put(aggregateIdName, serializedId);
    return new BpmsInitiatedStartResult(serializedId, aggregateIdName, variables, created);

  }

}
