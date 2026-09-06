package io.vanillabp.migration.test.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.AdapterProperties;
import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.adapter.migration.deployment.DeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

@ExtendWith(MockitoExtension.class)
@ExtendWith(SuppressOutputExtension.class)
public class DeploymentServiceTest {

  private ListAppender<ILoggingEvent> logWatcher;

  @Mock
  private AdapterDeploymentService<Integer, Integer> adapter1DeploymentService;

  @Mock
  private AdapterDeploymentService<Long, Long> adapter2DeploymentService;

  @Mock
  private ExtensionWiringService<Integer, Integer> adapter1WiringService;

  @Mock
  private ExtensionWiringService<Long, Long> adapter2WiringService;

  @Mock
  private ExtensionWiringService<Integer, Integer> extension1WiringService;

  @Mock
  private ExtensionWiringService<Comparable<Integer>, Comparable<Integer>> interfaceTypeWiringService;

  @BeforeEach
  public void initializeTests() {

    // Initialize log watcher to capture log output
    logWatcher = new ListAppender<>();
    logWatcher.start();
    ((Logger) LoggerFactory.getLogger(DeploymentService.class)).addAppender(logWatcher);

    // extension matching uses the adapters' DECLARED types - stub them for all
    // tests (real adapters always provide them)
    org.mockito.Mockito.lenient().when(adapter1DeploymentService.getModelType()).thenReturn(Integer.class);
    org.mockito.Mockito.lenient().when(adapter1DeploymentService.getProcessContextType()).thenReturn(Integer.class);
    org.mockito.Mockito.lenient().when(adapter2DeploymentService.getModelType()).thenReturn(Long.class);
    org.mockito.Mockito.lenient().when(adapter2DeploymentService.getProcessContextType()).thenReturn(Long.class);

  }

  @AfterEach
  public void stopLogWatcher() {

    ((Logger) LoggerFactory.getLogger(DeploymentService.class)).detachAndStopAllAppenders();

  }

  @Nested
  @DisplayName("Constructor Tests")
  class ConstructorTests {

    @Test
    @DisplayName("Wiring services are sorted by order")
    public void wiringServicesAreSortedByOrder() {

      // Configure mocks for order sorting
      when(adapter1WiringService.getOrder()).thenReturn(3);
      when(adapter2WiringService.getOrder()).thenReturn(1);
      when(extension1WiringService.getOrder()).thenReturn(2);

      // Create DeploymentService with unsorted list
      final var properties = createPropertiesWithAdapter("adapter-test1");
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of(adapter1WiringService, extension1WiringService,
              adapter2WiringService));

      // Verify: object was created (sorting is internal and can only be tested indirectly)
      assertNotNull(testee);

    }

  }

  @Nested
  @DisplayName("deployResources Tests")
  class DeployResourcesTests {

    @Test
    @DisplayName("Correct deployment service is found for configured adapter")
    public void findsCorrectDeploymentServiceForAdapter() {

      // Configure properties and mocks
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Configure adapter1DeploymentService to return the correct adapter ID
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      // Create resources loader that returns a test BPMN file
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("test-process.bpmn",
              createDummyBpmnInputStream()));

      // readBpmn should return an executable process
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));

      // prepareBpmn should return a context
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      // Create DeploymentService and call deployResources
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      testee.deployResources(List.of("test-module"), resourcesLoader);

      // Verify that the correct DeploymentService was used
      verify(adapter1DeploymentService).readBpmn(
          eq("test-module"),
          eq("test-process.bpmn"),
          any(InputStream.class),
          eq(false));

    }

    @Test
    @DisplayName("IllegalStateException is thrown when no deployment service exists for adapter")
    public void throwsExceptionWhenNoDeploymentServiceFound() {

      // Configure properties with an adapter for which no DeploymentService exists
      final var properties = createPropertiesWithAdapter("non-existent-adapter");

      // Create DeploymentService without matching service
      final var testee = new DeploymentService(
          properties, List.of(), List.of());

      // Dummy loader that should not be called
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(location -> Map.of());

      // IllegalStateException is expected
      final var exception = assertThrows(
          IllegalStateException.class,
          () -> testee.deployResources(List.of("test-module"), resourcesLoader));
      assertTrue(exception.getMessage().contains("No deployment service found for adapter"));

    }

    @Test
    @DisplayName("Warning is logged when BPMN contains no executable processes")
    public void logsWarningWhenBpmnHasNoExecutableProcesses() {

      // Configure properties and mocks
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Configure adapter1DeploymentService
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      // Create resources loader with a BPMN file
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("empty-process.bpmn",
              createDummyBpmnInputStream()));

      // readBpmn should return an empty list (no executable processes)
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of());

      // Create DeploymentService and call deployResources
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      testee.deployResources(List.of("test-module"), resourcesLoader);

      // Verify that a warning was logged
      final var warningLogs = logWatcher.list
          .stream()
          .filter(event -> event.getLevel() == Level.WARN)
          .filter(event -> event.getFormattedMessage().contains("did not contain any executable processes"))
          .toList();

      assertEquals(1, warningLogs.size());
      assertTrue(warningLogs.getFirst().getFormattedMessage().contains("empty-process.bpmn"));

    }

    @Test
    @DisplayName("BPMN is read, prepared, wired and deployed")
    public void fullDeploymentPipelineIsExecuted() {

      // Configure properties and mocks
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Configure adapter1DeploymentService
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      // Create resources loader with BPMN file
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("my-process.bpmn",
              createDummyBpmnInputStream()));

      // Configure mock behavior for complete pipeline
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("MyProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      // Create DeploymentService and call deployResources
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      testee.deployResources(List.of("test-module"), resourcesLoader);

      // Verify that all pipeline steps were called
      verify(adapter1DeploymentService).readBpmn(
          eq("test-module"), eq("my-process.bpmn"), any(InputStream.class), eq(false));
      verify(adapter1DeploymentService).prepareBpmn(
          eq("test-module"), eq(null), eq("my-process.bpmn"), eq("MyProcess"), eq(42));
      verify(adapter1DeploymentService).wireBpmn(
          eq("test-module"), eq("my-process.bpmn"), eq("MyProcess"), eq(42), eq(100));
      verify(adapter1DeploymentService).deployResources(eq("test-module"), eq(100));

    }

    @Test
    @DisplayName("A DMN file is read after the processes, into the context they produced")
    public void aDecisionTableTravelsWithTheProcessesCallingIt() throws Exception {

      final var properties = createPropertiesWithAdapter("adapter-test1");
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      // a ByteArrayInputStream cannot say whether it was closed, so the test brings a
      // stream which remembers it
      final var closed = new java.util.concurrent.atomic.AtomicBoolean();
      final var decisionTable = new java.io.FilterInputStream(createDummyBpmnInputStream()) {

        @Override
        public void close() throws java.io.IOException {

          closed.set(true);
          super.close();

        }

      };
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = (
          location,
          extension) -> DeploymentService.BPMN_EXTENSION.equals(extension)
              ? Map.of("my-process.bpmn", createDummyBpmnInputStream())
              : Map.of("rating.dmn", decisionTable);

      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("MyProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);
      when(adapter1DeploymentService.readDmn(anyString(), any(), anyString(), any(InputStream.class)))
          .thenReturn(200);

      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      testee.deployResources(List.of("test-module"), resourcesLoader);

      // the context the processes produced is what the decision table is added to, and
      // what the adapter returned from it is what gets deployed
      verify(adapter1DeploymentService).readDmn(
          eq("test-module"), eq(100), eq("rating.dmn"), any(InputStream.class));
      verify(adapter1DeploymentService).deployResources(eq("test-module"), eq(200));
      assertTrue(
          closed.get(),
          "the pipeline owns the stream of a DMN file and closes it, like it does for BPMN");

    }

    @Test
    @DisplayName("A module without an executable process is told that its DMN is not deployed either")
    public void aModuleWithoutProcessesDeploysNoDecisionTableEither() {

      final var properties = createPropertiesWithAdapter("adapter-test1");
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of());

      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("empty-process.bpmn", createDummyBpmnInputStream()));

      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      testee.deployResources(List.of("test-module"), resourcesLoader);

      final var warnings = logWatcher.list
          .stream()
          .filter(event -> event.getLevel() == Level.WARN)
          .map(ILoggingEvent::getFormattedMessage)
          .toList();

      assertTrue(
          warnings.stream().anyMatch(message -> message.contains("DMN")),
          "the message says that a decision table alone is not deployed, logged: "
              + warnings);
      verify(adapter1DeploymentService, never())
          .readDmn(anyString(), any(), anyString(), any(InputStream.class));

    }

    @Test
    @DisplayName("Multiple BPMN files are processed sequentially with shared context")
    public void multipleBpmnFilesShareProcessingContext() {

      // Configure properties and mocks
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Configure adapter1DeploymentService
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      // Create resources loader with two BPMN files (LinkedHashMap to preserve insertion order)
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(location -> {
        final var resources = new LinkedHashMap<String, InputStream>();
        resources.put("process1.bpmn", createDummyBpmnInputStream());
        resources.put("process2.bpmn", createDummyBpmnInputStream());
        return resources;
      });

      // Mock behavior: first BPMN creates context, second uses it
      when(adapter1DeploymentService.readBpmn(anyString(), eq("process1.bpmn"), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("Process1", 1)));
      when(adapter1DeploymentService.readBpmn(anyString(), eq("process2.bpmn"), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("Process2", 2)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), eq(null), eq("process1.bpmn"), anyString(), any()))
          .thenReturn(100);
      when(adapter1DeploymentService.prepareBpmn(anyString(), eq(100), eq("process2.bpmn"), anyString(), any()))
          .thenReturn(100);

      // Create DeploymentService and call deployResources
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      testee.deployResources(List.of("test-module"), resourcesLoader);

      // Verify that prepareBpmn for process2 receives the context from process1
      verify(adapter1DeploymentService).prepareBpmn(
          eq("test-module"), eq(null), eq("process1.bpmn"), eq("Process1"), eq(1));
      verify(adapter1DeploymentService).prepareBpmn(
          eq("test-module"), eq(100), eq("process2.bpmn"), eq("Process2"), eq(2));

    }

    @Test
    @DisplayName("All executable processes of a single BPMN file are processed with threaded context")
    public void allProcessesOfBpmnFileAreProcessedWithThreadedContext() {

      // Configure properties and mocks
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Configure adapter1DeploymentService
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      // Create resources loader with a single BPMN file containing two executable processes
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("multi-process.bpmn",
              createDummyBpmnInputStream()));

      // readBpmn returns TWO executable processes for the same file
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("ProcessA", 41), Map.entry("ProcessB", 42)));

      // the context of the first process has to be passed to the second process as existing context
      when(adapter1DeploymentService.prepareBpmn(anyString(), eq(null), anyString(), eq("ProcessA"), eq(41)))
          .thenReturn(100);
      when(adapter1DeploymentService.prepareBpmn(anyString(), eq(100), anyString(), eq("ProcessB"), eq(42)))
          .thenReturn(200);

      // Create DeploymentService and call deployResources
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      testee.deployResources(List.of("test-module"), resourcesLoader);

      // Verify: BOTH processes are wired using their respective context
      verify(adapter1DeploymentService).wireBpmn(
          eq("test-module"), eq("multi-process.bpmn"), eq("ProcessA"), eq(41), eq(100));
      verify(adapter1DeploymentService).wireBpmn(
          eq("test-module"), eq("multi-process.bpmn"), eq("ProcessB"), eq(42), eq(200));

      // Verify: the FINAL context is deployed
      verify(adapter1DeploymentService).deployResources(eq("test-module"), eq(200));

    }

    @Test
    @DisplayName("Extension wiring services receive the same processing context as the adapter")
    public void extensionWiringServicesReceiveSameContextAsAdapter() {

      // Configure properties
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Configure adapter1DeploymentService
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      // Configure wiring service (getOrder() not called when only one service in list)
      lenient().when(adapter1WiringService.getOrder()).thenReturn(1);
      when(adapter1WiringService.getModelType()).thenReturn(Integer.class);
      when(adapter1WiringService.getProcessContextType()).thenReturn(Integer.class);

      // Create resources loader
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("process.bpmn",
              createDummyBpmnInputStream()));

      // Configure mock behavior
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      // Create DeploymentService with wiring service
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of(adapter1WiringService));

      testee.deployResources(List.of("test-module"), resourcesLoader);

      // Verify: the extension receives the context returned by prepareBpmn
      // (the same one passed to the adapter's wireBpmn), not the context of a previous file
      verify(adapter1DeploymentService).wireBpmn(
          eq("test-module"), eq("process.bpmn"), eq("TestProcess"), eq(42), eq(100));
      verify(adapter1WiringService).wireBpmn(
          eq("test-module"), eq("process.bpmn"), eq("TestProcess"), eq(42), eq(100));

    }

    @Test
    @DisplayName("Extension wiring services declaring an interface as model type are matched")
    public void extensionWiringServicesWithInterfaceModelTypeAreMatched() {

      // Configure properties
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Configure adapter1DeploymentService (model and context types are classes)
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");
      when(adapter1DeploymentService.getModelType()).thenReturn(Integer.class);
      when(adapter1DeploymentService.getProcessContextType()).thenReturn(Integer.class);

      // Configure wiring service declaring INTERFACES as model and context types
      lenient().when(interfaceTypeWiringService.getOrder()).thenReturn(1);
      doReturn(Comparable.class).when(interfaceTypeWiringService).getModelType();
      doReturn(Comparable.class).when(interfaceTypeWiringService).getProcessContextType();

      // Create resources loader
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("process.bpmn",
              createDummyBpmnInputStream()));

      // Configure mock behavior
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      // Create DeploymentService with wiring service
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of(interfaceTypeWiringService));

      testee.deployResources(List.of("test-module"), resourcesLoader);

      // Verify: the extension is matched by assignability (Integer implements Comparable)
      verify(interfaceTypeWiringService).wireBpmn(
          eq("test-module"), eq("process.bpmn"), eq("TestProcess"), eq(42), eq(100));

      testee.startWorkflowProcessing(List.of("test-module"));

      // Verify: the extension is also started by assignability
      verify(interfaceTypeWiringService).startWorkflowProcessing(eq("test-module"), eq(100));

    }

    @Test
    @DisplayName("All prioritized adapters are deployed and started")
    public void allPrioritizedAdaptersAreDeployedAndStarted() {

      // Configure properties with TWO prioritized adapters
      final var properties = createPropertiesWithAdapters("adapter-test1", "adapter-test2");

      // Configure both adapters
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");
      when(adapter2DeploymentService.getAdapterId()).thenReturn("adapter-test2");

      // Create resources loader
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("process.bpmn",
              createDummyBpmnInputStream()));

      // Configure mock behavior of both adapters
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);
      when(adapter2DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 4711L)));
      when(adapter2DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(4200L);

      // Create DeploymentService with both adapters
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService, adapter2DeploymentService), List.of());

      testee.deployResources(List.of("test-module"), resourcesLoader);

      // Verify: resources are deployed to BOTH adapters
      verify(adapter1DeploymentService).deployResources(eq("test-module"), eq(100));
      verify(adapter2DeploymentService).deployResources(eq("test-module"), eq(4200L));

      testee.startWorkflowProcessing(List.of("test-module"));

      // Verify: BOTH adapters keep processing workflows (necessary for BPMS migration)
      verify(adapter1DeploymentService).startWorkflowProcessing(eq("test-module"), eq(100));
      verify(adapter2DeploymentService).startWorkflowProcessing(eq("test-module"), eq(4200L));

    }

    @Test
    @DisplayName("Extension wiring services are filtered by model type and process context type")
    public void extensionWiringServicesAreFilteredAndCalled() {

      // Configure properties
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Configure adapter1DeploymentService
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      // Configure wiring services (sorted by order in constructor)
      when(adapter1WiringService.getOrder()).thenReturn(1);
      when(adapter1WiringService.getModelType()).thenReturn(Integer.class);
      when(adapter1WiringService.getProcessContextType()).thenReturn(Integer.class);
      when(adapter2WiringService.getOrder()).thenReturn(2);
      lenient().when(adapter2WiringService.getModelType()).thenReturn(Long.class);
      lenient().when(adapter2WiringService.getProcessContextType()).thenReturn(Long.class);

      // Create resources loader
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("process.bpmn",
              createDummyBpmnInputStream()));

      // Configure mock behavior
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      // Create DeploymentService with wiring services
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of(adapter1WiringService, adapter2WiringService));

      testee.deployResources(List.of("test-module"), resourcesLoader);

      // Verify: adapter1WiringService is called (Integer/Integer matches)
      verify(adapter1WiringService).wireBpmn(
          eq("test-module"), eq("process.bpmn"), eq("TestProcess"), eq(42), any());

      // Verify: adapter2WiringService is NOT called (Long/Long does not match Integer/Integer)
      verify(adapter2WiringService, never()).wireBpmn(anyString(), anyString(), anyString(), any(), any());

    }

    @Test
    @DisplayName("VanillaBP BPMN flag is passed correctly to readBpmn")
    public void vanillaBpBpmnFlagIsPassedCorrectly() {

      // Create properties with VanillaBP resources location (not adapter-specific)
      final var properties = MigrationAdapterProperties
          .builder()
          .adapters(Map.of("adapter-test1", AdapterConfigProperties.ofType("dummy")))
          .prioritizedAdapters(List.of("adapter-test1"))
          .resourcesLocation("classpath:vanillabp-processes")
          .workflowModules(Map.of(
              "test-module",
              WorkflowModuleAdapterProperties
                  .builder()
                  .workflowModuleId("test-module")
                  .build()))
          .build();
      properties.validateAndLink();

      // Configure adapter1DeploymentService
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      // Create resources loader
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("process.bpmn",
              createDummyBpmnInputStream()));

      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      testee.deployResources(List.of("test-module"), resourcesLoader);

      // Verify: isVanillaBpBpmn is true when using global resourcesLocation
      verify(adapter1DeploymentService).readBpmn(
          eq("test-module"), eq("process.bpmn"), any(InputStream.class), eq(true));

    }

  }

  @Nested
  @DisplayName("startWorkflowProcessing Tests")
  class StartWorkflowProcessingTests {

    @Test
    @DisplayName("startWorkflowProcessing is called for deployed modules")
    public void startWorkflowProcessingIsCalledForDeployedModules() {

      // Configure properties and mocks
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Configure adapter1DeploymentService
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      // Create resources loader
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("process.bpmn",
              createDummyBpmnInputStream()));

      // Configure mock behavior
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      // Create DeploymentService
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      // First deploy
      testee.deployResources(List.of("test-module"), resourcesLoader);

      // Then start
      testee.startWorkflowProcessing(List.of("test-module"));

      // Verify that startWorkflowProcessing was called on DeploymentService
      verify(adapter1DeploymentService).startWorkflowProcessing(eq("test-module"), eq(100));

    }

    @Test
    @DisplayName("startWorkflowProcessing skips non-deployed modules")
    public void startWorkflowProcessingSkipsNonDeployedModules() {

      // Configure properties
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Create DeploymentService WITHOUT prior deployment
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      // Call startWorkflowProcessing for non-deployed module
      testee.startWorkflowProcessing(List.of("non-deployed-module"));

      // Verify that startWorkflowProcessing was NOT called
      verify(adapter1DeploymentService, never()).startWorkflowProcessing(anyString(), any());

    }

    @Test
    @DisplayName("Extension wiring services are called on start")
    public void extensionWiringServicesAreCalledOnStart() {

      // Configure properties and mocks
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Configure adapter1DeploymentService
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");
      when(adapter1DeploymentService.getModelType()).thenReturn(Integer.class);
      when(adapter1DeploymentService.getProcessContextType()).thenReturn(Integer.class);

      // Configure wiring service (getOrder() not called when only one service in list)
      lenient().when(adapter1WiringService.getOrder()).thenReturn(1);
      when(adapter1WiringService.getModelType()).thenReturn(Integer.class);
      when(adapter1WiringService.getProcessContextType()).thenReturn(Integer.class);

      // Create resources loader
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("process.bpmn",
              createDummyBpmnInputStream()));

      // Configure mock behavior
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      // Create DeploymentService with wiring service
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of(adapter1WiringService));

      // Deploy and start
      testee.deployResources(List.of("test-module"), resourcesLoader);
      testee.startWorkflowProcessing(List.of("test-module"));

      // Verify that startWorkflowProcessing was called on wiring service
      verify(adapter1WiringService).startWorkflowProcessing(eq("test-module"), eq(100));

    }

    @Test
    @DisplayName("Only matching extension wiring services are called on start")
    public void onlyMatchingExtensionWiringServicesAreCalledOnStart() {

      // Configure properties and mocks
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Configure adapter1DeploymentService
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");
      when(adapter1DeploymentService.getModelType()).thenReturn(Integer.class);
      when(adapter1DeploymentService.getProcessContextType()).thenReturn(Integer.class);

      // Configure wiring services
      when(adapter1WiringService.getOrder()).thenReturn(1);
      when(adapter1WiringService.getModelType()).thenReturn(Integer.class);
      when(adapter1WiringService.getProcessContextType()).thenReturn(Integer.class);
      when(adapter2WiringService.getOrder()).thenReturn(2);
      lenient().when(adapter2WiringService.getModelType()).thenReturn(Long.class);
      lenient().when(adapter2WiringService.getProcessContextType()).thenReturn(Long.class);

      // Create resources loader
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("process.bpmn",
              createDummyBpmnInputStream()));

      // Configure mock behavior
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      // Create DeploymentService with both wiring services
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of(adapter1WiringService, adapter2WiringService));

      // Deploy and start
      testee.deployResources(List.of("test-module"), resourcesLoader);
      testee.startWorkflowProcessing(List.of("test-module"));

      // Verify: adapter1WiringService is called (matches)
      verify(adapter1WiringService).startWorkflowProcessing(eq("test-module"), eq(100));

      // Verify: adapter2WiringService is NOT called (does not match)
      verify(adapter2WiringService, never()).startWorkflowProcessing(anyString(), any());

    }

  }

  @Nested
  @DisplayName("stopWorkflowProcessing Tests")
  class StopWorkflowProcessingTests {

    @Test
    @DisplayName("stopWorkflowProcessing is called for deployed modules using the deployed context")
    public void stopWorkflowProcessingIsCalledForDeployedModules() {

      // Configure properties and mocks
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Configure adapter1DeploymentService
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      // Create resources loader
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("process.bpmn",
              createDummyBpmnInputStream()));

      // Configure mock behavior
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      // Create DeploymentService
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      // Deploy, start and stop
      testee.deployResources(List.of("test-module"), resourcesLoader);
      testee.startWorkflowProcessing(List.of("test-module"));
      testee.stopWorkflowProcessing(List.of("test-module"));

      // Verify that stopWorkflowProcessing was called with the deployed context
      verify(adapter1DeploymentService).stopWorkflowProcessing(eq("test-module"), eq(100));

    }

    @Test
    @DisplayName("stopWorkflowProcessing skips non-deployed modules")
    public void stopWorkflowProcessingSkipsNonDeployedModules() {

      // Configure properties
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Create DeploymentService WITHOUT prior deployment
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      // Call stopWorkflowProcessing for a non-deployed module
      testee.stopWorkflowProcessing(List.of("non-deployed-module"));

      // Verify that stopWorkflowProcessing was NOT called
      verify(adapter1DeploymentService, never()).stopWorkflowProcessing(anyString(), any());

    }

    @Test
    @DisplayName("Matching extension wiring services are stopped before the adapter")
    public void extensionWiringServicesAreStoppedBeforeAdapters() {

      // Configure properties and mocks
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Configure adapter1DeploymentService
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");
      when(adapter1DeploymentService.getModelType()).thenReturn(Integer.class);
      when(adapter1DeploymentService.getProcessContextType()).thenReturn(Integer.class);

      // Configure wiring services
      when(adapter1WiringService.getOrder()).thenReturn(1);
      when(adapter1WiringService.getModelType()).thenReturn(Integer.class);
      when(adapter1WiringService.getProcessContextType()).thenReturn(Integer.class);
      when(adapter2WiringService.getOrder()).thenReturn(2);
      lenient().when(adapter2WiringService.getModelType()).thenReturn(Long.class);
      lenient().when(adapter2WiringService.getProcessContextType()).thenReturn(Long.class);

      // Create resources loader
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("process.bpmn",
              createDummyBpmnInputStream()));

      // Configure mock behavior
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      // Create DeploymentService with both wiring services
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of(adapter1WiringService, adapter2WiringService));

      // Deploy, start and stop
      testee.deployResources(List.of("test-module"), resourcesLoader);
      testee.startWorkflowProcessing(List.of("test-module"));
      testee.stopWorkflowProcessing(List.of("test-module"));

      // Verify: matching extension is stopped BEFORE the adapter (reverse of start order)
      final var inOrder = Mockito.inOrder(adapter1WiringService, adapter1DeploymentService);
      inOrder.verify(adapter1WiringService).stopWorkflowProcessing(eq("test-module"), eq(100));
      inOrder.verify(adapter1DeploymentService).stopWorkflowProcessing(eq("test-module"), eq(100));

      // Verify: non-matching extension is NOT stopped
      verify(adapter2WiringService, never()).stopWorkflowProcessing(anyString(), any());

    }

  }

  @Nested
  @DisplayName("Deployment-failure policy Tests")
  class DeploymentFailurePolicyTests {

    @Test
    @DisplayName("Failure of a non-first-priority adapter with policy 'warn' boots anyway")
    public void nonPrimaryAdapterFailureWithWarnPolicyBootsAnyway() {

      // Configure properties with TWO prioritized adapters, second one may fail
      final var properties = createPropertiesWithAdapters("adapter-test1", "adapter-test2");
      properties.getAdapters().get("adapter-test2").setDeploymentFailure(DeploymentFailurePolicy.WARN);

      // Configure both adapters
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");
      when(adapter2DeploymentService.getAdapterId()).thenReturn("adapter-test2");

      // Create resources loader
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("process.bpmn",
              createDummyBpmnInputStream()));

      // First adapter deploys fine, second adapter fails (e.g. old BPMS unreachable)
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);
      when(adapter2DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenThrow(new IllegalStateException("BPMS unreachable"));

      // Create DeploymentService with both adapters
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService, adapter2DeploymentService), List.of());

      // Deployment does NOT fail
      testee.deployResources(List.of("test-module"), resourcesLoader);

      // Verify: the first adapter was deployed
      verify(adapter1DeploymentService).deployResources(eq("test-module"), eq(100));

      // Verify: a warning naming the failing adapter was logged
      final var warningLogs = logWatcher.list
          .stream()
          .filter(event -> event.getLevel() == Level.WARN)
          .filter(event -> event.getFormattedMessage().contains("adapter-test2"))
          .filter(event -> event.getFormattedMessage().contains("deployment-failure"))
          .toList();
      assertEquals(1, warningLogs.size());

      // Verify: only the successfully deployed adapter starts processing
      testee.startWorkflowProcessing(List.of("test-module"));
      verify(adapter1DeploymentService).startWorkflowProcessing(eq("test-module"), eq(100));
      verify(adapter2DeploymentService, never()).startWorkflowProcessing(anyString(), any());

    }

    @Test
    @DisplayName("Failure of the first-priority adapter fails the boot even with policy 'warn'")
    public void primaryAdapterFailureFailsEvenWithWarnPolicy() {

      // Configure properties with TWO prioritized adapters, FIRST one may fail (policy is ignored)
      final var properties = createPropertiesWithAdapters("adapter-test1", "adapter-test2");
      properties.getAdapters().get("adapter-test1").setDeploymentFailure(DeploymentFailurePolicy.WARN);

      // Configure the first adapter to fail
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");
      lenient().when(adapter2DeploymentService.getAdapterId()).thenReturn("adapter-test2");
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenThrow(new IllegalStateException("BPMS unreachable"));

      // Create resources loader
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("process.bpmn",
              createDummyBpmnInputStream()));

      // Create DeploymentService with both adapters
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService, adapter2DeploymentService), List.of());

      // Deployment fails
      final var exception = assertThrows(
          IllegalStateException.class,
          () -> testee.deployResources(List.of("test-module"), resourcesLoader));
      assertTrue(exception.getMessage().contains("BPMS unreachable"));

    }

    @Test
    @DisplayName("Failure of a non-first-priority adapter fails the boot by default")
    public void nonPrimaryAdapterFailureFailsByDefault() {

      // Configure properties with TWO prioritized adapters without any deployment-failure policy
      final var properties = createPropertiesWithAdapters("adapter-test1", "adapter-test2");

      // Configure both adapters, second one fails
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");
      when(adapter2DeploymentService.getAdapterId()).thenReturn("adapter-test2");
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);
      when(adapter2DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenThrow(new IllegalStateException("BPMS unreachable"));

      // Create resources loader
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("process.bpmn",
              createDummyBpmnInputStream()));

      // Create DeploymentService with both adapters
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService, adapter2DeploymentService), List.of());

      // Deployment fails (deployment-failure defaults to 'fail')
      final var exception = assertThrows(
          IllegalStateException.class,
          () -> testee.deployResources(List.of("test-module"), resourcesLoader));
      assertTrue(exception.getMessage().contains("BPMS unreachable"));

    }

  }

  @Nested
  @DisplayName("Workflow-level priorities Tests")
  class WorkflowLevelPrioritiesTests {

    @Test
    @DisplayName("Adapter named at the workflow level only receives the module's deployment (union)")
    public void workflowLevelAdapterIsIncludedInDeploymentUnion() {

      // module-level list names adapter-test1 only, one workflow overrides to adapter-test2
      final var properties = createPropertiesWithWorkflowOverride(
          List.of("adapter-test1"),
          "ProcessOnB",
          List.of("adapter-test2"));

      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");
      when(adapter2DeploymentService.getAdapterId()).thenReturn("adapter-test2");

      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("process.bpmn",
              createDummyBpmnInputStream()));

      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("ProcessOnB", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);
      when(adapter2DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("ProcessOnB", 4711L)));
      when(adapter2DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(4200L);

      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService, adapter2DeploymentService), List.of());

      testee.deployResources(List.of("test-module"), resourcesLoader);

      // Verify: BOTH adapters receive the module's resources although the module-level
      // list names adapter-test1 only - otherwise starting 'ProcessOnB' via
      // adapter-test2 would fail at runtime
      verify(adapter1DeploymentService).deployResources(eq("test-module"), eq(100));
      verify(adapter2DeploymentService).deployResources(eq("test-module"), eq(4200L));

      testee.startWorkflowProcessing(List.of("test-module"));

      // Verify: BOTH adapters start processing workflows
      verify(adapter1DeploymentService).startWorkflowProcessing(eq("test-module"), eq(100));
      verify(adapter2DeploymentService).startWorkflowProcessing(eq("test-module"), eq(4200L));

    }

    @Test
    @DisplayName("Failure of an adapter being first priority for a single workflow fails the boot even with policy 'warn'")
    public void workflowLevelFirstPriorityAdapterFailureFailsEvenWithWarnPolicy() {

      // adapter-test2 is NOT first for the module but IS first for one workflow
      final var properties = createPropertiesWithWorkflowOverride(
          List.of("adapter-test1", "adapter-test2"),
          "ProcessOnB",
          List.of("adapter-test2", "adapter-test1"));
      properties.getAdapters().get("adapter-test2").setDeploymentFailure(DeploymentFailurePolicy.WARN);

      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");
      when(adapter2DeploymentService.getAdapterId()).thenReturn("adapter-test2");

      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("process.bpmn",
              createDummyBpmnInputStream()));

      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("ProcessOnB", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);
      when(adapter2DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenThrow(new IllegalStateException("BPMS unreachable"));

      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService, adapter2DeploymentService), List.of());

      // Deployment fails although the policy is 'warn': starting 'ProcessOnB' would
      // be impossible without adapter-test2
      final var exception = assertThrows(
          IllegalStateException.class,
          () -> testee.deployResources(List.of("test-module"), resourcesLoader));
      assertTrue(exception.getMessage().contains("BPMS unreachable"));

    }

    @Test
    @DisplayName("Configured workflow ID matching no BPMN process is reported by a WARN naming the known IDs")
    public void unknownConfiguredWorkflowIdIsWarned() {

      final var properties = createPropertiesWithWorkflowOverride(
          List.of("adapter-test1"),
          "NoSuchProcess",
          List.of());

      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("process.bpmn",
              createDummyBpmnInputStream()));

      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      // only a WARN - the BPMN may arrive later (e.g. during a BPMS migration)
      testee.deployResources(List.of("test-module"), resourcesLoader);

      final var warningLogs = logWatcher.list
          .stream()
          .filter(event -> event.getLevel() == Level.WARN)
          .map(ILoggingEvent::getFormattedMessage)
          .filter(msg -> msg.contains("vanillabp.workflow-modules.test-module.workflows.NoSuchProcess"))
          .toList();
      assertEquals(1, warningLogs.size());
      assertTrue(warningLogs.getFirst().contains("'TestProcess'"));

    }

    @Test
    @DisplayName("Configured workflow ID matching a BPMN process is not warned about")
    public void knownConfiguredWorkflowIdIsNotWarned() {

      final var properties = createPropertiesWithWorkflowOverride(
          List.of("adapter-test1"),
          "TestProcess",
          List.of());

      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("process.bpmn",
              createDummyBpmnInputStream()));

      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      testee.deployResources(List.of("test-module"), resourcesLoader);

      assertTrue(logWatcher.list
          .stream()
          .filter(event -> event.getLevel() == Level.WARN)
          .map(ILoggingEvent::getFormattedMessage)
          .noneMatch(msg -> msg.contains("workflows.TestProcess")));

    }

  }

  @Nested
  @DisplayName("BPMN processes no workflow service claims")
  class UnclaimedBpmnProcessesTests {

    /**
     * A wiring interface answering that the given processes of 'test-module' are
     * claimed by nobody - what the registry collects while an adapter wires them.
     */
    private io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring wiringReporting(
        final String... unclaimedProcessIds) {

      return new io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring() {

        @Override
        public void validateTaskWiring(
            final String workflowModuleId,
            final String bpmnProcessId,
            final java.util.Collection<io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec> tasks) {
        }

        @Override
        public void validateNoUnwiredWorkflowTaskMethods(
            final String workflowModuleId) {
        }

        @Override
        public java.util.Collection<String> bpmnProcessesWithoutWorkflowService(
            final String workflowModuleId) {

          return List.of(unclaimedProcessIds);

        }

        @Override
        public String resolveWorkflowAggregateIdName(
            final String workflowModuleId,
            final String bpmnProcessId) {

          return null;

        }

      };

    }

    private DeploymentService deployTwoProcessesOfOneFile(
        final io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring workflowTaskWiring) {

      final var properties = createPropertiesWithAdapter("adapter-test1");
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("two-processes.bpmn", createDummyBpmnInputStream()));

      // one file, two executable processes - what a modeller produces by drawing a
      // called process next to the calling one
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("Claiming", 42), Map.entry("Unclaimed", 43)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of(), workflowTaskWiring);
      testee.deployResources(List.of("test-module"), resourcesLoader);
      return testee;

    }

    @Test
    @DisplayName("The WARN names the process, its file and the workflow module, and what it costs")
    public void unclaimedProcessIsWarnedAboutNamingItsFile() {

      deployTwoProcessesOfOneFile(wiringReporting("Unclaimed"));

      final var warnings = logWatcher.list
          .stream()
          .filter(event -> event.getLevel() == Level.WARN)
          .map(ILoggingEvent::getFormattedMessage)
          .filter(message -> message.contains("no @WorkflowService class"))
          .toList();
      assertEquals(1, warnings.size(), "one report per workflow module, whatever it lists: "
          + warnings);
      final var warning = warnings.getFirst();
      assertTrue(warning.contains("'test-module'"), warning);
      assertTrue(warning.contains("process 'Unclaimed' of file 'two-processes.bpmn'"), warning);
      assertTrue(warning.contains("not get past its first task"), warning);
      assertTrue(warning.contains("@WorkflowService(bpmnProcess"), warning);
      assertTrue(warning.contains("take the process out of its file"), warning);
      assertFalse(warning.contains("Claiming"), "the claimed process of the same file is not reported: "
          + warning);

    }

    @Test
    @DisplayName("A module whose processes are all claimed is not reported")
    public void claimedProcessesAreNotWarnedAbout() {

      deployTwoProcessesOfOneFile(wiringReporting());

      assertTrue(
          logWatcher.list
              .stream()
              .map(ILoggingEvent::getFormattedMessage)
              .noneMatch(message -> message.contains("no @WorkflowService class")),
          "nothing to report where every deployed process is served");

    }

    @Test
    @DisplayName("An adapter which does not answer the query leaves the deployment silent")
    public void anAdapterWithoutTheQueryChangesNothing() {

      // the pipeline runs without the core's wiring interface in tests exercising
      // only the pipeline itself, and it has to stay silent then
      final var properties = createPropertiesWithAdapter("adapter-test1");
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("process.bpmn", createDummyBpmnInputStream()));
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      new DeploymentService(properties, List.of(adapter1DeploymentService), List.of())
          .deployResources(List.of("test-module"), resourcesLoader);

      assertTrue(
          logWatcher.list
              .stream()
              .map(ILoggingEvent::getFormattedMessage)
              .noneMatch(message -> message.contains("no @WorkflowService class")),
          "no wiring interface, nothing to ask");

    }

  }

  @Nested
  @DisplayName("Adapters as wiring services Tests")
  class AdaptersAsWiringServicesTests {

    @Test
    @DisplayName("Adapters passed as wiring services are not wired a second time")
    public void adaptersPassedAsWiringServicesAreFiltered() {

      // Configure properties and mocks
      final var properties = createPropertiesWithAdapter("adapter-test1");

      // Configure adapter1DeploymentService
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      // Create resources loader
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("process.bpmn",
              createDummyBpmnInputStream()));

      // Configure mock behavior
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      // Create DeploymentService passing the ADAPTER also as a wiring service (as bean
      // containers collecting by type would do, since AdapterDeploymentService extends
      // ExtensionWiringService)
      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of(adapter1DeploymentService));

      testee.deployResources(List.of("test-module"), resourcesLoader);
      testee.startWorkflowProcessing(List.of("test-module"));

      // Verify: the adapter is wired and started exactly ONCE (not additionally as an extension)
      verify(adapter1DeploymentService, Mockito.times(1)).wireBpmn(
          eq("test-module"), eq("process.bpmn"), eq("TestProcess"), eq(42), eq(100));
      verify(adapter1DeploymentService, Mockito.times(1)).startWorkflowProcessing(
          eq("test-module"), eq(100));

    }

  }

  // === Helper Methods ===

  /**
   * Creates properties with a configured adapter and workflow module.
   */
  private MigrationAdapterProperties createPropertiesWithAdapter(
      final String adapterId) {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(adapterId, AdapterConfigProperties.ofType("dummy")))
        .workflowModules(Map.of(
            "test-module",
            WorkflowModuleAdapterProperties
                .builder()
                .workflowModuleId("test-module")
                .prioritizedAdapters(List.of(adapterId))
                .adapters(Map.of(adapterId, AdapterProperties
                    .builder()
                    .resourcesLocation("classpath:test-module/processes")
                    .build()))
                .build()))
        .build();
    // Link back-references (workflowModuleId etc.)
    properties.validateAndLink();
    return properties;

  }

  /**
   * Properties of module 'test-module' with the given module-level prioritized
   * adapters and ONE workflow having its own (possibly empty) prioritized-adapters
   * override. Adapter sections exist for all adapter ids of both lists.
   */
  private MigrationAdapterProperties createPropertiesWithWorkflowOverride(
      final List<String> moduleAdapterIds,
      final String bpmnProcessId,
      final List<String> workflowAdapterIds) {

    final var adapters = new LinkedHashMap<String, AdapterConfigProperties>();
    final var adapterProperties = new LinkedHashMap<String, AdapterProperties>();
    for (final var adapterId : Stream
        .concat(moduleAdapterIds.stream(), workflowAdapterIds.stream())
        .distinct()
        .toList()) {
      adapters.put(adapterId, AdapterConfigProperties.ofType("dummy"));
      adapterProperties.put(adapterId, AdapterProperties
          .builder()
          .resourcesLocation("classpath:test-module/processes/"
              + adapterId)
          .build());
    }

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(adapters)
        .workflowModules(Map.of(
            "test-module",
            WorkflowModuleAdapterProperties
                .builder()
                .workflowModuleId("test-module")
                .prioritizedAdapters(moduleAdapterIds)
                .adapters(adapterProperties)
                .workflows(Map.of(bpmnProcessId, WorkflowAdapterProperties
                    .builder()
                    .bpmnProcessId(bpmnProcessId)
                    .prioritizedAdapters(workflowAdapterIds)
                    .build()))
                .build()))
        .build();
    // Link back-references (workflowModuleId etc.)
    properties.validateAndLink();
    return properties;

  }

  /**
   * Creates properties with multiple configured adapters (in given priority) and a workflow module.
   */
  private MigrationAdapterProperties createPropertiesWithAdapters(
      final String... adapterIds) {

    final var adapters = new LinkedHashMap<String, AdapterConfigProperties>();
    final var adapterProperties = new LinkedHashMap<String, AdapterProperties>();
    for (final var adapterId : adapterIds) {
      adapters.put(adapterId, AdapterConfigProperties.ofType("dummy"));
      adapterProperties.put(adapterId, AdapterProperties
          .builder()
          .resourcesLocation("classpath:test-module/processes/"
              + adapterId)
          .build());
    }

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(adapters)
        .workflowModules(Map.of(
            "test-module",
            WorkflowModuleAdapterProperties
                .builder()
                .workflowModuleId("test-module")
                .prioritizedAdapters(List.of(adapterIds))
                .adapters(adapterProperties)
                .build()))
        .build();
    // Link back-references (workflowModuleId etc.)
    properties.validateAndLink();
    return properties;

  }

  /**
   * An input stream tracking whether it was closed - used to pin the
   * stream-ownership contract of the deployment pipeline.
   */
  private static class CountingCloseInputStream extends ByteArrayInputStream {

    private boolean closed = false;

    /**
     * Creates a dummy InputStream for BPMN tests.
     */
    private CountingCloseInputStream() {
      super("<bpmn>dummy</bpmn>".getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() throws java.io.IOException {
      closed = true;
      super.close();
    }

  }

  @Nested
  @DisplayName("Pipeline hygiene tests")
  class PipelineHygieneTests {

    @Test
    @DisplayName("All BPMN streams are closed even if parsing the first of several files fails")
    public void allStreamsAreClosedOnParseFailure() {

      final var properties = createPropertiesWithAdapter("adapter-test1");
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      final var stream1 = new CountingCloseInputStream();
      final var stream2 = new CountingCloseInputStream();
      final var files = new java.util.LinkedHashMap<String, InputStream>();
      files.put("first.bpmn", stream1);
      files.put("second.bpmn", stream2);
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(location -> files);

      // parsing the FIRST file fails - the second file is never processed
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenThrow(new io.vanillabp.integration.adapter.spi.BpmnParseException("parse failure of first.bpmn"));

      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      assertThrows(
          io.vanillabp.integration.adapter.spi.BpmnParseException.class,
          () -> testee.deployResources(List.of("test-module"), resourcesLoader));

      // no open streams remain - also the never-processed one is closed
      assertTrue(stream1.closed, "stream of the failing file has to be closed");
      assertTrue(stream2.closed, "stream of the never-processed file has to be closed");

    }

    @Test
    @DisplayName("Zero executable processes skip the adapter with a warning instead of a null context")
    public void zeroExecutableProcessesSkipAdapter() {

      final var properties = createPropertiesWithAdapter("adapter-test1");
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("collab.bpmn",
              createDummyBpmnInputStream()));

      // no executable processes in the file
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of());

      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      testee.deployResources(List.of("test-module"), resourcesLoader);
      testee.startWorkflowProcessing(List.of("test-module"));
      testee.stopWorkflowProcessing(List.of("test-module"));

      // the adapter is never called with a null processing context
      verify(adapter1DeploymentService, never()).deployResources(anyString(), any());
      verify(adapter1DeploymentService, never()).startWorkflowProcessing(anyString(), any());
      verify(adapter1DeploymentService, never()).stopWorkflowProcessing(anyString(), any());

      // the warning names the location and the property key to change
      assertTrue(logWatcher.list
          .stream()
          .map(ILoggingEvent::getFormattedMessage)
          .anyMatch(msg -> msg.contains("No executable BPMN processes found") && msg.contains("test-module") && msg
              .contains("resources-location")));

    }

    @Test
    @DisplayName("A null context returned by prepareBpmn fails with a guiding message")
    public void nullContextFromPrepareBpmnFails() {

      final var properties = createPropertiesWithAdapter("adapter-test1");
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");

      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("process.bpmn",
              createDummyBpmnInputStream()));

      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));
      when(adapter1DeploymentService.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(null);

      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> testee.deployResources(List.of("test-module"), resourcesLoader));
      assertTrue(exception.getMessage().contains("prepareBpmn"));
      assertTrue(exception.getMessage().contains("adapter-test1"));

    }

    @Test
    @DisplayName("Extension matching uses declared types: subtype extension is neither wired nor started")
    public void subtypeExtensionIsNeitherWiredNorStarted() {

      final var properties = createPropertiesWithAdapter("adapter-test1");

      // an adapter declaring Number as model type - but delivering an Integer
      // instance at runtime
      @SuppressWarnings("unchecked")
      final AdapterDeploymentService<Number, Number> numberAdapter = org.mockito.Mockito
          .mock(AdapterDeploymentService.class);
      lenient().when(numberAdapter.getAdapterId()).thenReturn("adapter-test1");
      lenient().when(numberAdapter.getModelType()).thenReturn(Number.class);
      lenient().when(numberAdapter.getProcessContextType()).thenReturn(Number.class);
      lenient().when(numberAdapter.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of(Map.entry("TestProcess", 42)));
      lenient().when(numberAdapter.prepareBpmn(anyString(), any(), anyString(), anyString(), any()))
          .thenReturn(100);

      // an extension declaring the SUBTYPE Integer: with the former isInstance
      // matching it was wired (the actual model IS an Integer) but never started -
      // declared-type matching makes it consistently neither
      lenient().when(adapter1WiringService.getOrder()).thenReturn(1);
      lenient().when(adapter1WiringService.getModelType()).thenReturn(Integer.class);
      lenient().when(adapter1WiringService.getProcessContextType()).thenReturn(Integer.class);

      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader = bpmnFilesOnly(
          location -> Map.of("process.bpmn",
              createDummyBpmnInputStream()));

      final var testee = new DeploymentService(
          properties, List.of(numberAdapter), List.of(adapter1WiringService));

      testee.deployResources(List.of("test-module"), resourcesLoader);
      testee.startWorkflowProcessing(List.of("test-module"));

      verify(adapter1WiringService, never()).wireBpmn(anyString(), anyString(), anyString(), any(), any());
      verify(adapter1WiringService, never()).startWorkflowProcessing(anyString(), any());

    }

  }


  /**
   * The rule: several ids of ONE adapter type only make sense if they address
   * DIFFERENT systems, and whether two configurations differ is BPMS knowledge, so the
   * adapter decides. The SPI documents WHEN the adapter is asked - once per type, on the
   * first deployment service of that type, with the ids in priority order and before
   * anything reaches a BPMS, and this is what holds it.
   */
  @Nested
  @DisplayName("Several ids of one adapter type Tests")
  class DistinctAdapterInstancesTests {

    @Test
    @DisplayName("A single id of a type is not asked whether its instances differ")
    public void aSingleIdIsNotAsked() {

      final var properties = propertiesWithGlobalPriorities(List.of("adapter-test1"));
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");
      when(adapter1DeploymentService.getAdapterType()).thenReturn("dummy");
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of());

      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of());

      testee.deployResources(List.of("test-module"), oneBpmnFile());

      verify(adapter1DeploymentService, never()).validateDistinctAdapterInstances(any());

    }

    @Test
    @DisplayName("Two ids of ONE type are asked once, on the first service, in priority order")
    public void twoIdsOfOneTypeAreAskedOnce() {

      // the service order and the priority order differ on purpose
      final var properties = propertiesWithGlobalPriorities(List.of("adapter-test2", "adapter-test1"));
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");
      when(adapter2DeploymentService.getAdapterId()).thenReturn("adapter-test2");
      when(adapter1DeploymentService.getAdapterType()).thenReturn("dummy");
      when(adapter2DeploymentService.getAdapterType()).thenReturn("dummy");
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of());
      when(adapter2DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of());

      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService, adapter2DeploymentService), List.of());

      testee.deployResources(List.of("test-module"), oneBpmnFile());

      verify(adapter1DeploymentService)
          .validateDistinctAdapterInstances(List.of("adapter-test2", "adapter-test1"));
      verify(adapter2DeploymentService, never()).validateDistinctAdapterInstances(any());

    }

    @Test
    @DisplayName("Two ids of DIFFERENT types are two single instances - neither is asked")
    public void twoTypesAreNotAsked() {

      final var properties = propertiesWithGlobalPriorities(List.of("adapter-test1", "adapter-test2"));
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");
      when(adapter2DeploymentService.getAdapterId()).thenReturn("adapter-test2");
      when(adapter1DeploymentService.getAdapterType()).thenReturn("dummy");
      when(adapter2DeploymentService.getAdapterType()).thenReturn("another-dummy");
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of());
      when(adapter2DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of());

      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService, adapter2DeploymentService), List.of());

      testee.deployResources(List.of("test-module"), oneBpmnFile());

      verify(adapter1DeploymentService, never()).validateDistinctAdapterInstances(any());
      verify(adapter2DeploymentService, never()).validateDistinctAdapterInstances(any());

    }

    @Test
    @DisplayName("Ids which cannot be told apart end the boot before any BPMN was read")
    public void aFailingValidationEndsTheBootBeforeDeploying() {

      final var properties = propertiesWithGlobalPriorities(List.of("adapter-test1", "adapter-test2"));
      lenient().when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");
      lenient().when(adapter2DeploymentService.getAdapterId()).thenReturn("adapter-test2");
      when(adapter1DeploymentService.getAdapterType()).thenReturn("dummy");
      when(adapter2DeploymentService.getAdapterType()).thenReturn("dummy");
      doThrow(new IllegalStateException("Both adapter ids address the same engine!"))
          .when(adapter1DeploymentService)
          .validateDistinctAdapterInstances(any());

      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService, adapter2DeploymentService), List.of());

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> testee.deployResources(List.of("test-module"), oneBpmnFile()));
      assertTrue(exception.getMessage().contains("address the same engine"));

      verify(adapter1DeploymentService, never())
          .readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean());
      verify(adapter2DeploymentService, never())
          .readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean());

    }

    private BiFunction<String, String, Map<String, InputStream>> oneBpmnFile() {

      return bpmnFilesOnly(location -> Map.of("process.bpmn", createDummyBpmnInputStream()));

    }

    /**
     * Properties whose GLOBAL prioritized adapters are the given ids, in that order -
     * which is the order the adapter is told about, no matter how the deployment
     * services happen to be sorted.
     */
    private MigrationAdapterProperties propertiesWithGlobalPriorities(
        final List<String> prioritizedAdapters) {

      final var adapters = new LinkedHashMap<String, AdapterConfigProperties>();
      final var adapterProperties = new LinkedHashMap<String, AdapterProperties>();
      for (final var adapterId : prioritizedAdapters) {
        adapters.put(adapterId, AdapterConfigProperties.ofType("dummy"));
        adapterProperties
            .put(adapterId, AdapterProperties
                .builder()
                .resourcesLocation("classpath:test-module/processes/"
                    + adapterId)
                .build());
      }

      final var properties = MigrationAdapterProperties
          .builder()
          .prioritizedAdapters(prioritizedAdapters)
          .adapters(adapters)
          .workflowModules(Map.of(
              "test-module",
              WorkflowModuleAdapterProperties
                  .builder()
                  .workflowModuleId("test-module")
                  .prioritizedAdapters(prioritizedAdapters)
                  .adapters(adapterProperties)
                  .build()))
          .build();
      properties.validateAndLink();
      return properties;

    }

  }

  @Nested
  @DisplayName("The BPMN processes an application declares without deploying them")
  class ProcessesNobodyDeployedTests {

    /**
     * A renamed BPMN process leaves its old id in the BPMS, and only the core knows that the
     * application still declares it. So the core asks each adapter of the module once the
     * module is deployed - and before the reverse wiring check, which needs the answer to
     * tell a method kept for the old id from one wired to nothing.
     */
    @Test
    @DisplayName("Every adapter of the workflow module is asked, before the module is judged")
    public void everyAdapterIsAskedBeforeTheChecks() {

      final var properties = createPropertiesWithAdapters("adapter-test1", "adapter-test2");
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");
      when(adapter2DeploymentService.getAdapterId()).thenReturn("adapter-test2");
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of());
      when(adapter2DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of());
      final var wiring = org.mockito.Mockito
          .mock(io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring.class);

      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService, adapter2DeploymentService), List.of(), wiring);
      testee
          .deployResources(
              List.of("test-module"),
              bpmnFilesOnly(location -> Map.of("process.bpmn", createDummyBpmnInputStream())));

      final var order = org.mockito.Mockito.inOrder(wiring);
      order
          .verify(wiring)
          .registerVersionsOfProcessesNobodyDeployed(
              org.mockito.ArgumentMatchers.eq("test-module"),
              org.mockito.ArgumentMatchers.eq("adapter-test1"),
              org.mockito.ArgumentMatchers.any());
      order
          .verify(wiring)
          .registerVersionsOfProcessesNobodyDeployed(
              org.mockito.ArgumentMatchers.eq("test-module"),
              org.mockito.ArgumentMatchers.eq("adapter-test2"),
              org.mockito.ArgumentMatchers.any());
      order.verify(wiring).validateNoUnwiredWorkflowTaskMethods("test-module");
      order.verify(wiring).resolveProcessVersions("test-module");

    }

    @Test
    @DisplayName("What the core is handed asks the adapter of that very id")
    public void theAnswerComesFromTheAdapterItself() {

      final var properties = createPropertiesWithAdapters("adapter-test1");
      when(adapter1DeploymentService.getAdapterId()).thenReturn("adapter-test1");
      when(adapter1DeploymentService.readBpmn(anyString(), anyString(), any(InputStream.class), anyBoolean()))
          .thenReturn(List.of());
      final var wiring = org.mockito.Mockito
          .mock(io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring.class);

      final var testee = new DeploymentService(
          properties, List.of(adapter1DeploymentService), List.of(), wiring);
      testee
          .deployResources(
              List.of("test-module"),
              bpmnFilesOnly(location -> Map.of("process.bpmn", createDummyBpmnInputStream())));

      @SuppressWarnings("unchecked")
      final var catalogOfProcess = org.mockito.ArgumentCaptor
          .forClass(java.util.function.BiFunction.class);
      verify(wiring)
          .registerVersionsOfProcessesNobodyDeployed(
              org.mockito.ArgumentMatchers.eq("test-module"),
              org.mockito.ArgumentMatchers.eq("adapter-test1"),
              catalogOfProcess.capture());
      catalogOfProcess.getValue().apply("test-module", "RenamedProcess");

      verify(adapter1DeploymentService).processVersionCatalogOf("test-module", "RenamedProcess");

    }

  }

  /**
   * A loader which hands out the given BPMN files and nothing else - the pipeline asks
   * separately for the DMN files of a location, and most of these tests bring none.
   *
   * @param bpmnFiles What to answer where the BPMN extension is asked for
   * @return The loader
   */
  private static BiFunction<String, String, Map<String, InputStream>> bpmnFilesOnly(
      final Function<String, Map<String, InputStream>> bpmnFiles) {

    return (
        location,
        extension) -> DeploymentService.BPMN_EXTENSION.equals(extension)
            ? bpmnFiles.apply(location)
            : Map.of();

  }

  private InputStream createDummyBpmnInputStream() {

    return new ByteArrayInputStream("<bpmn>dummy</bpmn>".getBytes(StandardCharsets.UTF_8));

  }

}
