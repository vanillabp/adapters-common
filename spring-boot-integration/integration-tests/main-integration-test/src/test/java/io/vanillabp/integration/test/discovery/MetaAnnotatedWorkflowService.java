package io.vanillabp.integration.test.discovery;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.WorkflowTask;

/**
 * A class declaring its workflow service through {@link OurOwnWorkflowService} instead of
 * through {@code @WorkflowService} itself.
 */
@Service
@OurOwnWorkflowService
public class MetaAnnotatedWorkflowService {

  @WorkflowTask(taskDefinition = "Activity_Process")
  public void processTask(
      final MetaAnnotatedAggregate aggregate) {

  }

}
