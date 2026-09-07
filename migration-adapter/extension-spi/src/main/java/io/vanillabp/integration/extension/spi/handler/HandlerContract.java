package io.vanillabp.integration.extension.spi.handler;

import java.lang.annotation.Annotation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * What an extension tells VanillaBP about an annotation of its own, so that the
 * mechanics behind <code>&#64;WorkflowTask</code> serve it as well: which methods of a
 * <code>&#64;WorkflowService</code> class belong to the extension, what they are
 * matched by, what may stand in their parameter list and whether what they return is
 * handed back to the caller.
 * <p>
 * A contract is registered once at startup ({@link ExtensionHandlers#register}) and the
 * methods it describes are then invoked through
 * {@link ExtensionHandlers#invoke(HandlerCall)}: VanillaBP loads the workflow
 * aggregate, binds the parameters, calls the method and saves the aggregate, all in one
 * transaction - the same steps a workflow task goes through.
 * <p>
 * <b>Matching.</b> Every method carrying the annotation serves the lookup keys
 * {@link Builder#lookupKeys(Function)} reads from it. A method whose annotation names
 * none serves its own METHOD NAME, which is the convention
 * <code>&#64;WorkflowTask</code> follows too; {@link #EVERY_KEY} makes it serve every
 * key of its BPMN process. An invocation hands in the keys it accepts (a task definition
 * and an element id, say), and the method NAMING one of them runs; where none does, the
 * method serving every key runs. So a catch-all may stand next to methods for single
 * elements, and the specific one wins - the rule
 * <code>&#64;WorkflowStartedByBpms</code> follows for its start events too.
 * <p>
 * <b>Zero matches are legal.</b> Nothing is registered for a BPMN process whose
 * workflow service carries no such method, and an invocation for it answers
 * {@link ExtensionHandlers#hasHandler} with <code>false</code> respectively returns an
 * empty result - what an extension does instead is its own business (the Business
 * Cockpit passes its prefilled details through unchanged). Two methods naming the same
 * key of one BPMN process, on the other hand, end the boot, and so do two catch-alls:
 * which of them was meant cannot be guessed.
 */
public final class HandlerContract {

  /**
   * A lookup key standing for "every key of this BPMN process" - the default of the
   * Business Cockpit's <code>&#64;WorkflowDetailsProvider</code>, which exists once per
   * process, and of a <code>&#64;WorkflowStartedByBpms</code> method naming no start
   * event.
   */
  public static final String EVERY_KEY = "*";

  private final String extensionId;

  private final Class<? extends Annotation> annotationType;

  private final Function<Annotation, List<String>> lookupKeys;

  private final Set<CoreHandlerParameter> coreParameters;

  private final List<HandlerParameterBinder> parameterBinders;

  private final boolean deliversReturnValue;

  private HandlerContract(
      final Builder builder) {

    this.extensionId = builder.extensionId;
    this.annotationType = builder.annotationType;
    this.lookupKeys = builder.lookupKeys;
    this.coreParameters = Set.copyOf(builder.coreParameters);
    this.parameterBinders = List.copyOf(builder.parameterBinders);
    this.deliversReturnValue = builder.deliversReturnValue;

  }

  /**
   * Starts building a contract.
   *
   * @param extensionId The extension's id, e.g. <code>business-cockpit</code> - it
   *          names the extension in every message about one of its methods
   * @param annotationType The annotation the extension's methods carry; repeatable
   *          annotations are supported, a method then serves every key of every
   *          repetition
   * @return The builder
   */
  public static Builder of(
      final String extensionId,
      final Class<? extends Annotation> annotationType) {

    return new Builder(extensionId, annotationType);

  }

  /**
   * @return The id of the extension owning this contract
   */
  public String getExtensionId() {

    return extensionId;

  }

  /**
   * @return The annotation the extension's handler methods carry
   */
  public Class<? extends Annotation> getAnnotationType() {

    return annotationType;

  }

  /**
   * @return Reads the keys one occurrence of the annotation names, or an empty list
   *         for "the method's name"
   */
  public Function<Annotation, List<String>> getLookupKeys() {

    return lookupKeys;

  }

  /**
   * @return The parameter kinds VanillaBP binds for these methods
   */
  public Set<CoreHandlerParameter> getCoreParameters() {

    return coreParameters;

  }

  /**
   * @return The binders the extension contributes, asked in the order they were added
   */
  public List<HandlerParameterBinder> getParameterBinders() {

    return parameterBinders;

  }

  /**
   * @return Whether what a method returns is handed back to the caller
   */
  public boolean deliversReturnValue() {

    return deliversReturnValue;

  }

  /**
   * Builds a {@link HandlerContract}.
   */
  public static final class Builder {

    private final String extensionId;

    private final Class<? extends Annotation> annotationType;

    private Function<Annotation, List<String>> lookupKeys = annotation -> List.of();

    private final Set<CoreHandlerParameter> coreParameters = new LinkedHashSet<>();

    private final List<HandlerParameterBinder> parameterBinders = new java.util.LinkedList<>();

    private boolean deliversReturnValue = false;

    private Builder(
        final String extensionId,
        final Class<? extends Annotation> annotationType) {

      this.extensionId = extensionId;
      this.annotationType = annotationType;

    }

    /**
     * How the keys a method serves are read from one occurrence of the annotation -
     * typically the annotation's <code>id</code> or <code>taskDefinition</code>
     * attribute. Returning an EMPTY list means "the method's name", the convention
     * VanillaBP's own annotations follow; returning {@link HandlerContract#EVERY_KEY}
     * makes the method serve every key of its BPMN process.
     * <p>
     * Without this the methods are matched by their name only.
     *
     * @param lookupKeys Reads the keys of one occurrence of the annotation
     * @return This builder
     */
    public Builder lookupKeys(
        final Function<Annotation, List<String>> lookupKeys) {

      this.lookupKeys = lookupKeys;
      return this;

    }

    /**
     * Allows a parameter kind VanillaBP binds itself. Everything not allowed is
     * refused at startup - an extension whose event has no process variables around it
     * says so by leaving {@link CoreHandlerParameter#TASK_PARAM} out, and the developer
     * reads that instead of receiving <code>null</code>.
     *
     * @param parameters The kinds to allow
     * @return This builder
     */
    public Builder coreParameters(
        final CoreHandlerParameter... parameters) {

      java.util.Collections.addAll(coreParameters, parameters);
      return this;

    }

    /**
     * Adds a binder for the parameters of the extension's own SPI. Binders are asked
     * in the order they were added, before VanillaBP tries its own.
     *
     * @param binder The binder
     * @return This builder
     */
    public Builder parameterBinder(
        final HandlerParameterBinder binder) {

      parameterBinders.add(binder);
      return this;

    }

    /**
     * Declares that what a method returns is handed back to the caller of
     * {@link ExtensionHandlers#invoke(HandlerCall)}. Without this a method has to be
     * <code>void</code>, which is what <code>&#64;WorkflowTask</code> methods are.
     *
     * @return This builder
     */
    public Builder deliversReturnValue() {

      this.deliversReturnValue = true;
      return this;

    }

    /**
     * @return The contract
     */
    public HandlerContract build() {

      if ((extensionId == null) || extensionId.isBlank()) {
        throw new IllegalArgumentException(
            "A handler contract needs the id of the extension owning it - it names the extension in "
                + "every message about one of its methods!");
      }
      if (annotationType == null) {
        throw new IllegalArgumentException(
            """
                The handler contract of extension '%s' names no annotation! Pass the annotation your \
                extension's methods carry to HandlerContract.of(extensionId, annotationType)."""
                .formatted(extensionId));
      }
      return new HandlerContract(this);

    }

  }

}
