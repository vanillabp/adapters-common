package io.vanillabp.integration.test.extension;

import java.util.Collection;
import java.util.List;

import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stands in for what an adapter reads out of the BPMN model of this scenario: one user
 * task, carrying the <code>name</code> a modeller wrote on it. The name is the reason
 * this source exists - it is what an extension asks VanillaBP for instead of parsing the
 * same bytes a second time.
 * <p>
 * A user task needs no handler, which is what lets this scenario supply a task at all:
 * its workflow service has methods of the extension and no <code>&#64;WorkflowTask</code>.
 */
@ApplicationScoped
public class NoteTaskWiringSource implements DummyTaskWiringSource {

  /**
   * The element the name belongs to.
   */
  public static final String ACTIVITY_ID = "TheUserTask";

  /**
   * What the modeller wrote on it.
   */
  public static final String NAME = "Review the note";

  @Override
  public Collection<BpmnTaskSpec> tasksOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return "NoteProcess".equals(bpmnProcessId)
        ? List.of(BpmnTaskSpec.userTask(ACTIVITY_ID, "reviewTheNote", NAME))
        : List.of();

  }

}
