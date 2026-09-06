package io.vanillabp.integration.test.deployment;

import java.util.Collection;
import java.util.List;

import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A workflow service with one method serving the modelled task and one whose task
 * definition nobody modelled - a typo, or a method left behind after a model change.
 * Used by {@code OrphanWorkflowTaskMethodTest}: the core reports such a method once the
 * module finished deploying, and the application does not start.
 */
@ApplicationScoped
@io.vanillabp.spi.service.WorkflowService(
    workflowAggregateClass = Aggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "orphan"))
public class OrphanMethodWorkflowService {

  @WorkflowTask
  public void juhu() {

  }

  @WorkflowTask(taskDefinition = "activityNobodyModelled")
  public void typo() {

  }

  /**
   * What the model of this test really holds: the one task {@code juhu} serves. Without
   * a wiring source the dummy adapter reports no tasks at all, and a module nothing was
   * wired in is deliberately not reported - a model may arrive later during a migration.
   */
  @ApplicationScoped
  public static class OrphanProcessWiringSource implements DummyTaskWiringSource {

    @Override
    public Collection<BpmnTaskSpec> tasksOf(
        final String adapterId,
        final String workflowModuleId,
        final String bpmnProcessId) {

      return "orphan".equals(bpmnProcessId)
          ? List.of(new BpmnTaskSpec("Activity_Juhu", "juhu"))
          : List.of();

    }

  }

}
