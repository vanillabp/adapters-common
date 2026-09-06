package io.vanillabp.integration.test.inheritance;

import io.vanillabp.spi.service.WorkflowTask;

/**
 * Declares a handler which the subclass overrides. Java never inherits a method
 * annotation, so the override takes the handler with it.
 */
public abstract class OverriddenHandlerBase {

  @WorkflowTask
  public void overriddenTask(
      final InvisibleHandlersAggregate aggregate) {

    aggregate.setServedBy("the base");

  }

}
