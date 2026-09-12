package io.vanillabp.integration.extension.spi.handler;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One invocation of an extension's handler method: which method is meant, which
 * workflow it runs for and what its parameters are bound from.
 * <p>
 * The transaction is not part of it. VanillaBP takes part in the transaction the caller
 * of the extension is in and opens one only where none runs, which serves an extension
 * called from an application's own transaction as well as one called from a thread where
 * nothing is open.
 * <p>
 * The workflow aggregate is named in one of two ways. Usually the call carries its ID
 * and VanillaBP loads it, runs the method and saves it - the steps a workflow task goes
 * through. Where the aggregate does not exist yet, the caller builds one and hands it in
 * ({@link Builder#workflowAggregate(Object)}); it is then saved like a loaded one unless
 * the call says otherwise.
 *
 * @see ExtensionHandlers#invoke(HandlerCall)
 */
public final class HandlerCall {

  private final Class<? extends Annotation> annotationType;

  private final String workflowModuleId;

  private final String bpmnProcessId;

  private final List<String> lookupKeys;

  private final Object workflowAggregateId;

  private final Object workflowAggregate;

  private final boolean aggregateProvided;

  private final Map<String, Object> variables;

  private final Map<String, HandlerMultiInstance> multiInstances;

  private final Object payload;

  private final boolean savesWorkflowAggregate;

  private HandlerCall(
      final Builder builder) {

    this.annotationType = builder.annotationType;
    this.workflowModuleId = builder.workflowModuleId;
    this.bpmnProcessId = builder.bpmnProcessId;
    this.lookupKeys = List.copyOf(builder.lookupKeys);
    this.workflowAggregateId = builder.workflowAggregateId;
    this.workflowAggregate = builder.workflowAggregate;
    this.aggregateProvided = builder.aggregateProvided;
    this.variables = Map.copyOf(builder.variables);
    this.multiInstances = Map.copyOf(builder.multiInstances);
    this.payload = builder.payload;
    this.savesWorkflowAggregate = builder.savesWorkflowAggregate;

  }

  /**
   * Starts building a call of a method of the given contract.
   *
   * @param annotationType The annotation of the contract to invoke
   * @param workflowModuleId The workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process the workflow belongs to
   * @return The builder
   */
  public static Builder of(
      final Class<? extends Annotation> annotationType,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return new Builder(annotationType, workflowModuleId, bpmnProcessId);

  }

  /**
   * @return The annotation of the contract to invoke
   */
  public Class<? extends Annotation> getAnnotationType() {

    return annotationType;

  }

  /**
   * @return The workflow module of the workflow
   */
  public String getWorkflowModuleId() {

    return workflowModuleId;

  }

  /**
   * @return The BPMN process of the workflow
   */
  public String getBpmnProcessId() {

    return bpmnProcessId;

  }

  /**
   * @return The keys a method may be matched by - a method serving ANY of them runs
   */
  public List<String> getLookupKeys() {

    return lookupKeys;

  }

  /**
   * @return The ID of the workflow aggregate to load, or <code>null</code> where the
   *         caller handed one in
   */
  public Object getWorkflowAggregateId() {

    return workflowAggregateId;

  }

  /**
   * @return The workflow aggregate the caller built, or <code>null</code> where the
   *         call names its ID instead
   */
  public Object getWorkflowAggregate() {

    return workflowAggregate;

  }

  /**
   * @return Whether the caller handed in the aggregate rather than its ID
   */
  public boolean isWorkflowAggregateProvided() {

    return aggregateProvided;

  }

  /**
   * @return The process variables <code>&#64;TaskParam</code> parameters read
   */
  public Map<String, Object> getVariables() {

    return variables;

  }

  /**
   * @return The multi-instance scopes of this invocation, keyed by BPMN element id
   */
  public Map<String, HandlerMultiInstance> getMultiInstances() {

    return multiInstances;

  }

  /**
   * @return The extension's own event object the binders of the extension read
   */
  public Object getPayload() {

    return payload;

  }

  /**
   * @return Whether the aggregate is saved after the method returned
   */
  public boolean savesWorkflowAggregate() {

    return savesWorkflowAggregate;

  }

  /**
   * Builds a {@link HandlerCall}.
   */
  public static final class Builder {

    private final Class<? extends Annotation> annotationType;

    private final String workflowModuleId;

    private final String bpmnProcessId;

    private List<String> lookupKeys = List.of();

    private Object workflowAggregateId;

    private Object workflowAggregate;

    private boolean aggregateProvided = false;

    private final Map<String, Object> variables = new LinkedHashMap<>();

    private final Map<String, HandlerMultiInstance> multiInstances = new LinkedHashMap<>();

    private Object payload;

    private boolean savesWorkflowAggregate = true;

    private Builder(
        final Class<? extends Annotation> annotationType,
        final String workflowModuleId,
        final String bpmnProcessId) {

      this.annotationType = annotationType;
      this.workflowModuleId = workflowModuleId;
      this.bpmnProcessId = bpmnProcessId;

    }

    /**
     * The keys the method may be matched by - typically the task definition and the
     * BPMN element id of the element the event belongs to. A method serving any of them
     * runs; a method serving {@link HandlerContract#EVERY_KEY} always does.
     *
     * @param lookupKeys The keys, <code>null</code> entries are ignored
     * @return This builder
     */
    public Builder lookupKeys(
        final Collection<String> lookupKeys) {

      this.lookupKeys = lookupKeys == null
          ? List.of()
          : lookupKeys
              .stream()
              .filter(java.util.Objects::nonNull)
              .toList();
      return this;

    }

    /**
     * The workflow aggregate VanillaBP loads for this invocation.
     *
     * @param workflowAggregateId Its ID, in the aggregate's own ID type or serialized
     * @return This builder
     */
    public Builder workflowAggregateId(
        final Object workflowAggregateId) {

      this.workflowAggregateId = workflowAggregateId;
      return this;

    }

    /**
     * The workflow aggregate the caller built - for an event about a workflow whose
     * aggregate does not exist yet.
     *
     * @param workflowAggregate The aggregate to hand to the method
     * @return This builder
     */
    public Builder workflowAggregate(
        final Object workflowAggregate) {

      this.workflowAggregate = workflowAggregate;
      this.aggregateProvided = true;
      return this;

    }

    /**
     * @param name The name of a process variable
     * @param value Its value
     * @return This builder
     */
    public Builder variable(
        final String name,
        final Object value) {

      variables.put(name, value);
      return this;

    }

    /**
     * @param variables The process variables of this invocation
     * @return This builder
     */
    public Builder variables(
        final Map<String, Object> variables) {

      if (variables != null) {
        this.variables.putAll(variables);
      }
      return this;

    }

    /**
     * @param elementId The BPMN element carrying the multi-instance characteristics
     * @param multiInstance Its current iteration
     * @return This builder
     */
    public Builder multiInstance(
        final String elementId,
        final HandlerMultiInstance multiInstance) {

      multiInstances.put(elementId, multiInstance);
      return this;

    }

    /**
     * @param payload The extension's own event object, read by the binders it
     *          contributed
     * @return This builder
     */
    public Builder payload(
        final Object payload) {

      this.payload = payload;
      return this;

    }

    /**
     * Runs the method WITHOUT saving the aggregate afterwards - for an event which only
     * reads (the Business Cockpit building the details of a user task somebody opened).
     * <p>
     * What it switches off is the save VanillaBP performs. A persistence layer which
     * writes what changed on a managed object by itself - JPA's dirty checking - still
     * writes it when the transaction commits, so a handler meant to change nothing has to
     * change nothing.
     *
     * @return This builder
     */
    public Builder withoutSavingTheWorkflowAggregate() {

      this.savesWorkflowAggregate = false;
      return this;

    }

    /**
     * @return The call
     */
    public HandlerCall build() {

      if (!aggregateProvided && (workflowAggregateId == null)) {
        throw new IllegalArgumentException(
            """
                The handler call for BPMN process '%s' of workflow module '%s' names neither the ID of \
                a workflow aggregate to load nor an aggregate to hand in! Call workflowAggregateId(...) \
                for a workflow which exists, workflowAggregate(...) for one whose aggregate you built \
                yourself."""
                .formatted(bpmnProcessId, workflowModuleId));
      }
      return new HandlerCall(this);

    }

  }

}
