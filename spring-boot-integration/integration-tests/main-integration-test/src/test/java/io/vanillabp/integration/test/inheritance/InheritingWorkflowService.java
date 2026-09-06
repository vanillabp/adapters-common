package io.vanillabp.integration.test.inheritance;

import io.vanillabp.spi.service.WorkflowTask;

/**
 * The class which IS the workflow service: it serves the process named after itself, with
 * its own handler and the one it inherited.
 */
public class InheritingWorkflowService extends HandlersEveryWorkflowServiceInherits {

  @WorkflowTask
  public void ownTask(
      final InheritedAggregate aggregate) {

    aggregate.setServedBy(aggregate.getServedBy()
        + "+ownTask");

  }

}
