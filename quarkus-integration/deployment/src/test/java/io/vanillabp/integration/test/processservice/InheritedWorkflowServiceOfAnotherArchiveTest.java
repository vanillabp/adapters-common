package io.vanillabp.integration.test.processservice;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.adapter.DummyAdapters;
import io.vanillabp.integration.test.samples.inheritance.AnnotatedWorkflowServiceBase;
import io.vanillabp.integration.test.samples.inheritance.InheritedAggregate;
import io.vanillabp.integration.test.samples.inheritance.InheritedAggregatePersistence;
import io.vanillabp.integration.test.samples.inheritance.InheritingWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import jakarta.inject.Inject;

/**
 * A base class carrying the declaration lives in a library of its own, which is a workflow
 * module of its own too. The workflow module of a workflow service comes from the archive its
 * class sits in, and the class is the SUBCLASS - so the workflow service belongs to the
 * application's module, not to the library's. Reading it off the declaring class moved every
 * application using such a library into the library's module, or ended its build with "no
 * workflow module descriptor" where the library had none.
 */
@ExtendWith(SuppressOutputExtension.class)
public class InheritedWorkflowServiceOfAnotherArchiveTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("inheritance-library/application.yaml", "application.yaml")
          .addClass(InheritingWorkflowService.class)
          .addClass(InheritedAggregatePersistence.class)
          .addClass(io.vanillabp.integration.test.adapter.DummyAdapters.class)
          .addClass(io.vanillabp.integration.test.adapter.TestPhaseTwoOutbox.class)
          .addClass(io.vanillabp.integration.test.adapter.TestAdapterDeploymentService.class)
          .addClass(io.vanillabp.integration.test.adapter.TestAdapterDeploymentServiceProducer.class)
          .addClass(io.vanillabp.integration.test.adapter.TestMigratableProcessService.class)
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE))
      // the library carrying the declaration, a workflow module of its own
      .withAdditionalDependency(dependency -> dependency
          .addClass(AnnotatedWorkflowServiceBase.class)
          .addClass(InheritedAggregate.class)
          .addAsResource(new StringAsset("library-module"), WorkflowModule.METAINF_WORKFLOWMODULE))
      // the classes of the additional dependency are on the test classpath as well;
      // without a flat class path they would be loaded twice
      .setFlatClassPath(true)
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter());

  @Inject
  ProcessService<InheritedAggregate> processService;

  @Test
  @DisplayName("The workflow module comes from the archive of the subclass")
  public void theWorkflowModuleFollowsTheSubclass() {

    Assertions.assertEquals(
        "test-module",
        processService.getWorkflowModuleId(),
        "the workflow service moved into the workflow module of the library carrying its base class");

  }

}
