package io.vanillabp.integration.extension.spi.handler;

/**
 * The parameter kinds VanillaBP binds itself. A {@link HandlerContract} names the ones
 * its methods may ask for; a parameter of a kind the contract left out is refused at
 * startup naming what may stand there instead.
 * <p>
 * The task-processing parameters (<code>&#64;TaskId</code>,
 * <code>&#64;TaskEvent</code>) are deliberately absent: they belong to a BPMN task
 * VanillaBP completes, which a handler of an extension is not.
 */
public enum CoreHandlerParameter {

  /**
   * The workflow aggregate, taken by its type without an annotation - the way a
   * <code>&#64;WorkflowTask</code> method takes it.
   */
  WORKFLOW_AGGREGATE,

  /**
   * A process variable named by <code>&#64;TaskParam</code>, converted to the
   * parameter's type by the same conversion a <code>&#64;WorkflowTask</code> method
   * gets. The values come from {@link HandlerCall#getVariables()}.
   */
  TASK_PARAM,

  /**
   * The multi-instance parameters <code>&#64;MultiInstanceElement</code>,
   * <code>&#64;MultiInstanceIndex</code> and <code>&#64;MultiInstanceTotal</code>,
   * bound from {@link HandlerCall#getMultiInstances()}. Element resolvers
   * (<code>resolverBean</code>) are resolved as beans of the application, like they
   * are for a workflow task.
   */
  MULTI_INSTANCE

}
