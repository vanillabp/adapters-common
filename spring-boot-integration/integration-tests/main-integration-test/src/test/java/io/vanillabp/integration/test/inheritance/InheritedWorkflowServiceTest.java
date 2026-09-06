package io.vanillabp.integration.test.inheritance;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;

import io.vanillabp.adapter.dummy.springboot.DummyAdapterConfiguration;
import io.vanillabp.adapter.dummy.springboot.deployment.DeploymentService;
import io.vanillabp.adapter.dummy.springboot.deployment.DummyTaskWiringSource;
import io.vanillabp.adapter.dummy.springboot.processservice.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.TestTransactionRunnerConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.deployment.DeploymentTest;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.spi.process.ProcessService;

/**
 * What <code>&#64;Inherited</code> promises on Spring Boot, and the regression which proves
 * that this story moved Quarkus rather than Spring Boot: the class of the BEAN is the
 * workflow service, so the BPMN process is named after the SUBCLASS, the subclass' handler
 * and the handler it inherited both serve that process, and the workflow module is the one of
 * the subclass.
 * <p>
 * Next to it, the two ways of writing a handler method which nobody sees. Both used to end in
 * the wiring validation asking for a method the developer can point at in their own source,
 * so the boot names them now - the method, the class it is declared in and the way out.
 */
@ExtendWith(SuppressOutputExtension.class)
public class InheritedWorkflowServiceTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "InheritingWorkflowService";

  private static final String SHARED_PROCESS = "SharedProcess";

  /**
   * The persistence of the two aggregates plus the transaction infrastructure a handler
   * invocation needs.
   */
  @Configuration
  static class InheritanceConfiguration {

    static final Map<String, InheritedAggregate> AGGREGATES = new ConcurrentHashMap<>();

    @Bean
    AggregatePersistenceAware<InheritedAggregate> inheritedPersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<InheritedAggregate> getAggregateClass() {
          return InheritedAggregate.class;
        }

        @Override
        public InheritedAggregate save(
            final InheritedAggregate aggregate) {
          AGGREGATES.put(aggregate.getId(), copyOf(aggregate));
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final InheritedAggregate aggregate) {
          return aggregate.getId();
        }

        @Override
        public Class<?> getAggregateIdType() {
          return String.class;
        }

        @Override
        public InheritedAggregate loadById(
            final Object aggregateId) {
          final var stored = AGGREGATES.get(aggregateId);
          return stored != null
              ? copyOf(stored)
              : null;
        }

      };

    }

    private static InheritedAggregate copyOf(
        final InheritedAggregate aggregate) {

      final var copy = new InheritedAggregate();
      copy.setId(aggregate.getId());
      copy.setServedBy(aggregate.getServedBy());
      return copy;

    }

    @Bean
    DataSource inheritanceDataSource() {

      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();

    }

    @Bean
    PlatformTransactionManager transactionManager(
        final DataSource inheritanceDataSource) {

      return new DataSourceTransactionManager(inheritanceDataSource);

    }

    /**
     * Stands in for the BPMN model: both tasks of the process the subclass serves, the one
     * whose handler the subclass writes itself and the one whose handler it inherited.
     */
    @Bean
    DummyTaskWiringSource inheritanceWiringSource() {

      return (
          adapterId,
          workflowModuleId,
          bpmnProcessId) -> PROCESS.equals(bpmnProcessId)
              ? List.of(
                  new BpmnTaskSpec("Activity_Own", "ownTask"),
                  new BpmnTaskSpec("Activity_Inherited", "inheritedTask"))
              : List.of();

    }

    /**
     * The subclass is a bean, the class carrying the declaration is not - which is what makes
     * the subclass the workflow service.
     */
    @Bean
    InheritingWorkflowService inheritingWorkflowService() {

      return new InheritingWorkflowService();

    }

  }

  /**
   * The application of the second test: nothing here ever reaches an aggregate, the boot's
   * report about the two handler methods is what is asserted.
   */
  @Configuration
  static class InvisibleHandlersConfiguration {

    @Bean
    AggregatePersistenceAware<InvisibleHandlersAggregate> invisibleHandlersPersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<InvisibleHandlersAggregate> getAggregateClass() {
          return InvisibleHandlersAggregate.class;
        }

        @Override
        public InvisibleHandlersAggregate save(
            final InvisibleHandlersAggregate aggregate) {
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final InvisibleHandlersAggregate aggregate) {
          return aggregate.getId();
        }

      };

    }

    @Bean
    InvisibleHandlersWorkflowService invisibleHandlersWorkflowService() {

      return new InvisibleHandlersWorkflowService();

    }

  }

  /**
   * Two classes splitting the handlers of the process their common base names. This is the
   * shape the refusal of an annotated interface points a developer at, so it has to work.
   */
  @Configuration
  static class SharedProcessConfiguration {

    static final Map<String, SharedProcessAggregate> AGGREGATES = new ConcurrentHashMap<>();

    @Bean
    AggregatePersistenceAware<SharedProcessAggregate> sharedProcessPersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<SharedProcessAggregate> getAggregateClass() {
          return SharedProcessAggregate.class;
        }

        @Override
        public SharedProcessAggregate save(
            final SharedProcessAggregate aggregate) {
          AGGREGATES.put(aggregate.getId(), copyOf(aggregate));
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final SharedProcessAggregate aggregate) {
          return aggregate.getId();
        }

        @Override
        public Class<?> getAggregateIdType() {
          return String.class;
        }

        @Override
        public SharedProcessAggregate loadById(
            final Object aggregateId) {
          final var stored = AGGREGATES.get(aggregateId);
          return stored != null
              ? copyOf(stored)
              : null;
        }

      };

    }

    private static SharedProcessAggregate copyOf(
        final SharedProcessAggregate aggregate) {

      final var copy = new SharedProcessAggregate();
      copy.setId(aggregate.getId());
      copy.setServedBy(aggregate.getServedBy());
      return copy;

    }

    @Bean
    DataSource sharedProcessDataSource() {

      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();

    }

    @Bean
    PlatformTransactionManager transactionManager(
        final DataSource sharedProcessDataSource) {

      return new DataSourceTransactionManager(sharedProcessDataSource);

    }

    @Bean
    DummyTaskWiringSource sharedProcessWiringSource() {

      return (
          adapterId,
          workflowModuleId,
          bpmnProcessId) -> SHARED_PROCESS.equals(bpmnProcessId)
              ? List.of(
                  new BpmnTaskSpec("Activity_First", "firstHalf"),
                  new BpmnTaskSpec("Activity_Second", "secondHalf"))
              : List.of();

    }

    @Bean
    HandlersOfTheFirstHalf handlersOfTheFirstHalf() {

      return new HandlersOfTheFirstHalf();

    }

    @Bean
    HandlersOfTheSecondHalf handlersOfTheSecondHalf() {

      return new HandlersOfTheSecondHalf();

    }

  }

  /**
   * Each scenario deploys BPMN files of its own, so each brings its own resources location:
   * a process no workflow service of the running application claims would otherwise be
   * reported for every application here.
   */
  private static String applicationYaml(
      final String resourcesLocation) {

    return """
        vanillabp:
          adapters:
            test:
              type: dummy
              test: 1
          workflow-modules:
            test-module:
              adapters:
                test:
                  resources-location: classpath*:test-module/processes/%s
        """
        .formatted(resourcesLocation);

  }

  private ConfigurableApplicationContext runTestApplication(
      final SpringBootTestApplication testApp,
      final Class<?>... additionalClasses) {

    final var classes = new java.util.LinkedList<Class<?>>(List.of(
        DummyAdapterConfiguration.class,
        DummyAdapterProcessServiceConfiguration.class,
        WorkflowModuleAutoConfiguration.class,
        SpringBootMigrationAdapterAutoConfiguration.class,
        TestPersistenceConfiguration.class,
        TestPhaseTwoOutboxConfiguration.class,
        WorkflowModuleConfiguration.class,
        DeploymentTest.TestConfig.class));
    classes.addAll(List.of(additionalClasses));
    return testApp.applicationBuilder(classes.toArray(Class[]::new)).run();

  }

  private SpringBootTestApplication buildTestApp(
      final String resourcesLocation) throws IOException {

    return SpringBootTestApplication.builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", applicationYaml(resourcesLocation))
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build();

  }

  private static TaskInvocationContext context(
      final String taskDefinition,
      final String aggregateId) {

    return new TaskInvocationContext() {

      @Override
      public String getTaskDefinition() {
        return taskDefinition;
      }

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

    };

  }

  @Test
  @DisplayName("The subclass serves the process named after it, with its own and its inherited handler")
  public void theSubclassIsTheWorkflowService() throws IOException {

    InheritanceConfiguration.AGGREGATES.clear();

    try (var testApp = buildTestApp("inheritance"); var context = runTestApplication(testApp,
        InheritanceConfiguration.class)) {

      final var processService = (ProcessService<?>) context
          .getBeanProvider(org.springframework.core.ResolvableType
              .forClassWithGenerics(ProcessService.class, InheritedAggregate.class))
          .getObject();
      Assertions.assertEquals(MODULE, processService.getWorkflowModuleId());

      final var seeded = new InheritedAggregate();
      seeded.setId("4711");
      seeded.setServedBy("nobody");
      InheritanceConfiguration.AGGREGATES.put("4711", seeded);

      final var dummyAdapter = context.getBean("DummyAdapter_DeploymentService_test", DeploymentService.class);
      Assertions.assertEquals(
          WorkflowTaskOutcome.Kind.COMPLETED,
          dummyAdapter.invokeTask(MODULE, PROCESS, context("ownTask", "4711")).kind());
      Assertions.assertEquals(
          WorkflowTaskOutcome.Kind.COMPLETED,
          dummyAdapter.invokeTask(MODULE, PROCESS, context("inheritedTask", "4711")).kind());
      Assertions.assertEquals(
          "nobody+ownTask+inheritedTask",
          InheritanceConfiguration.AGGREGATES.get("4711").getServedBy());

    }

  }

  @Test
  @DisplayName("A handler which is not public and one whose override dropped the annotation are named")
  public void bothInvisibleHandlersAreReported(
      final CapturedOutput output) throws IOException {

    try (var testApp = buildTestApp("inheritance"); var context = runTestApplication(testApp,
        InvisibleHandlersConfiguration.class,
        TestTransactionRunnerConfiguration.class)) {

      final var reported = output.getAll();
      Assertions.assertTrue(
          reported.contains("which VanillaBP does not see"),
          "no report about the handler methods nobody sees: "
              + reported);
      Assertions.assertTrue(reported.contains("tooWellHidden"), reported);
      Assertions.assertTrue(reported.contains("is protected"), reported);
      Assertions.assertTrue(reported.contains("Make the method public"), reported);
      Assertions.assertTrue(reported.contains("overriddenTask"), reported);
      Assertions.assertTrue(reported.contains(OverriddenHandlerBase.class.getName()), reported);
      Assertions.assertTrue(reported.contains("carries no annotation of its own"), reported);
      Assertions.assertTrue(reported.contains("Repeat the annotation on the override"), reported);

    }

  }


  @Test
  @DisplayName("Two subclasses of one base split the handlers of the process the base names")
  public void twoSubclassesShareTheProcessOfTheirBase() throws IOException {

    SharedProcessConfiguration.AGGREGATES.clear();

    try (var testApp = buildTestApp("inheritance-shared"); var context = runTestApplication(testApp,
        SharedProcessConfiguration.class)) {

      final var seeded = new SharedProcessAggregate();
      seeded.setId("4712");
      seeded.setServedBy("nobody");
      SharedProcessConfiguration.AGGREGATES.put("4712", seeded);

      final var dummyAdapter = context.getBean("DummyAdapter_DeploymentService_test", DeploymentService.class);
      Assertions.assertEquals(
          WorkflowTaskOutcome.Kind.COMPLETED,
          dummyAdapter.invokeTask(MODULE, SHARED_PROCESS, context("firstHalf", "4712")).kind());
      Assertions.assertEquals(
          WorkflowTaskOutcome.Kind.COMPLETED,
          dummyAdapter.invokeTask(MODULE, SHARED_PROCESS, context("secondHalf", "4712")).kind());
      Assertions.assertEquals(
          "nobody+firstHalf+secondHalf",
          SharedProcessConfiguration.AGGREGATES.get("4712").getServedBy());

    }

  }

}
