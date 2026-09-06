package io.vanillabp.integration.test;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * The bean of {@link WorkflowServiceInterface}: its own <code>processTask</code> carries no
 * annotation, because Java does not inherit a method annotation from an interface.
 */
@ApplicationScoped
public class WorkflowServiceImplementingAnInterface implements WorkflowServiceInterface {

  @Override
  public void processTask(
      final Aggregate aggregate) {

  }

}
