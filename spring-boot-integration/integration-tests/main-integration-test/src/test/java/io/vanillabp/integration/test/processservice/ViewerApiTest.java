package io.vanillabp.integration.test.processservice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;

import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.bpmsdouble.DummyViewerSource;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.TestTransactionRunnerConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.sample.Aggregate;
import io.vanillabp.integration.test.sample.SampleWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.ProcessDefinitionNotFoundException;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.process.WorkflowElementHistory;
import io.vanillabp.spi.process.WorkflowElementType;
import io.vanillabp.spi.process.WorkflowHistory;
import io.vanillabp.spi.process.WorkflowNotFoundException;

/**
 * Acceptance test of the viewer/history API on Spring Boot: the
 * application calls {@code ProcessService#getProcessDefinitions/getBpmnXml/
 * getWorkflowHistory}, the BPMS holding the workflow answers, and the process
 * definition ids handed to the application are namespaced per adapter id so
 * {@code getBpmnXml} stays resolvable in a multi-BPMS (migration) setup.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ViewerApiTest {

  private static final String BPMN_XML = "<bpmn:definitions>viewed</bpmn:definitions>";

  /**
   * The aggregate IDs of this test - the workflow of '4711' lives in 'old-bpms',
   * every other aggregate is unknown to both BPMS.
   */
  private static final Map<Object, String> AGGREGATE_IDS = Collections.synchronizedMap(new IdentityHashMap<>());

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
          return AGGREGATE_IDS.getOrDefault(aggregate, "unknown-aggregate");
        }

      };

    }

  }

  /**
   * The BPMS doubles: 'old-bpms' runs the workflow of '4711' and serves the
   * viewer data for it; 'new-bpms' knows nothing.
   */
  @Configuration
  static class ViewedWorkflowConfiguration {

    @Bean
    DummyTaskAwarenessSource viewedWorkflowAwareness() {

      return (
          adapterId,
          workflowAggregateId,
          taskId) -> "old-bpms".equals(adapterId) && "4711".equals(String.valueOf(workflowAggregateId))
              ? WorkflowAwareness.ACTIVE
              : WorkflowAwareness.UNKNOWN_TO_BPMS;

    }

    @Bean
    DummyViewerSource viewedWorkflowData() {

      return new DummyViewerSource() {

        @Override
        public List<ProcessDefinition> getProcessDefinitions(
            final String adapterId,
            final Object workflowAggregateId,
            final String historyContext) {

          if (!"old-bpms".equals(adapterId) || !"4711".equals(String.valueOf(workflowAggregateId))) {
            return List.of();
          }
          return List.of(
              new ProcessDefinition("DummyProcess:1:aaa", "DummyProcess", "1", null),
              new ProcessDefinition("SubProcess:2:bbb", "SubProcess", "2", List.of("theCallActivity")));

        }

        @Override
        public String getBpmnXml(
            final String adapterId,
            final String processDefinitionId) {

          return "old-bpms".equals(adapterId) && "DummyProcess:1:aaa".equals(processDefinitionId)
              ? BPMN_XML
              : null;

        }

        @Override
        public WorkflowHistory getWorkflowHistory(
            final String adapterId,
            final Object workflowAggregateId,
            final String historyContext) {

          if (!"old-bpms".equals(adapterId) || !"4711".equals(String.valueOf(workflowAggregateId))) {
            return null;
          }
          return new WorkflowHistory(
              "DummyProcess:1:aaa", OffsetDateTime.parse("2026-08-06T10:00:00+02:00"), null, List.of(
                  new WorkflowElementHistory(
                      OffsetDateTime.parse("2026-08-06T10:00:00+02:00"), OffsetDateTime.parse(
                          "2026-08-06T10:00:01+02:00"), "theStartEvent", WorkflowElementType.START_EVENT, null, false, null),
                  new WorkflowElementHistory(
                      OffsetDateTime.parse(
                          "2026-08-06T10:00:01+02:00"), null, "theCallActivity", WorkflowElementType.CALL_ACTIVITY, null, false, "sub-instance-1")));

        }

      };

    }

  }

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

  @Test
  public void viewerApiIsServedByTheBpmsHoldingTheWorkflow() throws IOException {

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
        ViewedWorkflowConfiguration.class)
        .run()) {

      final var processService = processServiceOf(context);

      final var viewedAggregate = new Aggregate();
      AGGREGATE_IDS.put(viewedAggregate, "4711");

      // 1. the definitions of the workflow - answered by 'old-bpms' although
      // 'new-bpms' is first priority, with ids namespaced per adapter id
      final var definitions = processService.getProcessDefinitions(viewedAggregate, null);

      Assertions.assertEquals(2, definitions.size());
      Assertions.assertEquals("old-bpms#DummyProcess:1:aaa", definitions.get(0).id());
      Assertions.assertEquals("DummyProcess", definitions.get(0).bpmnProcessId());
      Assertions.assertNull(definitions.get(0).usedByElements());
      Assertions.assertEquals("old-bpms#SubProcess:2:bbb", definitions.get(1).id());
      Assertions.assertEquals(List.of("theCallActivity"), definitions.get(1).usedByElements());

      // 2. the BPMN XML of a definition - routed to 'old-bpms' by the composite id
      try (var xml = processService.getBpmnXml(definitions.get(0).id())) {
        Assertions.assertEquals(BPMN_XML, new String(xml.readAllBytes(), StandardCharsets.UTF_8));
      }

      // 3. the workflow history - the definition id inside is namespaced, too
      final var history = processService.getWorkflowHistory(viewedAggregate, null);

      Assertions.assertEquals("old-bpms#DummyProcess:1:aaa", history.processDefinitionId());
      Assertions.assertNull(history.endTime());
      Assertions.assertEquals(2, history.elementsHistory().size());
      Assertions.assertEquals("theStartEvent", history
          .elementsHistory()
          .get(0)
          .elementId());
      Assertions.assertEquals(
          "sub-instance-1",
          history
              .elementsHistory()
              .get(1)
              .secondaryWorkflowHistoryContext());

      // 4. a workflow no BPMS knows raises the SPI's guiding exception
      final var unknownAggregate = new Aggregate();
      final var workflowNotFound = Assertions.assertThrows(
          WorkflowNotFoundException.class,
          () -> processService.getWorkflowHistory(unknownAggregate, null));
      Assertions.assertTrue(
          workflowNotFound
              .getMessage()
              .contains("unknown-aggregate"),
          () -> "expected a guiding message but got: "
              + workflowNotFound.getMessage());

      // 5. a definition id of an adapter which is not configured
      final var definitionNotFound = Assertions.assertThrows(
          ProcessDefinitionNotFoundException.class,
          () -> processService.getBpmnXml("third-bpms#DummyProcess:1:aaa"));
      Assertions.assertTrue(
          definitionNotFound
              .getMessage()
              .contains("third-bpms"),
          () -> "expected a guiding message but got: "
              + definitionNotFound.getMessage());

      // 6. a definition the addressed adapter does not know
      Assertions.assertThrows(
          ProcessDefinitionNotFoundException.class,
          () -> processService.getBpmnXml("old-bpms#Unknown:1:zzz"));

    }

  }

}
