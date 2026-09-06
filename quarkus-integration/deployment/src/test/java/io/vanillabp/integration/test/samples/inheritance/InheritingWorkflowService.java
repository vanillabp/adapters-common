package io.vanillabp.integration.test.samples.inheritance;

import io.vanillabp.spi.service.WorkflowTask;
import jakarta.inject.Singleton;

/**
 * A workflow service by inheritance, and the class the process is named after.
 */
@Singleton
public class InheritingWorkflowService extends AnnotatedWorkflowServiceBase {

  @WorkflowTask
  public void subclassTask() {
  }

}
