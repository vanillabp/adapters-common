package io.vanillabp.integration.test;

import java.time.OffsetDateTime;
import java.util.List;

import io.vanillabp.bpmsdouble.DummyViewerSource;
import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.WorkflowElementHistory;
import io.vanillabp.spi.process.WorkflowElementType;
import io.vanillabp.spi.process.WorkflowHistory;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The BPMS double's viewer data: exactly ONE adapter id serves
 * definitions, BPMN XML and a history - for exactly one aggregate id. Everything
 * else is unknown, so the tests can assert both the happy path and the guiding
 * errors.
 */
@ApplicationScoped
public class SteerableViewerSource implements DummyViewerSource {

  public static final String BPMN_XML = "<bpmn:definitions>viewed</bpmn:definitions>";

  public static final String NATIVE_DEFINITION_ID = "DummyProcess:1:aaa";

  private volatile String servingAdapterId;

  private volatile String knownAggregateId;

  public void serve(
      final String adapterId,
      final String workflowAggregateId) {

    this.servingAdapterId = adapterId;
    this.knownAggregateId = workflowAggregateId;

  }

  public void reset() {

    this.servingAdapterId = null;
    this.knownAggregateId = null;

  }

  private boolean serves(
      final String adapterId,
      final Object workflowAggregateId) {

    return (servingAdapterId != null) && servingAdapterId.equals(adapterId) && String.valueOf(knownAggregateId)
        .equals(String.valueOf(workflowAggregateId));

  }

  @Override
  public List<ProcessDefinition> getProcessDefinitions(
      final String adapterId,
      final Object workflowAggregateId,
      final String historyContext) {

    if (!serves(adapterId, workflowAggregateId)) {
      return List.of();
    }
    return List.of(
        new ProcessDefinition(NATIVE_DEFINITION_ID, "DummyProcess", "1", null),
        new ProcessDefinition("SubProcess:2:bbb", "SubProcess", "2", List.of("theCallActivity")));

  }

  @Override
  public String getBpmnXml(
      final String adapterId,
      final String processDefinitionId) {

    return (servingAdapterId != null) && servingAdapterId.equals(adapterId) && NATIVE_DEFINITION_ID
        .equals(processDefinitionId)
            ? BPMN_XML
            : null;

  }

  @Override
  public WorkflowHistory getWorkflowHistory(
      final String adapterId,
      final Object workflowAggregateId,
      final String historyContext) {

    if (!serves(adapterId, workflowAggregateId)) {
      return null;
    }
    return new WorkflowHistory(
        NATIVE_DEFINITION_ID, OffsetDateTime.parse("2026-08-06T10:00:00+02:00"), null, List.of(
            new WorkflowElementHistory(
                OffsetDateTime.parse("2026-08-06T10:00:00+02:00"), OffsetDateTime.parse(
                    "2026-08-06T10:00:01+02:00"), "theStartEvent", WorkflowElementType.START_EVENT, null, false, null),
            new WorkflowElementHistory(
                OffsetDateTime.parse(
                    "2026-08-06T10:00:01+02:00"), null, "theCallActivity", WorkflowElementType.CALL_ACTIVITY, null, false, "sub-instance-1")));

  }

}
