package io.vanillabp.integration.test;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * A class declaring its workflow service through {@link OurOwnWorkflowService} instead of
 * through <code>&#64;WorkflowService</code> itself.
 */
@ApplicationScoped
@OurOwnWorkflowService
public class MetaAnnotatedWorkflowService {

  @io.vanillabp.spi.service.WorkflowTask(taskDefinition = "processTask")
  public void processTask(
      final Aggregate aggregate) {

  }

}
