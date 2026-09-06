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
import io.vanillabp.integration.test.samples.nobeansubclass.DeclarationOfANoBeanSubclass;
import io.vanillabp.integration.test.samples.nobeansubclass.NoBeanAggregate;
import io.vanillabp.integration.test.samples.nobeansubclass.SubclassWhichIsNoBean;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A class inheriting the declaration is a workflow service of its own, so VanillaBP asks CDI
 * for an instance of THAT class - and a bean of a subclass cannot stand in for it, because a
 * subclass is a different workflow service, serving a BPMN process of its own wherever the
 * process ID follows the class name. A class which never became a bean is therefore reported
 * while the application is built, and the message says what to do about it: annotate the
 * class, or make it abstract where it is only meant to carry the declaration.
 */
@ExtendWith(SuppressOutputExtension.class)
public class SubclassOfAWorkflowServiceIsNoBeanTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addPackage(NoBeanAggregate.class.getPackageName())
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
          if ((current.getMessage() != null) && current.getMessage().contains("the class itself is not a CDI bean")) {
            final var message = current.getMessage();
            assertTrue(message.contains(SubclassWhichIsNoBean.class.getName()), message);
            assertTrue(message.contains(DeclarationOfANoBeanSubclass.class.getName()), message);
            assertTrue(message.contains("@ApplicationScoped"), message);
            assertTrue(message.contains("make it abstract"), message);
            return;
          }
          current = current.getCause();
        }
        fail("expected the build to report the subclass which is no bean but got: "
            + throwable);
      });

  @Test
  @DisplayName("A subclass inheriting the declaration has to be a bean itself")
  public void aSubclassWhichIsNoBeanEndsTheBuild() {
    // the assertion happens on the build exception (assertException above)
  }

}
