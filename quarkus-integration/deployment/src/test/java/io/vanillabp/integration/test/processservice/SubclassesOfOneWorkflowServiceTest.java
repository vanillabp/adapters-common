package io.vanillabp.integration.test.processservice;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.runtime.processservice.ProcessServiceBaseCdiBean;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.adapter.DummyAdapters;
import io.vanillabp.integration.test.samples.sharedprocess.HandlersOfTheFirstHalf;
import io.vanillabp.integration.test.samples.sharedprocess.HandlersOfTheSecondHalf;
import io.vanillabp.integration.test.samples.sharedprocess.SharedAggregate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import jakarta.inject.Inject;

/**
 * Two subclasses of one annotated base are two workflow services, which is the honest reading
 * of the annotation: each of them is a class an application can get handlers from. Where the
 * base names the BPMN process, both serve THAT process, which is the documented way of
 * splitting the handlers of one process across classes.
 * <p>
 * It used to be one workflow service - the base - and an ambiguous CDI lookup, thrown at the
 * first task delivery rather than while the application was built.
 */
@ExtendWith(SuppressOutputExtension.class)
public class SubclassesOfOneWorkflowServiceTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addPackage(SharedAggregate.class.getPackageName())
          .addClass(io.vanillabp.integration.test.adapter.DummyAdapters.class)
          .addClass(io.vanillabp.integration.test.adapter.TestPhaseTwoOutbox.class)
          .addClass(io.vanillabp.integration.test.adapter.TestAdapterDeploymentService.class)
          .addClass(io.vanillabp.integration.test.adapter.TestAdapterDeploymentServiceProducer.class)
          .addClass(io.vanillabp.integration.test.adapter.TestMigratableProcessService.class)
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE))
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter());

  @Inject
  ProcessService<SharedAggregate> processService;

  @Test
  @DisplayName("Both subclasses are registered, both for the process the base names")
  public void bothSubclassesServeTheProcessOfTheBase() {

    final var registrations = java.util.List.of(((ProcessServiceBaseCdiBean<?>) processService)
        .getWorkflowTaskRegistrations()
        .split(";"));

    Assertions.assertEquals(
        java.util.List.of(
            "test-module|%s|SharedProcess".formatted(HandlersOfTheFirstHalf.class.getName()),
            "test-module|%s|SharedProcess".formatted(HandlersOfTheSecondHalf.class.getName())),
        registrations);
    Assertions.assertEquals(
        "SharedProcess",
        ((ProcessServiceBaseCdiBean<?>) processService).getBpmnProcessId());

  }

}
