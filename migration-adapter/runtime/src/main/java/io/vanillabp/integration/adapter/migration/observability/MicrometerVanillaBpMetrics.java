package io.vanillabp.integration.adapter.migration.observability;

import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.vanillabp.integration.adapter.migration.config.MetricsProperties;
import io.vanillabp.integration.adapter.spi.observability.CachedGaugeValue;

/**
 * Publishes what {@link VanillaBpMetrics} records as Micrometer meters. Both
 * platforms apply {@link MeterBinder} beans to their registries themselves (Spring
 * Boot through the Actuator's metrics auto-configuration, Quarkus through the
 * Micrometer extension), so this one class serves both.
 * <p>
 * Micrometer is OPTIONAL: this class is loaded only where the platform integration
 * found Micrometer, and everything the core records goes to {@link
 * VanillaBpMetrics#NONE} otherwise.
 * <p>
 * <b>Why the meters are cached.</b> A delivery must not pay for its own measurement.
 * Resolving a meter by name and tags costs a map lookup plus the tag list on every
 * call, so each meter is looked up once per tag combination and kept - and the number
 * of combinations is fixed by the deployment, see the cardinality note on
 * {@link VanillaBpMetrics}.
 * <p>
 * Before {@link #bindTo(MeterRegistry)} was called there is no registry, and every
 * record is dropped. That is the normal state during startup, where beans are built
 * before the metrics infrastructure binds them.
 * <p>
 * <b>Reading a metric costs nothing.</b> The counters and the timer are numbers this
 * class already holds, but a gauge is different: it is read on every collection, and
 * the only gauge here has to ask an outbox store how many entries wait. That query is
 * wrapped in a {@link CachedGaugeValue} before it ever becomes a gauge, so a store
 * cannot forget to do it - see {@link #registerPendingOutboxEntries(String, java.util.function.Supplier)}.
 * <p>
 * Why the caching happens here once instead of in every store is decision 18 in the repository's
 * DECISIONS.md.
 */
public class MicrometerVanillaBpMetrics implements VanillaBpMetrics, MeterBinder {

  private volatile MeterRegistry registry;

  /**
   * How long one measurement of an asking gauge is reused
   * (<code>vanillabp.metrics.gauge-cache</code>). Zero measures on every collection.
   */
  private final Duration gaugeCache;

  /**
   * Creates the meters with the default holding period - kept for tests; the platform
   * integrations always pass the configured one.
   */
  public MicrometerVanillaBpMetrics() {

    this(MetricsProperties.DEFAULT_GAUGE_CACHE);

  }

  /**
   * @param gaugeCache How long one measurement of a gauge which has to ask somebody is
   *          reused before it is taken again
   */
  public MicrometerVanillaBpMetrics(
      final Duration gaugeCache) {

    this.gaugeCache = gaugeCache;

  }

  private final Map<String, Counter> counters = new ConcurrentHashMap<>();

  private final Map<String, Timer> timers = new ConcurrentHashMap<>();

  /**
   * The pending-entry suppliers of the outbox stores, kept because a store may
   * register before the registry exists.
   */
  private final Map<String, Supplier<OptionalLong>> pendingOutboxEntries = new ConcurrentHashMap<>();

  @Override
  public void bindTo(
      final MeterRegistry meterRegistry) {

    this.registry = meterRegistry;
    // the cached meters belong to the registry they were created in
    counters.clear();
    timers.clear();
    pendingOutboxEntries.forEach((
        store,
        pending) -> registerPendingGauge(meterRegistry, store, pending));

  }

  @Override
  public void taskDelivered(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final DeliveryOutcome outcome,
      final long durationNanos) {

    final var meterRegistry = registry;
    if (meterRegistry == null) {
      return;
    }

    final var adapter = tagValue(adapterId);
    final var module = tagValue(workflowModuleId);
    final var process = tagValue(bpmnProcessId);
    final var task = tagValue(taskDefinition);

    counter(
        meterRegistry,
        TASK_DELIVERIES,
        "Task deliveries processed, by outcome",
        Tags.of(
            TAG_ADAPTER, adapter,
            TAG_WORKFLOW_MODULE, module,
            TAG_BPMN_PROCESS, process,
            TAG_TASK_DEFINITION, task,
            TAG_OUTCOME, outcome.getTagValue()))
        .increment();

    timer(
        meterRegistry,
        TASK_DELIVERY_DURATION,
        "Duration of a task delivery, including the transaction VanillaBP opens for it",
        Tags.of(
            TAG_ADAPTER, adapter,
            TAG_WORKFLOW_MODULE, module,
            TAG_BPMN_PROCESS, process,
            TAG_TASK_DEFINITION, task))
        .record(durationNanos, TimeUnit.NANOSECONDS);

  }

  @Override
  public void taskRedeliveryDeduplicated(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition) {

    final var meterRegistry = registry;
    if (meterRegistry == null) {
      return;
    }

    counter(
        meterRegistry,
        TASK_REDELIVERIES_DEDUPLICATED,
        "Repeated deliveries answered from the delivery record instead of running the handler again",
        Tags.of(
            TAG_ADAPTER, tagValue(adapterId),
            TAG_WORKFLOW_MODULE, tagValue(workflowModuleId),
            TAG_BPMN_PROCESS, tagValue(bpmnProcessId),
            TAG_TASK_DEFINITION, tagValue(taskDefinition)))
        .increment();

  }

  @Override
  public void taskRedeliveryRanConcurrently(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition) {

    final var meterRegistry = registry;
    if (meterRegistry == null) {
      return;
    }

    counter(
        meterRegistry,
        TASK_REDELIVERIES_CONCURRENT,
        "Repeated deliveries which overlapped the delivery they repeat, so the handler ran twice",
        Tags.of(
            TAG_ADAPTER, tagValue(adapterId),
            TAG_WORKFLOW_MODULE, tagValue(workflowModuleId),
            TAG_BPMN_PROCESS, tagValue(bpmnProcessId),
            TAG_TASK_DEFINITION, tagValue(taskDefinition)))
        .increment();

  }

  @Override
  public void taskElectionAnsweredFromRecord(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String operation) {

    final var meterRegistry = registry;
    if (meterRegistry == null) {
      return;
    }

    counter(
        meterRegistry,
        TASK_ELECTIONS_FROM_RECORD,
        "Elections answered by the delivery record of the task the call names, instead of by asking every BPMS",
        Tags.of(
            TAG_ADAPTER, tagValue(adapterId),
            TAG_WORKFLOW_MODULE, tagValue(workflowModuleId),
            TAG_BPMN_PROCESS, tagValue(bpmnProcessId),
            TAG_OPERATION, tagValue(operation)))
        .increment();

  }

  @Override
  public void outboxDispatchStarted(
      final String operation,
      final boolean previouslyAttempted) {

    final var meterRegistry = registry;
    if (meterRegistry == null) {
      return;
    }

    final var tags = Tags.of(TAG_OPERATION, tagValue(operation));
    counter(
        meterRegistry,
        OUTBOX_DISPATCHES,
        "Phase-two calls dispatched out of the transaction outbox",
        tags)
        .increment();
    if (previouslyAttempted) {
      counter(
          meterRegistry,
          OUTBOX_RETRIES,
          "Dispatches of an outbox entry which was attempted before",
          tags)
          .increment();
    }

  }

  @Override
  public void outboxDispatchFailed(
      final String operation,
      final boolean permanent) {

    final var meterRegistry = registry;
    if (meterRegistry == null) {
      return;
    }

    counter(
        meterRegistry,
        OUTBOX_FAILURES,
        "Outbox dispatches which ended in a failure",
        Tags.of(
            TAG_OPERATION, tagValue(operation),
            TAG_PERMANENT, Boolean.toString(permanent)))
        .increment();

  }

  @Override
  public void outboxScheduleDiscarded(
      final String operation) {

    final var meterRegistry = registry;
    if (meterRegistry == null) {
      return;
    }

    counter(
        meterRegistry,
        OUTBOX_DISCARDED,
        "Operations not planned because one of the same idempotency key was still waiting",
        Tags.of(TAG_OPERATION, tagValue(operation)))
        .increment();

  }

  @Override
  public void outboxEntryBlocked(
      final String store,
      final String operation,
      final boolean permanent) {

    final var meterRegistry = registry;
    if (meterRegistry == null) {
      return;
    }

    counter(
        meterRegistry,
        OUTBOX_BLOCKED,
        "Outbox entries a store gave up on, which stay until somebody repairs them",
        Tags.of(
            TAG_STORE, tagValue(store),
            TAG_OPERATION, tagValue(operation),
            TAG_PERMANENT, Boolean.toString(permanent)))
        .increment();

  }

  /**
   * The supplier is NOT registered as the gauge reads it. Counting the waiting entries
   * of an outbox is a query, a gauge is read on every collection, and every instance of
   * the application answers for itself - so what becomes the gauge is a
   * {@link CachedGaugeValue} around it, and the query runs at most once per
   * <code>vanillabp.metrics.gauge-cache</code> however many collectors ask.
   */
  @Override
  public void registerPendingOutboxEntries(
      final String store,
      final Supplier<OptionalLong> pending) {

    final var held = CachedGaugeValue.holding(gaugeCache, pending);
    pendingOutboxEntries.put(store, held);
    final var meterRegistry = registry;
    if (meterRegistry != null) {
      registerPendingGauge(meterRegistry, store, held);
    }

  }

  /**
   * A measurement which could not be taken is reported as NaN, which Micrometer treats
   * as "no measurement" and most backends skip - the same way the election cache's size
   * gauge answers for a cache which does not know its size. A zero would be a claim
   * nobody checked.
   */
  private static void registerPendingGauge(
      final MeterRegistry meterRegistry,
      final String store,
      final Supplier<OptionalLong> pending) {

    Gauge
        .builder(
            OUTBOX_PENDING,
            pending,
            supplier -> supplier
                .get()
                .stream()
                .mapToDouble(waiting -> waiting)
                .findFirst()
                .orElse(Double.NaN))
        .tags(Tags.of(TAG_STORE, store))
        .description("Outbox entries waiting to be dispatched")
        .register(meterRegistry);

  }

  private Counter counter(
      final MeterRegistry meterRegistry,
      final String name,
      final String description,
      final Tags tags) {

    return counters.computeIfAbsent(
        cacheKey(name, tags),
        key -> Counter
            .builder(name)
            .tags(tags)
            .description(description)
            .register(meterRegistry));

  }

  private Timer timer(
      final MeterRegistry meterRegistry,
      final String name,
      final String description,
      final Tags tags) {

    return timers.computeIfAbsent(
        cacheKey(name, tags),
        key -> Timer
            .builder(name)
            .tags(tags)
            .description(description)
            .register(meterRegistry));

  }

  private static String cacheKey(
      final String name,
      final Tags tags) {

    final var key = new StringBuilder(name);
    tags.forEach(tag -> key
        .append('|')
        .append(tag.getKey())
        .append('=')
        .append(tag.getValue()));
    return key.toString();

  }

  private static String tagValue(
      final String value) {

    return ((value == null) || value.isBlank())
        ? TAG_VALUE_UNKNOWN
        : value;

  }

}
