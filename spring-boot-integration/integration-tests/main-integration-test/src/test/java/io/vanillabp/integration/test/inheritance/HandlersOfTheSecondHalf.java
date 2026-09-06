package io.vanillabp.integration.test.inheritance;

import io.vanillabp.spi.service.WorkflowTask;

/**
 * The other half, see {@link HandlersOfTheFirstHalf}.
 */
public class HandlersOfTheSecondHalf extends SharedProcessBase {

  @WorkflowTask
  public void secondHalf(
      final SharedProcessAggregate aggregate) {

    aggregate.setServedBy(aggregate.getServedBy()
        + "+secondHalf");

  }

}
