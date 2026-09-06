package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.test.activation.ActivationAggregate;
import io.vanillabp.integration.test.activation.ActivationAggregatePersistence;
import io.vanillabp.integration.test.activation.ActivationAwarenessSource;
import io.vanillabp.integration.test.activation.ActivationOutbox;
import io.vanillabp.integration.test.activation.ActivationOutboxAware;
import io.vanillabp.integration.test.activation.ActivationProcessWiringSource;
import io.vanillabp.integration.test.activation.ActivationWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * Acceptance test on Quarkus of what the identity of an activation buys: the dummy
 * adapter delivers the same task of the same workflow aggregate three times, once per
 * element of a multi-instance activity, and the handler correlates the same message name
 * with the same correlation id every time. All three correlations have to be planned.
 * <p>
 * Before this they shared one idempotency key - a called process is a secondary workflow
 * of the SAME aggregate, so module, process, aggregate id, message name and correlation
 * id were equal - and two of the three were discarded while the first one was still
 * waiting for its dispatch (see decision 23 in the repository's DECISIONS.md).
 * <p>
 * Pinned next to it: the guarantee this must not cost (a redelivery of ONE element is
 * still one correlation) and the case it deliberately does not fix (the same correlation
 * planned twice outside any activation).
 */
@ExtendWith(SuppressOutputExtension.class)
public class ActivationIdentityTest {

  private static final String MODULE = "activation-module";

  private static final String PROCESS = "ActivationProcess";

  private static final String ADAPTER = "demo1";

  private static final String AGGREGATE_ID = "4711";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("activation-identity/application.yaml", "application.yaml")
          .addClass(ActivationAggregate.class)
          .addClass(ActivationAggregatePersistence.class)
          .addClass(ActivationWorkflowService.class)
          .addClass(ActivationProcessWiringSource.class)
          .addClass(ActivationAwarenessSource.class)
          .addClass(ActivationOutbox.class)
          .addClass(ActivationOutboxAware.class)
          .addAsResource("bpmn/first.bpmn", "processes/activation/ActivationProcess.bpmn")
          .addAsResource("activation-identity/workflow-module", "META-INF/workflow-module"));

  @Inject
  ActivationAggregatePersistence persistence;

  @Inject
  ActivationOutbox outbox;

  @Inject
  io.vanillabp.spi.process.ProcessService<ActivationAggregate> processService;

  @Inject
  UserTransaction userTransaction;

  @Inject
  @Any
  Instance<List<AdapterDeploymentService<Object, Object>>> deploymentServices;

  @BeforeEach
  public void reset() {

    outbox.clear();
    persistence.store(AGGREGATE_ID);

  }

  private DummyDeploymentService dummyAdapter() {

    return deploymentServices
        .stream()
        .filter(java.util.Objects::nonNull)
        .flatMap(List::stream)
        .filter(java.util.Objects::nonNull)
        .filter(DummyDeploymentService.class::isInstance)
        .map(DummyDeploymentService.class::cast)
        .filter(service -> ADAPTER.equals(service.getAdapterId()))
        .findFirst()
        .orElseThrow();

  }

  /**
   * One delivery, as an adapter of a remote BPMS builds it. The delivery identity stays
   * the same while the BPMS repeats itself; the activation identity names the element
   * instance the BPMS is running.
   */
  private TaskInvocationContext delivery(
      final String deliveryId,
      final String activationId) {

    return new TaskInvocationContext() {

      @Override
      public String getAdapterId() {
        return ADAPTER;
      }

      @Override
      public String getTaskDefinition() {
        return "requestOffer";
      }

      @Override
      public String getWorkflowAggregateId() {
        return AGGREGATE_ID;
      }

      @Override
      public String getDeliveryId() {
        return deliveryId;
      }

      @Override
      public String getActivationId() {
        return activationId;
      }

    };

  }

  @Test
  @DisplayName("Multi-instance siblings of one aggregate each reach the BPMS")
  public void siblingsOfOneAggregateEachReachTheBpms() {

    final var dummyAdapter = dummyAdapter();

    // three elements of a multi-instance call activity, three deliveries of the same
    // task of the same aggregate, one correlation each
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("job-1", "element-1"));
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("job-2", "element-2"));
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("job-3", "element-3"));

    final var keys = outbox.plannedKeys();
    assertEquals(3, keys.size(), "all three siblings were planned: "
        + keys);
    assertEquals(3, keys.stream().distinct().count(), "one key per activation: "
        + keys);
    keys
        .forEach(key -> assertTrue(
            key
                .startsWith(
                    "CORRELATE_MESSAGE|%s|%s|%s|OfferRequested|%s|element-".formatted(
                        MODULE,
                        PROCESS,
                        AGGREGATE_ID,
                        ActivationWorkflowService.CORRELATION_ID)),
            key));

    // the guarantee this must not cost: the BPMS handing element 2 out again is not a
    // fourth element, so its correlation is the one already waiting
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("job-2", "element-2"));
    assertEquals(3, outbox.plannedKeys().size(), "a redelivery of one element adds nothing: "
        + outbox.plannedKeys());

  }

  @Test
  @DisplayName("A correlation outside any activation keeps the key it always had")
  public void aCorrelationOutsideAnyActivationKeepsTheKeyItAlwaysHad() throws Exception {

    // what a REST endpoint does: no activation, so no component naming one, and the
    // repetition is indistinguishable from a repeat of itself - which this story does not
    // fix and does not claim to
    correlateInItsOwnTransaction();
    correlateInItsOwnTransaction();

    final var keys = outbox.plannedKeys();
    assertEquals(1, keys.size(), "the second one lost against the first: "
        + keys);
    assertEquals(
        "CORRELATE_MESSAGE|%s|%s|%s|OfferRequested|%s".formatted(
            MODULE,
            PROCESS,
            AGGREGATE_ID,
            ActivationWorkflowService.CORRELATION_ID),
        keys.getFirst());

  }

  private void correlateInItsOwnTransaction() throws Exception {

    userTransaction.begin();
    try {
      processService
          .correlateMessage(
              persistence.get(AGGREGATE_ID),
              "OfferRequested",
              ActivationWorkflowService.CORRELATION_ID);
      userTransaction.commit();
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

  }

}
