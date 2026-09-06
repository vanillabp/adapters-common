package io.vanillabp.integration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.adapter.DummyAdapters;
import io.vanillabp.integration.test.samples.nosubclass.DeclarationWithoutASubclass;
import io.vanillabp.integration.test.samples.nosubclass.LonelyAggregate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An abstract class carrying <code>&#64;WorkflowService</code> declares a workflow service
 * without being one: VanillaBP asks CDI for an instance of the workflow service class, and
 * nobody can hand out an instance of an abstract class. Where no subclass exists either, the
 * declaration reaches no BPMN process at all, and the build says so instead of leaving the
 * developer with a bean question about a class they made abstract on purpose.
 */
@ExtendWith(SuppressOutputExtension.class)
public class DeclarationWithoutAWorkflowServiceTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addPackage(LonelyAggregate.class.getPackageName())
          .addClass(io.vanillabp.integration.test.adapter.DummyAdapters.class)
          .addClass(io.vanillabp.integration.test.adapter.TestPhaseTwoOutbox.class)
          .addClass(io.vanillabp.integration.test.adapter.TestAdapterDeploymentService.class)
          .addClass(io.vanillabp.integration.test.adapter.TestAdapterDeploymentServiceProducer.class)
          .addClass(io.vanillabp.integration.test.adapter.TestMigratableProcessService.class)
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE))
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())
      .assertException(throwable -> {
        var current = throwable;
        while (current != null) {
          if ((current.getMessage() != null) && current.getMessage().contains("which is abstract")) {
            final var message = current.getMessage();
            assertTrue(message.contains(DeclarationWithoutASubclass.class.getName()), message);
            assertTrue(message.contains("no class of this application extends it"), message);
            assertTrue(message.contains("@Inherited"), message);
            assertTrue(message.contains("jandex-maven-plugin"), message);
            return;
          }
          current = current.getCause();
        }
        fail("expected the build to report the declaration nobody serves but got: "
            + throwable);
      });

  @Test
  @DisplayName("An abstract declaration nobody extends ends the build naming it")
  public void aDeclarationNobodyServesEndsTheBuild() {
    // the assertion happens on the build exception (assertException above)
  }

}
