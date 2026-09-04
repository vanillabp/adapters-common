package io.vanillabp.integration.test.discovery;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The handlers of 'DummyProcess', brought by one profile only. Without that profile the
 * class is still on the classpath but no bean of it exists, which is the situation the
 * discovery cannot tell apart from a class another profile brings - and does not have
 * to: the deployment reports the BPMN process nothing serves, see
 * WorkflowServiceDiscoveryTest#aWorkflowServiceOfAnInactiveProfileLeavesItsProcessUnclaimed.
 */
@Service
@Profile(WorkflowServiceDiscoveryTest.PROFILE_WITH_HANDLERS)
@WorkflowService(
    workflowAggregateClass = ProfiledAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "DummyProcess"))
public class ProfiledWorkflowService {

  @WorkflowTask
  public void processTask(
      final ProfiledAggregate aggregate) {

  }

}
