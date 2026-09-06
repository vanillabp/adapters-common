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
import io.vanillabp.integration.test.samples.annotatedbeanbase.AnnotatedBaseWhichIsABean;
import io.vanillabp.integration.test.samples.annotatedbeanbase.BeanBaseAggregate;
import io.vanillabp.integration.test.samples.annotatedbeanbase.SubclassOfABeanBase;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import jakarta.inject.Inject;

/**
 * A class carrying the declaration may be a bean itself, next to the subclass inheriting it.
 * Both are then workflow services: both are classes the application can get handlers from,
 * and each serves the BPMN process its own annotation attributes name.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AnnotatedBaseWhichIsABeanTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addPackage(AnnotatedBaseWhichIsABean.class.getPackageName())
          .addClass(io.vanillabp.integration.test.adapter.DummyAdapters.class)
          .addClass(io.vanillabp.integration.test.adapter.TestPhaseTwoOutbox.class)
          .addClass(io.vanillabp.integration.test.adapter.TestAdapterDeploymentService.class)
          .addClass(io.vanillabp.integration.test.adapter.TestAdapterDeploymentServiceProducer.class)
          .addClass(io.vanillabp.integration.test.adapter.TestMigratableProcessService.class)
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE))
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter());

  @Inject
  ProcessService<BeanBaseAggregate> processService;

  @Test
  @DisplayName("Base and subclass are both workflow services of the process the base names")
  public void bothClassesAreWorkflowServices() {

    Assertions.assertEquals(
        java.util.List.of(
            "test-module|%s|BaseProcess".formatted(AnnotatedBaseWhichIsABean.class.getName()),
            "test-module|%s|BaseProcess".formatted(SubclassOfABeanBase.class.getName())),
        java.util.List.of(((ProcessServiceBaseCdiBean<?>) processService)
            .getWorkflowTaskRegistrations()
            .split(";")));

  }

}
