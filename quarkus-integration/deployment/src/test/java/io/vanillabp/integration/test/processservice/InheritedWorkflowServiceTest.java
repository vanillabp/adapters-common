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
import io.vanillabp.integration.test.samples.inheritance.AnnotatedWorkflowServiceBase;
import io.vanillabp.integration.test.samples.inheritance.InheritedAggregate;
import io.vanillabp.integration.test.samples.inheritance.InheritingWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import jakarta.inject.Inject;

/**
 * <code>&#64;WorkflowService</code> is <code>&#64;Inherited</code>, and what that promises is
 * the same on both platforms: the SUBCLASS is the workflow service. Jandex resolves no
 * <code>&#64;Inherited</code> and reports the class the annotation SITS on, so the build used
 * to register the superclass here - and with it the superclass' simple name as the BPMN
 * process ID, while Spring Boot registers the class of the bean and arrives at the subclass'
 * name. One source file, two BPMN processes.
 * <p>
 * What the build records per workflow service is <code>module|class|BPMN process</code>, so
 * all three answers of this story can be read off one string.
 */
@ExtendWith(SuppressOutputExtension.class)
public class InheritedWorkflowServiceTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addPackage(InheritingWorkflowService.class.getPackageName())
          .addClass(io.vanillabp.integration.test.adapter.DummyAdapters.class)
          .addClass(io.vanillabp.integration.test.adapter.TestPhaseTwoOutbox.class)
          .addClass(io.vanillabp.integration.test.adapter.TestAdapterDeploymentService.class)
          .addClass(io.vanillabp.integration.test.adapter.TestAdapterDeploymentServiceProducer.class)
          .addClass(io.vanillabp.integration.test.adapter.TestMigratableProcessService.class)
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE))
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter());

  @Inject
  ProcessService<InheritedAggregate> processService;

  @Test
  @DisplayName("The subclass is the workflow service, and the process is named after it")
  public void theSubclassIsTheWorkflowService() {

    Assertions.assertEquals(
        "test-module|%s|%s".formatted(
            InheritingWorkflowService.class.getName(),
            InheritingWorkflowService.class.getSimpleName()),
        ((ProcessServiceBaseCdiBean<?>) processService).getWorkflowTaskRegistrations(),
        "the class inheriting the annotation is not the one registered");
    Assertions.assertEquals(
        InheritingWorkflowService.class.getSimpleName(),
        ((ProcessServiceBaseCdiBean<?>) processService).getBpmnProcessId(),
        "the BPMN process is not named after the subclass");
    Assertions.assertEquals(
        "test-module",
        processService.getWorkflowModuleId());

  }

  @Test
  @DisplayName("The class carrying the declaration serves nothing of its own")
  public void theAnnotatedBaseIsNoWorkflowServiceOfItsOwn() {

    // it is abstract, so CDI could not hand out an instance of it anyway - the point is
    // that its name does not reach the registrations
    Assertions.assertFalse(
        ((ProcessServiceBaseCdiBean<?>) processService)
            .getWorkflowTaskRegistrations()
            .contains(AnnotatedWorkflowServiceBase.class.getName()),
        "the class carrying the declaration was registered as a workflow service");

  }

}
