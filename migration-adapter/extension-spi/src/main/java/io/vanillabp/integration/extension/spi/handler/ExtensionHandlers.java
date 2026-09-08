package io.vanillabp.integration.extension.spi.handler;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The mechanics behind <code>&#64;WorkflowTask</code>, opened for the annotations an
 * extension brings: scan the <code>&#64;WorkflowService</code> classes of the
 * application, match a method by its name or by what its annotation names, bind its
 * parameters, load the workflow aggregate, invoke and save - all in one transaction.
 * <p>
 * An extension registers its {@link HandlerContract}s once, and calls
 * {@link #invoke(HandlerCall)} whenever its own event happens. Both platforms provide
 * this as a bean.
 * <p>
 * <b>Registration may happen at any time.</b> The workflow services are scanned while
 * the process-service beans are created, which on both platforms is before or after an
 * extension's own bean exists depending on what else the application does. A contract
 * registered late is therefore applied to the classes registered so far as well, so an
 * extension never has to reason about the order its bean is created in.
 * <p>
 * <b>Exceptions of a handler method propagate unchanged</b> - the extension decides what
 * a failure means for its own event, since only it knows whether the BPMS repeats the
 * notification.
 */
public interface ExtensionHandlers {

  /**
   * Registers what an extension's annotation means. Registering a second contract for
   * the same annotation ends the boot: the second one would silently replace the first.
   *
   * @param contract The contract
   */
  void register(
      HandlerContract contract);

  /**
   * Whether the application has a method for that key - what an extension asks before
   * building an event nobody would receive, and what tells it to fall back to its own
   * default (the Business Cockpit passing prefilled details through unchanged).
   *
   * @param annotationType The annotation of a registered contract
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process
   * @param lookupKeys The keys a method may be matched by
   * @return Whether a method serves any of those keys
   */
  boolean hasHandler(
      Class<? extends Annotation> annotationType,
      String workflowModuleId,
      String bpmnProcessId,
      Collection<String> lookupKeys);

  /**
   * Runs the method the call addresses: load the workflow aggregate (or take the one
   * handed in), bind the parameters, invoke, save - in one transaction of the platform.
   *
   * @param call What to invoke and what to bind it from
   * @return What the method returned, empty where no method serves the call's keys or
   *         where the contract does not deliver return values
   * @throws IllegalStateException If no contract is registered for the call's
   *           annotation
   */
  Optional<Object> invoke(
      HandlerCall call);

  /**
   * The workflow aggregate a BPMN process of a workflow module works on - what an
   * extension needs before it can ask for anything else about that workflow, since the
   * aggregate is the type its own per-aggregate service is parameterized with and the
   * type it hands to a resolver of the platform.
   * <p>
   * The answer is the class the <code>&#64;WorkflowService</code> declaring that process
   * named, which is what the scan already knows; an extension re-deriving it would read
   * the same annotations again, through the bean proxies of a platform it should not
   * have to know about.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process, primary or secondary
   * @return The workflow-aggregate class, or empty where no
   *         <code>&#64;WorkflowService</code> of this application declares that process
   */
  Optional<Class<?>> workflowAggregateOf(
      String workflowModuleId,
      String bpmnProcessId);

  /**
   * Every BPMN process of a workflow module a <code>&#64;WorkflowService</code> class
   * declares - the primary ones and the secondary ones alike, in the order they were
   * registered, each named once. An extension which has something to publish per
   * workflow (a set of user-task templates, a registration with a server of its own)
   * asks this instead of scanning the beans of the application.
   * <p>
   * A BPMN process the application deploys without claiming it in a
   * <code>&#64;WorkflowService</code> is not among them: nothing declares which
   * aggregate it works on, so there is nothing an extension could do with it.
   *
   * @param workflowModuleId The workflow module
   * @return The BPMN process ids, empty where the module has no workflow service
   */
  List<String> bpmnProcessesOf(
      String workflowModuleId);

}
