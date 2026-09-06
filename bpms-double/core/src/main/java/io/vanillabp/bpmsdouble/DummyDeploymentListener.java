package io.vanillabp.bpmsdouble;

/**
 * Test hook of the dummy adapter's {@link DummyDeploymentService}: beans
 * implementing this interface are notified about every deployment-pipeline call.
 * Integration tests use it to assert the pipeline order and to inject failures
 * (a listener throwing a {@link RuntimeException} makes the pipeline call fail,
 * e.g. to test the <code>deployment-failure</code> policy).
 */
public interface DummyDeploymentListener {

  /**
   * Notified for every deployment-pipeline call of the dummy adapter.
   *
   * @param adapterId The adapter id of the dummy adapter instance
   * @param method The pipeline method called (<code>readBpmn</code>,
   *        <code>prepareBpmn</code>, <code>wireBpmn</code>,
   *        <code>deployResources</code>, <code>startWorkflowProcessing</code>,
   *        <code>stopWorkflowProcessing</code>)
   * @param workflowModuleId The workflow module id
   * @param detail The filename (readBpmn/prepareBpmn) or BPMN process id (wireBpmn)
   *        or <code>null</code> (module-level calls)
   */
  void onPipelineCall(
      String adapterId,
      String method,
      String workflowModuleId,
      String detail);

}
