package io.vanillabp.integration.test.outbox;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import io.vanillabp.extension.sample.SampleNote;
import io.vanillabp.extension.sample.SampleNoteDetails;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;
import io.vanillabp.integration.extension.spi.handler.HandlerCall;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseOperationRegistry;
import io.vanillabp.integration.spi.PhaseTwoCall;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/**
 * Stands in for a real VanillaBP extension (e.g. the Business Cockpit) using the
 * outbox for its own crash-safe after-commit work: it registers an operation of its
 * own in the {@link PhaseOperationRegistry} and records the calls dispatched to
 * it.
 * <p>
 * Everything an extension needs is used here: a namespaced operation name, an
 * idempotency key of its own making, arguments travelling with the call and a
 * dispatch which is NOT routed through the aggregate-to-adapter election of the
 * core operations.
 */
@RequiredArgsConstructor
public class SampleExtension {

  public static final String OPERATION_NAME = "sample-extension:NOTIFY";

  public static final String ARG_EVENT = "event";

  private final PhaseOperationRegistry registry;

  private final ExtensionHandlers handlers;

  private final List<PhaseTwoCall> dispatched = new CopyOnWriteArrayList<>();

  private volatile int failNextDispatches;

  private volatile int reportAndFailNextDispatches;

  private final java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();

  /**
   * The operation contributed by this extension: deduplicated per workflow
   * aggregate AND event, so the same event is published at most once while
   * different events of the same workflow are all published.
   */
  public static final PhaseOperation OPERATION = PhaseOperation
      .extensionOperation(OPERATION_NAME)
      .idempotencyKey(
          call -> Optional
              .of(
                  "%s|%s|%s|%s".formatted(
                      call.workflowModuleId(),
                      call.bpmnProcessId(),
                      call.workflowAggregateId(),
                      call.args().get(ARG_EVENT))))
      .describedAs(args -> "notifying about event '%s'".formatted(args.get(ARG_EVENT)))
      .build();

  @PostConstruct
  public void registerOperation() {

    registry
        .register(
            OPERATION,
            (
                call,
                previouslyAttempted) -> {
              attempts.incrementAndGet();
              if (reportAndFailNextDispatches > 0) {
                reportAndFailNextDispatches--;
                runTheReportingHandler(
                    call.workflowModuleId(),
                    call.bpmnProcessId(),
                    call.workflowAggregateId());
                throw new RuntimeException("test dispatch failure behind the handler call");
              }
              if (failNextDispatches > 0) {
                failNextDispatches--;
                throw new RuntimeException("test dispatch failure");
              }
              dispatched.add(call);
            });

  }

  /**
   * Builds a call of this extension's operation - what the extension would
   * schedule inside the business transaction.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowAggregateId The aggregate's ID in serialized form
   * @param event The event to be published
   * @return The call to be scheduled
   */
  public static PhaseTwoCall call(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String event) {

    return PhaseTwoCall
        .of(
            OPERATION, workflowModuleId, bpmnProcessId, workflowAggregateId, null, Map.of(ARG_EVENT, event));

  }

  /**
   * Lets VanillaBP run the handler which writes while it reports, the way a real extension
   * asks for one of its provider methods.
   *
   * @param workflowModuleId The workflow module of the workflow
   * @param bpmnProcessId The BPMN process of the workflow
   * @param workflowAggregateId The aggregate's ID in serialized form
   * @return What the handler returned, empty where no handler serves that element
   */
  public Optional<Object> runTheReportingHandler(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    return handlers
        .invoke(
            HandlerCall
                .of(SampleNote.class, workflowModuleId, bpmnProcessId)
                .lookupKeys(List.of(SampleWorkflowService.REPORTED_ELEMENT))
                .workflowAggregateId(workflowAggregateId)
                .payload(
                    new SampleNoteDetails(
                        SampleWorkflowService.REPORTED_ELEMENT, SampleNoteDetails.Kind.CREATED, "reported"))
                .build());

  }

  /**
   * How often the dispatch of this extension's operation was entered, the attempts which
   * threw included - what tells a failed attempt apart from one which never happened.
   *
   * @return The number of attempts since the last reset
   */
  public int getAttempts() {

    return attempts.get();

  }

  public List<PhaseTwoCall> getDispatched() {

    return dispatched;

  }

  public void reset() {

    dispatched.clear();
    failNextDispatches = 0;
    reportAndFailNextDispatches = 0;
    attempts.set(0);

  }

  public void failNextDispatches(
      final int count) {

    failNextDispatches = count;

  }

  /**
   * Lets the next dispatches run the reporting handler and fail afterwards - the shape of a
   * notification whose handler wrote something and whose publishing broke right behind it.
   *
   * @param count The number of dispatches to fail that way
   */
  public void reportAndFailNextDispatches(
      final int count) {

    reportAndFailNextDispatches = count;

  }

  /**
   * Waits until the given number of calls was dispatched.
   *
   * @param count The number of calls awaited
   * @param timeoutMillis The maximum time to wait
   * @return The dispatched calls
   * @throws InterruptedException If interrupted while waiting
   */
  public List<PhaseTwoCall> awaitDispatched(
      final int count,
      final long timeoutMillis) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + timeoutMillis;
    while (dispatched.size() < count) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError(
            "Only %d of %d expected extension-operation dispatches happened".formatted(
                dispatched.size(),
                count));
      }
      Thread.sleep(50);
    }
    return List.copyOf(dispatched);

  }

}
