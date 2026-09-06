package io.vanillabp.integration.test.discovery;

import org.springframework.stereotype.Service;

/**
 * The bean of {@link WorkflowServiceInterface}: its own {@code processTask} carries no
 * annotation, because Java does not inherit a method annotation from an interface.
 */
@Service
public class WorkflowServiceImplementingAnInterface implements WorkflowServiceInterface {

  @Override
  public void processTask(
      final InterfaceAggregate aggregate) {

  }

}
