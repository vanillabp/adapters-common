package io.vanillabp.integration.test.discovery;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The support case this fixture stands for: somebody wrote a workflow service and forgot the
 * bean-defining annotation on it. Deliberately without {@code @Service} and never handed to a
 * {@code SpringApplicationBuilder}, so no bean of it exists in any application here - which
 * is why the discovery cannot see it and only the report about the process nothing claims
 * names it.
 */
@WorkflowService(
    workflowAggregateClass = ForgottenAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "DummyProcess"))
public class ForgottenWorkflowService {

  @WorkflowTask
  public void processTask(
      final ForgottenAggregate aggregate) {

  }

}
