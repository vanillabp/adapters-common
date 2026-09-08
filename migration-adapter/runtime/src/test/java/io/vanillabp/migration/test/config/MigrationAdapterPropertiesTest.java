package io.vanillabp.migration.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.AdapterProperties;
import io.vanillabp.integration.adapter.migration.config.ClasspathFacts;
import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.TaskAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(SuppressOutputExtension.class)
public class MigrationAdapterPropertiesTest {

  private ListAppender<ILoggingEvent> logWatcher;

  private final List<String> adaptersLoaded = List.of("adapter1", "adapter2");

  private final WorkflowModuleAdapterProperties testModule = WorkflowModuleAdapterProperties
      .builder()
      .workflowModuleId("test-module")
      .adapters(Map.of("adapter-test", AdapterProperties
          .builder()
          .resourcesLocation("classpath:test-module/processes/test")
          .build()))
      .build();

  @BeforeEach
  public void initLogWatcher() {

    logWatcher = new ListAppender<>();
    logWatcher.start();
    ((Logger) LoggerFactory.getLogger(MigrationAdapterProperties.class)).addAppender(logWatcher);

  }

  @AfterEach
  public void stopLogWatcher() {

    ((Logger) LoggerFactory.getLogger(MigrationAdapterProperties.class)).detachAndStopAllAppenders();

  }

  @Test
  public void testAdapterTypesNotInClasspath() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("adapter-unknown", new AdapterConfigProperties()));

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> properties.validateProperties(adaptersLoaded, List.of("test-module"))
    );
    assertEquals(
        """
            The following adapters were configured in properties section 'vanillabp.adapters' but there is no adapter in classpath matching the given type:
               adapter-unknown of type adapter-unknown
            Available adapter types in classpath: [adapter1, adapter2]""",
        exception.getMessage());

  }

  @Test
  public void testNoResourceLocationGivenIsDerivedByConvention() {

    // No resources-location configured is the NORMAL case - the location
    // follows the convention '<workflow module>/processes/<adapter id>'
    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("adapter-test", AdapterConfigProperties.ofType("adapter2")));
    properties.setWorkflowModules(Map.of("fake-module", WorkflowModuleAdapterProperties
        .builder()
        .workflowModuleId("fake-module")
        .build()));
    properties.setPrioritizedAdapters(List.of("adapter-test"));

    properties.validateProperties(adaptersLoaded, List.of("fake-module"));

    final var resourcesLocations = properties.getAdapterResourcesLocationsFor("fake-module", "adapter-test");
    assertEquals(1, resourcesLocations.size(), "a module of its own artifact has ONE conventional location");
    final var resourcesLocation = resourcesLocations.getFirst();
    assertEquals("classpath*:fake-module/processes/adapter-test", resourcesLocation.location());
    assertFalse(resourcesLocation.vanillaBpBpmn(), "a derived location is adapter-specific");

  }

  @Test
  public void testResourcesLocationOfTheApplicationsOwnWorkflowModule() {

    // the single workflow module declared by the application's MAIN artifact: its
    // BPMN lives below 'processes/' - no module id in the path. The module's OWN
    // location is searched first, because a workflow module tested
    // inside its own Maven module is the main artifact as well while its files sit
    // below the module id
    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("adapter-test", AdapterConfigProperties.ofType("adapter2")));
    properties.setPrioritizedAdapters(List.of("adapter-test"));

    properties.validateProperties(
        new ClasspathFacts(
            adaptersLoaded, List.of(new ClasspathFacts.WorkflowModuleInfo("the-app", true))),
        null);

    assertEquals(
        List.of("classpath*:the-app/processes/adapter-test", "classpath*:processes/adapter-test"),
        properties
            .getAdapterResourcesLocationsFor("the-app", "adapter-test")
            .stream()
            .map(MigrationAdapterProperties.ResourcesLocation::location)
            .toList());

  }

  @Test
  public void testOneLocationOnlyForAModuleWhichIsNotTheMainArtifact() {

    // a module shipped as its own artifact keeps ONE location: the root of the
    // application is another module's business
    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("adapter-test", AdapterConfigProperties.ofType("adapter2")));
    properties.setPrioritizedAdapters(List.of("adapter-test"));

    properties.validateProperties(
        new ClasspathFacts(
            adaptersLoaded, List.of(new ClasspathFacts.WorkflowModuleInfo("a-module", false))),
        null);

    assertEquals(
        List.of("classpath*:a-module/processes/adapter-test"),
        properties
            .getAdapterResourcesLocationsFor("a-module", "adapter-test")
            .stream()
            .map(MigrationAdapterProperties.ResourcesLocation::location)
            .toList());

  }

  @Test
  public void testConfiguredResourcesLocationsWinOverTheConvention() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("adapter-test", AdapterConfigProperties.ofType("adapter2")));
    properties.setPrioritizedAdapters(List.of("adapter-test"));
    properties.setResourcesLocation("classpath*:global-bpmn");
    properties.setWorkflowModules(Map.of(
        "configured-module", WorkflowModuleAdapterProperties
            .builder()
            .workflowModuleId("configured-module")
            .adapters(Map.of("adapter-test", AdapterProperties
                .builder()
                .resourcesLocation("classpath*:specific")
                .build()))
            .build()));

    properties.validateProperties(adaptersLoaded, List.of("configured-module", "global-module"));

    // the adapter-specific location wins over everything
    final var specific = properties
        .getAdapterResourcesLocationsFor("configured-module", "adapter-test")
        .getFirst();
    assertEquals("classpath*:specific", specific.location());
    assertFalse(specific.vanillaBpBpmn());
    // the global location wins over the convention (VanillaBP-neutral BPMN)
    final var global = properties
        .getAdapterResourcesLocationsFor("global-module", "adapter-test")
        .getFirst();
    assertEquals("classpath*:global-bpmn", global.location());
    assertTrue(global.vanillaBpBpmn());

  }

  @Test
  public void testWorkflowModulesConfiguredButNotInClasspath() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("adapter-test", AdapterConfigProperties.ofType("adapter2")));
    properties.setWorkflowModules(Map.of(
        "test-module", testModule,
        "fake-module", WorkflowModuleAdapterProperties
            .builder()
            .workflowModuleId("fake-module")
            .build()));
    properties.setPrioritizedAdapters(List.of("adapter-test"));
    properties.setResourcesLocation("whatever");

    properties.validateProperties(adaptersLoaded, List.of("test-module"));

    assertTrue(logWatcher.list
        .stream()
        .map(ILoggingEvent::getFormattedMessage)
        .anyMatch(msg -> msg.startsWith("""
            Found properties for workflow modules
              vanillabp.workflow-modules.fake-module
            which were not found in the class-path!""")));

  }

  @Test
  public void testPrioritizedAdapters() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of(
        "adapter-test1", AdapterConfigProperties.ofType("adapter1"),
        "adapter-test2", AdapterConfigProperties.ofType("adapter2")));
    properties.setPrioritizedAdapters(List.of("adapter-test2", "adapter-test1"));
    properties.setWorkflowModules(Map.of("test-module", WorkflowModuleAdapterProperties
        .builder()
        .workflowModuleId("test-module")
        .adapters(Map.of("adapter-test1", AdapterProperties
            .builder()
            .resourcesLocation("classpath*:processes/test1")
            .build(),
            "adapter-test2", AdapterProperties
                .builder()
                .resourcesLocation("classpath*:processes/test2")
                .build()))
        .prioritizedAdapters(List.of("adapter-test1", "adapter-test2"))
        .workflows(Map.of("testProcess", WorkflowAdapterProperties
            .builder()
            .bpmnProcessId("testProcess")
            .prioritizedAdapters(List.of("adapter-test1"))
            .build()))
        .build()));

    final var global = properties.getPrioritizedAdaptersFor(null, null);
    assertEquals(List.of("adapter-test2", "adapter-test1"), global);

    final var module = properties.getPrioritizedAdaptersFor("test-module", null);
    assertEquals(List.of("adapter-test1", "adapter-test2"), module);

    final var workflow = properties.getPrioritizedAdaptersFor("test-module", "testProcess");
    assertEquals(List.of("adapter-test1"), workflow);

  }

  @Test
  public void testOnlyOneAdapterInClasspath() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("adapter-test", AdapterConfigProperties.ofType("adapter2")));
    properties.setWorkflowModules(Map.of("test-module", testModule));
    properties.setPrioritizedAdapters(List.of("adapter-test"));

    properties.validateProperties(List.of("adapter2"), List.of("test-module"));

    assertTrue(logWatcher.list
        .stream()
        .map(ILoggingEvent::getFormattedMessage)
        .anyMatch(msg -> msg.equals(
            """
                Found only one VanillaBP adapter 'adapter-test' configured. Please ensure the properties
                  vanillabp.workflow-modules.test-module.adapters.adapter-test.resources-location
                are specific to this adapter in order to avoid future-problems once you wish to migrate to another adapter.""")));

  }

  @Test
  public void testOnlyOneAdapterInClasspathWithVanillaBpBpmnInsteadOfAdapterSpecificBpmn() {

    final var testModuleWithoutResourcesSet = WorkflowModuleAdapterProperties
        .builder()
        .workflowModuleId("test-module")
        .adapters(Map.of("adapter-test", AdapterProperties
            .builder()
            .build()))
        .build();

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("adapter-test", AdapterConfigProperties.ofType("adapter2")));
    properties.setWorkflowModules(Map.of("test-module", testModuleWithoutResourcesSet));
    properties.setPrioritizedAdapters(List.of("adapter-test"));
    properties.setResourcesLocation("classpath*:vanilla-bp-bpmn");

    properties.validateProperties(List.of("adapter2"), List.of("test-module"));

    assertTrue(logWatcher.list.isEmpty());

  }

  @Test
  public void testAdaptersReferencedButNotConfigured() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("adapter-test", AdapterConfigProperties.ofType("adapter2")));
    properties.setPrioritizedAdapters(List.of("unknown-adapter1"));
    properties.setWorkflowModules(Map.of("test-module", WorkflowModuleAdapterProperties
        .builder()
        .workflowModuleId("test-module")
        .prioritizedAdapters(List.of("unknown-adapter2"))
        .adapters(Map.of("adapter-test", AdapterProperties
            .builder()
            .resourcesLocation("whatever")
            .build()))
        .build()));

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> properties.validateProperties(List.of("adapter2"), List.of("test-module"))
    );
    assertEquals(
        """
            There are VanillaBP adapters referenced not found in any property section 'vanillabp.adapters.*':
              vanillabp.workflow-modules.test-module.prioritized-adapters => unknown-adapter2
              vanillabp.prioritized-adapters => unknown-adapter1
            """,
        exception.getMessage());
  }

  @Test
  public void testValidatePropertiesForHavingWrongPrioritizedAdapters() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("adapter-test", AdapterConfigProperties.ofType("adapter2")));
    properties.setPrioritizedAdapters(List.of("unknown-adapter1"));
    properties.setWorkflowModules(Map.of("test-module", WorkflowModuleAdapterProperties
        .builder()
        .workflowModuleId("test-module")
        .prioritizedAdapters(List.of("unknown-adapter2"))
        .workflows(Map.of("testProcess", WorkflowAdapterProperties
            .builder()
            .bpmnProcessId("testProcess")
            .prioritizedAdapters(List.of("adapter-test", "unknown-adapter3"))
            .build()))
        .build()));

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> properties.validatePropertiesFor(List.of("adapter-test"), "test-module", "testProcess"));

    assertEquals(
        """
            Property 'prioritized-adapters' of workflow-module 'test-module' and bpmn-process-id 'testProcess' contains adapters not configured in 'vanillabp.adapters.*':
              unknown-adapter3
            Available adapters are: 'adapter-test'!""",
        exception.getMessage());

  }

  @Test
  public void testValidatePropertiesForHavingWrongNoPrioritizedAdapters() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("adapter-test", AdapterConfigProperties.ofType("adapter2")));
    properties.setWorkflowModules(Map.of("test-module", WorkflowModuleAdapterProperties
        .builder()
        .workflowModuleId("test-module")
        .workflows(Map.of("testProcess", WorkflowAdapterProperties
            .builder()
            .bpmnProcessId("testProcess")
            .build()))
        .build()));

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> properties.validatePropertiesFor(List.of("adapter-test"), "test-module", "testProcess"));

    assertEquals(
        """
            No adapter is configured to be used for BPMN process 'testProcess' of workflow module 'test-module'! Define at least one of these properties:
              vanillabp.workflow-modules.test-module.workflows.testProcess.prioritized-adapters or
              vanillabp.workflow-modules.test-module.prioritized-adapters or
              vanillabp.prioritized-adapters
            Available adapters are 'adapter-test'.""",
        exception.getMessage());

  }

  @Test
  public void testDeploymentFailureDefaultsToFail() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of(
        "adapter-test", AdapterConfigProperties
            .builder()
            .type("adapter2")
            .deploymentFailure(DeploymentFailurePolicy.WARN)
            .build()));

    assertEquals(DeploymentFailurePolicy.WARN, properties.getDeploymentFailureFor("adapter-test"));
    assertEquals(DeploymentFailurePolicy.FAIL, properties.getDeploymentFailureFor("other-adapter"));

  }

  @Test
  public void testAdapterTypeDefaultsToAdapterId() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of(
        "adapter2", new AdapterConfigProperties(),
        "custom-id", AdapterConfigProperties.ofType("adapter1")));

    assertEquals(
        Map.of(
            "adapter2", "adapter2",
            "custom-id", "adapter1"),
        properties.adapterTypes());

  }

  @Test
  public void testSingleAdapterDefaultsPrioritizedAdapters() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("adapter-test", AdapterConfigProperties.ofType("adapter2")));
    properties.setWorkflowModules(Map.of("test-module", testModule));

    properties.validateProperties(List.of("adapter2"), List.of("test-module"));

    assertEquals(List.of("adapter-test"), properties.getPrioritizedAdapters());

  }

  @Test
  public void testNoAdaptersConfiguredIsRejected() {

    final var properties = new MigrationAdapterProperties();
    properties.setWorkflowModules(Map.of("test-module", testModule));

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> properties.validateProperties(List.of("adapter1", "adapter2"), List.of("test-module")));

    // SEVERAL adapter types in the classpath are never guessed - the
    // message asks for the ORDER, not for adapter sections
    assertTrue(
        exception.getMessage().contains("Several VanillaBP adapters were found in classpath"),
        exception::getMessage);
    assertTrue(exception.getMessage().contains("adapter1"), exception::getMessage);
    assertTrue(exception.getMessage().contains("adapter2"), exception::getMessage);
    assertTrue(exception.getMessage().contains("vanillabp.prioritized-adapters"), exception::getMessage);

  }

  @Test
  public void testSingleAdapterTypeInClasspathNeedsNoConfigurationAtAll() {

    // the headline: one adapter dependency, one workflow module, no property
    final var properties = new MigrationAdapterProperties();

    properties.validateProperties(List.of("only-adapter"), List.of("test-module"));

    assertEquals(Map.of("only-adapter", "only-adapter"), properties.adapterTypes());
    assertEquals(List.of("only-adapter"), properties.getPrioritizedAdapters());
    assertTrue(
        properties.getWorkflowModules().containsKey("test-module"),
        "a workflow module found in classpath needs no section any more");
    assertEquals(
        "classpath*:test-module/processes/only-adapter",
        properties
            .getAdapterResourcesLocationsFor("test-module", "only-adapter")
            .getFirst()
            .location());

  }

  @Test
  public void testAdapterSectionsAreDerivedFromPrioritizedAdapters() {

    // several BPMS: naming the ORDER is enough as long as the ids ARE adapter types
    final var properties = new MigrationAdapterProperties();
    properties.setPrioritizedAdapters(List.of("adapter2", "adapter1"));

    properties.validateProperties(List.of("adapter1", "adapter2"), List.of("test-module"));

    assertEquals(
        Map.of("adapter1", "adapter1", "adapter2", "adapter2"),
        properties.adapterTypes());
    assertEquals(List.of("adapter2", "adapter1"), properties.getPrioritizedAdapters());

  }

  @Test
  public void testCustomAdapterIdWithoutTypeIsNeverDerived() {

    // a custom id carries no information about its type - it cannot be derived
    final var properties = new MigrationAdapterProperties();
    properties.setPrioritizedAdapters(List.of("my-bpms"));

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> properties.validateProperties(List.of("adapter1"), List.of("test-module")));

    assertTrue(exception.getMessage().contains("my-bpms"), exception::getMessage);
    assertTrue(exception.getMessage().contains("vanillabp.adapters"), exception::getMessage);

  }

  @Test
  public void testWorkflowLevelConfigurationIsAccepted() {

    // regression for the former "not yet supported" rejection: a
    // workflow-level prioritized-adapters override passes the validation and is
    // resolved by getPrioritizedAdaptersFor
    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("adapter-test", AdapterConfigProperties.ofType("adapter2")));
    properties.setPrioritizedAdapters(List.of("adapter-test"));
    properties.setResourcesLocation("whatever");
    properties.setWorkflowModules(Map.of("test-module", WorkflowModuleAdapterProperties
        .builder()
        .workflowModuleId("test-module")
        .workflows(Map.of("MyProcess", WorkflowAdapterProperties
            .builder()
            .bpmnProcessId("MyProcess")
            .prioritizedAdapters(List.of("adapter-test"))
            .build()))
        .build()));

    properties.validateProperties(List.of("adapter2"), List.of("test-module"));

    assertEquals(
        List.of("adapter-test"),
        properties.getPrioritizedAdaptersFor("test-module", "MyProcess"));

  }

  @Test
  public void testWorkflowLevelPrioritizedAdapterUnknownIsRejected() {

    // a workflow-level prioritized-adapters list referencing an unknown adapter id
    // fails the boot - same behavior as the module level
    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("adapter-test", AdapterConfigProperties.ofType("adapter2")));
    properties.setPrioritizedAdapters(List.of("adapter-test"));
    properties.setResourcesLocation("whatever");
    properties.setWorkflowModules(Map.of("test-module", WorkflowModuleAdapterProperties
        .builder()
        .workflowModuleId("test-module")
        .workflows(Map.of("MyProcess", WorkflowAdapterProperties
            .builder()
            .bpmnProcessId("MyProcess")
            .prioritizedAdapters(List.of("no-such-adapter"))
            .build()))
        .build()));

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> properties.validateProperties(List.of("adapter2"), List.of("test-module")));

    assertEquals(
        """
            There are VanillaBP adapters referenced not found in any property section 'vanillabp.adapters.*':
              vanillabp.workflow-modules.test-module.workflows.MyProcess.prioritized-adapters => no-such-adapter
            """,
        exception.getMessage());

  }

  @Test
  public void testWorkflowAndTaskLevelAdapterKeysUnknownAreRejected() {

    // adapter-specific keys at the workflow and task level referencing unknown
    // adapter ids fail the boot - same behavior as the module level
    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("adapter-test", AdapterConfigProperties.ofType("adapter2")));
    properties.setPrioritizedAdapters(List.of("adapter-test"));
    properties.setResourcesLocation("whatever");
    properties.setWorkflowModules(Map.of("test-module", WorkflowModuleAdapterProperties
        .builder()
        .workflowModuleId("test-module")
        .workflows(Map.of("MyProcess", WorkflowAdapterProperties
            .builder()
            .bpmnProcessId("MyProcess")
            .adapters(Map.of("typo-adapter", AdapterProperties.builder().build()))
            .tasks(Map.of("myTask", TaskAdapterProperties
                .builder()
                .adapters(Map.of("other-typo", AdapterProperties.builder().build()))
                .build()))
            .build()))
        .build()));

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> properties.validateProperties(List.of("adapter2"), List.of("test-module")));

    assertEquals(
        """
            These properties refer to adapter ids not configured in 'vanillabp.adapters.*' - they are never used:
              vanillabp.workflow-modules.test-module.workflows.MyProcess.adapters.typo-adapter
              vanillabp.workflow-modules.test-module.workflows.MyProcess.tasks.myTask.adapters.other-typo
            Configured adapter ids are: 'adapter-test'. Fix the adapter id or add a section 'vanillabp.adapters.<id>'.""",
        exception.getMessage());

  }

  @Test
  public void testIsFirstPriorityFor() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of(
        "adapter-a", AdapterConfigProperties.ofType("adapter1"),
        "adapter-b", AdapterConfigProperties.ofType("adapter2"),
        "adapter-c", AdapterConfigProperties.ofType("adapter2")));
    properties.setPrioritizedAdapters(List.of("adapter-a", "adapter-b", "adapter-c"));
    properties.setWorkflowModules(Map.of("test-module", WorkflowModuleAdapterProperties
        .builder()
        .workflowModuleId("test-module")
        .prioritizedAdapters(List.of("adapter-b", "adapter-a"))
        .workflows(Map.of("testProcess", WorkflowAdapterProperties
            .builder()
            .bpmnProcessId("testProcess")
            .prioritizedAdapters(List.of("adapter-c"))
            .build()))
        .build()));
    properties.validateAndLink();

    assertTrue(properties.isFirstPriorityFor("test-module", "adapter-b")); // first of the module
    assertTrue(properties.isFirstPriorityFor("test-module", "adapter-c")); // first of a workflow
    assertFalse(properties.isFirstPriorityFor("test-module", "adapter-a")); // globally first, but not within this module
    assertTrue(properties.isFirstPriorityFor("other-module", "adapter-a")); // module unknown: global list applies
    assertFalse(properties.isFirstPriorityFor("other-module", "adapter-b"));

  }

  @Test
  public void testGetDeploymentAdaptersFor() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of(
        "adapter-a", AdapterConfigProperties.ofType("adapter1"),
        "adapter-b", AdapterConfigProperties.ofType("adapter2"),
        "adapter-c", AdapterConfigProperties.ofType("adapter2")));
    properties.setPrioritizedAdapters(List.of("adapter-a", "adapter-b", "adapter-c"));
    properties.setWorkflowModules(Map.of("test-module", WorkflowModuleAdapterProperties
        .builder()
        .workflowModuleId("test-module")
        .prioritizedAdapters(List.of("adapter-a"))
        .workflows(Map.of(
            "processOnB", WorkflowAdapterProperties
                .builder()
                .bpmnProcessId("processOnB")
                .prioritizedAdapters(List.of("adapter-b", "adapter-a"))
                .build(),
            "processWithoutOverride", WorkflowAdapterProperties
                .builder()
                .bpmnProcessId("processWithoutOverride")
                .build()))
        .build()));
    properties.validateAndLink();

    // union: module-level list first, workflow-level-only adapters appended
    assertEquals(
        List.of("adapter-a", "adapter-b"),
        properties.getDeploymentAdaptersFor("test-module"));
    // module without workflow-level overrides: identical to the module-level list
    assertEquals(
        List.of("adapter-a", "adapter-b", "adapter-c"),
        properties.getDeploymentAdaptersFor("other-module"));

  }

  @Test
  public void testIsFirstPriorityAnywhere() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of(
        "adapter-a", AdapterConfigProperties.ofType("adapter1"),
        "adapter-b", AdapterConfigProperties.ofType("adapter2"),
        "adapter-c", AdapterConfigProperties.ofType("adapter2")));
    properties.setPrioritizedAdapters(List.of("adapter-a", "adapter-b", "adapter-c"));
    properties.setWorkflowModules(Map.of("test-module", WorkflowModuleAdapterProperties
        .builder()
        .workflowModuleId("test-module")
        .prioritizedAdapters(List.of("adapter-b", "adapter-a"))
        .workflows(Map.of("testProcess", WorkflowAdapterProperties
            .builder()
            .bpmnProcessId("testProcess")
            .prioritizedAdapters(List.of("adapter-c"))
            .build()))
        .build()));
    properties.validateAndLink();

    assertTrue(properties.isFirstPriorityAnywhere("adapter-a")); // globally first
    assertTrue(properties.isFirstPriorityAnywhere("adapter-b")); // first of the module
    assertTrue(properties.isFirstPriorityAnywhere("adapter-c")); // first of a workflow
    assertFalse(properties.isFirstPriorityAnywhere("adapter-d")); // nowhere first

  }

  @Test
  public void testEnvironmentVariableOverrideOfConfiguredIdsIsAccepted() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("c8-cloud", AdapterConfigProperties.ofType("adapter2")));
    properties.setWorkflowModules(Map.of("test-module", testModule));

    // both the Quarkus-style (C8_CLOUD) and the Spring-uniform-style (C8CLOUD)
    // mangling of the dashed id have to be accepted, as well as static keys and
    // platform-owned sections without dynamic id segments
    properties.validateEnvironmentVariableUsage(List.of(
        "VANILLABP_ADAPTERS_C8_CLOUD_TYPE",
        "VANILLABP_ADAPTERS_C8CLOUD_TYPE",
        "VANILLABP_WORKFLOW_MODULES_TEST_MODULE_PRIORITIZED_ADAPTERS",
        "VANILLABP_PRIORITIZED_ADAPTERS",
        "VANILLABP_RESOURCES_LOCATION",
        "VANILLABP_OUTBOX_POLL_INTERVAL",
        "OTHER_ENV_VAR"));

  }

  @Test
  public void testEnvironmentVariableIntroducingUnknownIdIsRejected() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("c8-cloud", AdapterConfigProperties.ofType("adapter2")));
    properties.setWorkflowModules(Map.of("test-module", testModule));

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> properties.validateEnvironmentVariableUsage(List.of(
            "VANILLABP_ADAPTERS_C8_ONPREM_TYPE")));

    assertTrue(exception.getMessage().contains("VANILLABP_ADAPTERS_C8_ONPREM_TYPE"));
    assertTrue(exception.getMessage().contains("'c8-cloud'"));
    assertTrue(exception.getMessage().contains("cannot introduce a new adapter or workflow module"));
    assertTrue(exception.getMessage().contains("Declare the ID in a configuration file"));

  }

  @Test
  public void testEnvironmentVariableIntroducingUnknownModuleIdIsRejected() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("c8-cloud", AdapterConfigProperties.ofType("adapter2")));
    properties.setWorkflowModules(Map.of("test-module", testModule));

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> properties.validateEnvironmentVariableUsage(List.of(
            "VANILLABP_WORKFLOW_MODULES_OTHER_MODULE_PRIORITIZED_ADAPTERS")));

    assertTrue(exception.getMessage().contains("VANILLABP_WORKFLOW_MODULES_OTHER_MODULE_PRIORITIZED_ADAPTERS"));
    assertTrue(exception.getMessage().contains("'test-module'"));

  }


  @Test
  @org.junit.jupiter.api.DisplayName("A workflow module id containing the prefix separator fails - but only when prefixing is used")
  public void moduleIdsAreValidatedAgainstThePrefixSeparator() {

    final var withPrefixing = MigrationAdapterProperties
        .builder()
        .adapters(java.util.Map.of("c8", adapterUsingPrefixes()))
        .prioritizedAdapters(java.util.List.of("c8"))
        .workflowModules(java.util.Map.of(
            "loan__approval", new WorkflowModuleAdapterProperties()))
        .build();
    final var exception = org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class,
        withPrefixing::validateWorkflowModuleIdsAgainstPrefixing);
    org.junit.jupiter.api.Assertions.assertTrue(
        exception.getMessage().contains("'loan__approval'") && exception.getMessage().contains("'__'"),
        exception::getMessage);

    // the very same module id is fine as long as nothing uses prefixes
    MigrationAdapterProperties
        .builder()
        .adapters(java.util.Map.of("c8", AdapterConfigProperties.ofType("camunda8")))
        .prioritizedAdapters(java.util.List.of("c8"))
        .workflowModules(java.util.Map.of(
            "loan__approval", new WorkflowModuleAdapterProperties()))
        .build()
        .validateWorkflowModuleIdsAgainstPrefixing();

  }

  /**
   * Which adapter ids one adapter TYPE serves - the question a platform answers to
   * register one set of beans per configured engine, and an extension bridging to a
   * BPMS to register one bridge per engine.
   * <p>
   * The cases are the ones {@code AdapterBeanRegistrarSupportTest} pinned on Spring
   * Boot, asked of the core method both platforms now delegate to. What they are about
   * is the two halves of the convention: a section without a {@code type} takes its id
   * as the type, and an id named in {@code prioritized-adapters} which IS an adapter
   * type needs no section at all - the migration setup, where dropping the second half
   * left the old adapter without beans.
   */
  @org.junit.jupiter.api.Nested
  @org.junit.jupiter.api.DisplayName("The adapter ids of one type")
  class AdapterIdsOfType {

    private MigrationAdapterProperties configured(
        final List<String> prioritizedAdapters,
        final Map<String, AdapterConfigProperties> adapters) {

      final var properties = new MigrationAdapterProperties();
      properties.setPrioritizedAdapters(prioritizedAdapters);
      properties.setAdapters(adapters);
      return properties;

    }

    @Test
    @org.junit.jupiter.api.DisplayName("A section carrying nothing but the adapter's own keys takes its id as the type")
    public void aSectionWithoutATypeIsTheType() {

      // the section bound nothing the core model knows, so there is no entry for it -
      // which must not decide whether the id exists
      assertEquals(
          List.of("camunda7"),
          configured(List.of("camunda7"), Map.of()).adapterIdsOfType("camunda7"));

    }

    @Test
    @org.junit.jupiter.api.DisplayName("An id named in prioritized-adapters needs no section, even next to another adapter")
    public void aPrioritizedIdNeedsNoSection() {

      final var properties = configured(
          List.of("camunda8", "camunda7"),
          Map.of("camunda8", AdapterConfigProperties.ofType("camunda8")));

      assertEquals(List.of("camunda7"), properties.adapterIdsOfType("camunda7"));
      assertEquals(List.of("camunda8"), properties.adapterIdsOfType("camunda8"));

    }

    @Test
    @org.junit.jupiter.api.DisplayName("An id named like this type but declaring another one belongs to that other adapter")
    public void anIdNamedLikeTheTypeMayDeclareAnother() {

      final var properties = configured(
          List.of("camunda7"),
          Map.of("camunda7", AdapterConfigProperties.ofType("camunda8")));

      assertEquals(List.of(), properties.adapterIdsOfType("camunda7"));
      assertEquals(List.of("camunda7"), properties.adapterIdsOfType("camunda8"));

    }

    @Test
    @org.junit.jupiter.api.DisplayName("A custom id naming this type is served, and the type's own name next to it")
    public void aCustomIdNamingTheTypeIsServed() {

      final var properties = configured(
          List.of("camunda7", "old-engine"),
          Map.of("old-engine", AdapterConfigProperties.ofType("camunda7")));

      assertEquals(List.of("camunda7", "old-engine"), properties.adapterIdsOfType("camunda7"));

    }

    @Test
    @org.junit.jupiter.api.DisplayName("Nothing configured at all: the single adapter dependency IS the configuration")
    public void withoutAnyConfigurationTheTypeIsTheId() {

      assertEquals(List.of("camunda7"), configured(List.of(), Map.of()).adapterIdsOfType("camunda7"));

    }

    @Test
    @org.junit.jupiter.api.DisplayName("With another adapter configured, an unnamed type gets nothing")
    public void aTypeNobodyNamedGetsNothing() {

      final var properties = configured(
          List.of("camunda8"),
          Map.of("camunda8", AdapterConfigProperties.ofType("camunda8")));

      assertEquals(List.of(), properties.adapterIdsOfType("camunda7"));

    }

  }

  private static AdapterConfigProperties adapterUsingPrefixes() {

    final var adapter = AdapterConfigProperties.ofType("camunda8");
    adapter.setNameClashAvoidance(io.vanillabp.integration.adapter.spi.NameClashAvoidance.USE_PREFIX);
    return adapter;

  }

}