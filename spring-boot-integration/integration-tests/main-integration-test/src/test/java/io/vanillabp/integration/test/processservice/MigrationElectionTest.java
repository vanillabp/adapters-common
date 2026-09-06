package io.vanillabp.integration.test.processservice;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.TestTransactionRunnerConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.sample.Aggregate;
import io.vanillabp.integration.test.sample.SampleWorkflowService;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.spi.process.ProcessService;

/**
 * THE migration scenario: instances were started while adapter
 * 'old-bpms' was first priority; the configuration was then flipped ('new-bpms'
 * promoted, 'old-bpms' demoted). Operations on OLD instances still route to
 * 'old-bpms' (the probing election finds the workflow there), NEW workflows start
 * in 'new-bpms' - and the second operation on the same workflow skips the walk via
 * the election cache. Also proves the cache-override SPI: an application-provided
 * {@link WorkflowAdapterCache} bean replaces the in-memory default.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MigrationElectionTest {

  // test-specific task IDs: the captured output may contain lines of sibling
  // tests emitted by background threads - counting must not cross tests
  private static final String OLD_INSTANCE_TASK_PHASE_ONE = "Dummy-Adapter[old-bpms]: Completing task 'task-mig' (phase one)";

  private static final String NEW_START_PHASE_ONE = "Dummy-Adapter[new-bpms]: Starting workflow (phase one)";

  private static final String OLD_START_PHASE_ONE = "Dummy-Adapter[old-bpms]: Starting workflow (phase one)";

  /**
   * Persistence double for the sample aggregate.
   */
  @Configuration
  static class AggregatePersistenceConfiguration {

    @Bean
    AggregatePersistenceAware<Aggregate> testAggregatePersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<Aggregate> getAggregateClass() {
          return Aggregate.class;
        }

        @Override
        public Aggregate save(
            final Aggregate aggregate) {
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final Aggregate aggregate) {
          return "4711";
        }

      };

    }

  }

  /**
   * The BPMS double's awareness: the workflow of aggregate '4711' lives in
   * 'old-bpms' (started before the priority flip); 'new-bpms' does not know it.
   */
  @Configuration
  static class OldInstanceAwarenessConfiguration {

    @Bean
    DummyTaskAwarenessSource oldInstanceAwareness() {

      return (
          adapterId,
          workflowAggregateId,
          taskId) -> "old-bpms".equals(adapterId) && "4711".equals(String.valueOf(workflowAggregateId))
              ? WorkflowAwareness.ACTIVE
              : WorkflowAwareness.UNKNOWN_TO_BPMS;

    }

  }

  /**
   * An application-provided election cache (the cluster-shared-cache SPI): records
   * every access so the test can assert it replaced the in-memory default.
   */
  static class RecordingWorkflowAdapterCache implements WorkflowAdapterCache {

    final Map<String, String> entries = new ConcurrentHashMap<>();

    final List<String> puts = new CopyOnWriteArrayList<>();

    final List<String> gets = new CopyOnWriteArrayList<>();

    private String key(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId) {
      return "%s|%s|%s".formatted(workflowModuleId, bpmnProcessId, workflowAggregateId);
    }

    @Override
    public Optional<String> get(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId) {
      final var key = key(workflowModuleId, bpmnProcessId, workflowAggregateId);
      gets.add(key);
      return Optional.ofNullable(entries.get(key));
    }

    @Override
    public void put(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final String adapterId) {
      final var key = key(workflowModuleId, bpmnProcessId, workflowAggregateId);
      puts.add(key
          + "->"
          + adapterId);
      entries.put(key, adapterId);
    }

    @Override
    public void invalidate(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId) {
      entries.remove(key(workflowModuleId, bpmnProcessId, workflowAggregateId));
    }

  }

  @Configuration
  static class ApplicationProvidedCacheConfiguration {

    @Bean
    RecordingWorkflowAdapterCache applicationWorkflowAdapterCache() {

      return new RecordingWorkflowAdapterCache();

    }

  }

  /**
   * The flipped configuration: 'new-bpms' is first priority NOW; instances of
   * '4711' were started while 'old-bpms' was first.
   */
  private static final String APPLICATION_YAML = """
      vanillabp:
        prioritized-adapters:
          - new-bpms
          - old-bpms
        adapters:
          new-bpms:
            type: dummy
          old-bpms:
            type: dummy
        workflow-modules:
          test-module:
            adapters:
              new-bpms:
                resources-location: classpath*:test-module/processes/dummy
              old-bpms:
                resources-location: classpath*:test-module/processes/dummy
      test-module:
        nothing: there
      """;

  private SpringBootTestApplication testApplication() throws IOException {

    return SpringBootTestApplication.builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", APPLICATION_YAML)
        .addResource("test-module/processes/dummy/DummyProcess.bpmn")
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build();

  }

  @SuppressWarnings("unchecked")
  private static ProcessService<Aggregate> processServiceOf(
      final org.springframework.context.ApplicationContext context) {

    return (ProcessService<Aggregate>) context
        .getBeanProvider(ResolvableType
            .forClassWithGenerics(ProcessService.class, Aggregate.class))
        .getObject();

  }

  private static void inFakeTransaction(
      final Runnable operation) {

    // the dummy adapter completes tasks entirely in phase one (no two-phase
    // commit) - the process-service bean only requires an ACTIVE transaction
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      operation.run();
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

  }

  @Test
  public void oldInstancesRouteToOldBpmsNewStartsToNewBpms(
      final CapturedOutput output) throws IOException {

    try (var testApp = testApplication(); var context = testApp.applicationBuilder(
        DummyAdapterConfiguration.class,
        DummyAdapterProcessServiceConfiguration.class,
        WorkflowModuleAutoConfiguration.class,
        SpringBootMigrationAdapterAutoConfiguration.class,
        TestPersistenceConfiguration.class, TestPhaseTwoOutboxConfiguration.class,
        TestTransactionRunnerConfiguration.class,
        SampleWorkflowService.class,
        WorkflowModuleConfiguration.class,
        AggregatePersistenceConfiguration.class,
        OldInstanceAwarenessConfiguration.class)
        .run()) {

      final var processService = processServiceOf(context);

      // operation on the OLD instance: the probing election routes it to
      // 'old-bpms' although 'new-bpms' is first priority now
      final var oldAggregate = new Aggregate();
      inFakeTransaction(() -> processService.completeTask(oldAggregate, "task-mig"));

      final var afterOldOperation = output.getAll();
      Assertions.assertTrue(
          afterOldOperation.contains(OLD_INSTANCE_TASK_PHASE_ONE),
          "expected the task of the old instance to complete on 'old-bpms' but got: "
              + afterOldOperation);
      Assertions.assertFalse(
          afterOldOperation.contains("Dummy-Adapter[new-bpms]: Completing task"),
          "the task must never be completed on 'new-bpms' but got: "
              + afterOldOperation);

      // the election probed 'new-bpms' first (it is first priority) - count the
      // probes to prove the SECOND operation skips the walk via the cache
      final var newBpmsProbes = countOccurrences(
          output.getAll(), "Dummy-Adapter[new-bpms]: Checking awareness of task 'task-mig'");

      inFakeTransaction(() -> processService.completeTask(oldAggregate, "task-mig"));

      Assertions.assertEquals(
          newBpmsProbes,
          countOccurrences(output.getAll(), "Dummy-Adapter[new-bpms]: Checking awareness of task 'task-mig'"),
          "the second operation must skip the walk (cache hit) - 'new-bpms' probed again");
      Assertions.assertEquals(
          2,
          countOccurrences(output.getAll(), OLD_INSTANCE_TASK_PHASE_ONE),
          () -> "the second operation must still execute on 'old-bpms' but got: "
              + output
                  .getAll()
                  .lines()
                  .filter(line -> line.contains("Dummy-Adapter"))
                  .collect(java.util.stream.Collectors.joining("\n")));

      // NEW workflows start in the CURRENT first-priority adapter
      processService.startWorkflow(new Aggregate());

      final var afterStart = output.getAll();
      Assertions.assertTrue(
          afterStart.contains(NEW_START_PHASE_ONE),
          "expected new workflows to start in 'new-bpms' but got: "
              + afterStart);
      Assertions.assertFalse(
          afterStart.contains(OLD_START_PHASE_ONE),
          "new workflows must never start in the demoted 'old-bpms' but got: "
              + afterStart);

    }

  }

  @Test
  public void applicationProvidedCacheReplacesTheDefault(
      final CapturedOutput output) throws IOException {

    try (var testApp = testApplication(); var context = testApp.applicationBuilder(
        DummyAdapterConfiguration.class,
        DummyAdapterProcessServiceConfiguration.class,
        WorkflowModuleAutoConfiguration.class,
        SpringBootMigrationAdapterAutoConfiguration.class,
        TestPersistenceConfiguration.class, TestPhaseTwoOutboxConfiguration.class,
        TestTransactionRunnerConfiguration.class,
        SampleWorkflowService.class,
        WorkflowModuleConfiguration.class,
        AggregatePersistenceConfiguration.class,
        OldInstanceAwarenessConfiguration.class,
        ApplicationProvidedCacheConfiguration.class)
        .run()) {

      final var processService = processServiceOf(context);
      final var cache = context.getBean(RecordingWorkflowAdapterCache.class);

      inFakeTransaction(() -> processService.completeTask(new Aggregate(), "task-cache"));

      // the election consulted AND populated the application's cache
      Assertions.assertFalse(cache.gets.isEmpty(), "the election must consult the application's cache");
      Assertions.assertEquals(1, cache.puts.size(), "one successful election, one put");
      Assertions.assertTrue(
          cache.puts.getFirst().endsWith("|4711->old-bpms"),
          "the successful election must be stored in the application's cache but got: "
              + cache.puts);

    }

  }

  private static int countOccurrences(
      final String text,
      final String needle) {

    var count = 0;
    var index = text.indexOf(needle);
    while (index >= 0) {
      ++count;
      index = text.indexOf(needle, index + needle.length());
    }
    return count;

  }

}
