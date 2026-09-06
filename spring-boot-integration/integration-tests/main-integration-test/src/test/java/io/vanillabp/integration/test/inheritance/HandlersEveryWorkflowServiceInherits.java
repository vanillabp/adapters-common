package io.vanillabp.integration.test.inheritance;

import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The declaration, abstract and carrying a handler of its own. No <code>bpmnProcess</code>
 * is named, so the process is named after the class serving it, which is the subclass.
 */
@WorkflowService(workflowAggregateClass = InheritedAggregate.class)
public abstract class HandlersEveryWorkflowServiceInherits {

  @WorkflowTask
  public void inheritedTask(
      final InheritedAggregate aggregate) {

    aggregate.setServedBy(aggregate.getServedBy()
        + "+inheritedTask");

  }

}
