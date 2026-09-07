package io.vanillabp.integration.test.extension;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * A workflow service which knows nothing about the extension: no method of its
 * annotation, and no injection of its service. Both are legal - the extension asks
 * whether a method exists and does its own thing when none does.
 */
@Service
@WorkflowService(
    workflowAggregateClass = UnnotedAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "UnnotedProcess"))
public class UnnotedWorkflowService {

  private final ProcessService<UnnotedAggregate> processService;

  public UnnotedWorkflowService(
      final ProcessService<UnnotedAggregate> processService) {

    this.processService = processService;

  }

  public ProcessService<UnnotedAggregate> getProcessService() {

    return processService;

  }

}
