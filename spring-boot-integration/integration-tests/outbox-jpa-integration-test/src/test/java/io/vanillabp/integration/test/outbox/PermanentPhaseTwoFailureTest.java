package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vanillabp.integration.adapter.migration.observability.MicrometerVanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * The outbox repeats a failed dispatch, which is what makes losing a concurrency conflict
 * survivable. A failure the BPMS answers the same way every time gains nothing from that,
 * so an adapter may say ({@code MigratableProcessService#isPhaseTwoFailureRepeatable})
 * that repeating cannot help, and the entry is then blocked after the first attempt.
 * <p>
 * The store here is gruelbox, which is the default of a Spring Boot application on JPA
 * and the one which used to retry such an entry fifty times. The Quarkus side has the
 * same test for the stores VanillaBP writes itself.
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class PermanentPhaseTwoFailureTest {

  /**
   * The entry of ONE aggregate. The key of a start ends in the aggregate's id (see
   * {@code PhaseOperation#START_WORKFLOW}), and a count over the whole table would
   * already be satisfied by a sibling test's entry.
   */
  private static final String BLOCKED_ENTRIES_OF_AGGREGATE = "select count(*) from TXNO_OUTBOX "
      + "where blocked = true and uniqueRequestId like '%%|%s'";

  private static final String ATTEMPTS_OF_AGGREGATE = "select max(attempts) from TXNO_OUTBOX "
      + "where uniqueRequestId like '%%|%s'";

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private RecordingPhaseTwoListener listener;

  @Autowired
  private MicrometerVanillaBpMetrics metrics;

  @BeforeEach
  public void resetListener() {

    listener.reset();

  }

  private long count(
      final String query) {

    final var count = jdbcTemplate.queryForObject(query, Long.class);
    return count == null ? 0 : count;

  }

  private Aggregate startWorkflow(
      final String content) {

    return transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent(content);
      return processService.startWorkflow(aggregate);
    });

  }

  @Test
  @DisplayName("A failure repeating cannot fix blocks the entry after the first attempt")
  public void permanentFailureBlocksTheEntryImmediately() throws Exception {

    final var registry = new SimpleMeterRegistry();
    metrics.bindTo(registry);
    listener.failNextDispatchesPermanently(1);

    final var attachedAggregate = startWorkflow("permanent-failure-test");

    listener.awaitInvocations(1, 30_000);

    final var deadline = System.currentTimeMillis() + 30_000;
    while (count(BLOCKED_ENTRIES_OF_AGGREGATE.formatted(attachedAggregate.getId())) == 0) {
      assertTrue(System.currentTimeMillis() < deadline, "the entry was not blocked");
      Thread.sleep(50);
    }

    // exactly one attempt, and nothing retries a blocked entry
    assertEquals(1, count(ATTEMPTS_OF_AGGREGATE.formatted(attachedAggregate.getId())));
    Thread.sleep(1500);
    assertEquals(1, count(ATTEMPTS_OF_AGGREGATE.formatted(attachedAggregate.getId())));
    assertEquals(
        1,
        listener
            .getInvocations()
            .stream()
            .filter(attachedAggregate.getId()::equals)
            .count(),
        "the dispatch must not be repeated");

    assertEquals(
        1.0,
        registry
            .get(VanillaBpMetrics.OUTBOX_BLOCKED)
            .tag(VanillaBpMetrics.TAG_STORE, "GruelboxPhaseTwoOutbox")
            .tag(VanillaBpMetrics.TAG_OPERATION, PhaseOperation.START_WORKFLOW.name())
            .tag(VanillaBpMetrics.TAG_PERMANENT, "true")
            .counter()
            .count(),
        "a blocked entry is counted, because the gauge of waiting entries falls at that moment");

  }

  /**
   * The counter-check: a failure the adapter reports as repeatable is still repeated, so
   * the blocking above is the adapter's answer and not a change of the store's behaviour.
   */
  @Test
  @DisplayName("A repeatable failure is still retried instead of being blocked")
  public void repeatableFailureIsRetried() throws Exception {

    listener.failNextDispatches(1);

    final var attachedAggregate = startWorkflow("repeatable-failure-test");

    final var invocations = listener.awaitInvocations(2, 30_000);
    assertEquals(attachedAggregate.getId(), invocations.get(0));
    assertEquals(attachedAggregate.getId(), invocations.get(1));
    assertEquals(0, count(BLOCKED_ENTRIES_OF_AGGREGATE.formatted(attachedAggregate.getId())));

  }

}
