package io.vanillabp.integration.test.inheritance;

import io.vanillabp.spi.service.WorkflowTask;

/**
 * One of two classes splitting the handlers of one BPMN process, both of them workflow
 * services by inheritance.
 */
public class HandlersOfTheFirstHalf extends SharedProcessBase {

  @WorkflowTask
  public void firstHalf(
      final SharedProcessAggregate aggregate) {

    aggregate.setServedBy(aggregate.getServedBy()
        + "+firstHalf");

  }

}
