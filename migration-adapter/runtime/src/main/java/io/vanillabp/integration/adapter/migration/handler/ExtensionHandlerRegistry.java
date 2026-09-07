package io.vanillabp.integration.adapter.migration.handler;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.transaction.AggregateWrite;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;
import io.vanillabp.integration.extension.spi.handler.HandlerCall;
import io.vanillabp.integration.extension.spi.handler.HandlerContract;
import io.vanillabp.integration.spi.TransactionRunner;

/**
 * The handler methods of every registered extension contract, kept next to the
 * <code>&#64;WorkflowTask</code> methods of the same workflow service classes and
 * scanned from the same registration call.
 * <p>
 * <b>Why the workflow services are remembered.</b> A contract may be registered before
 * or after the workflow services are scanned - on both platforms that depends on when
 * the extension's own bean is created, which the extension cannot influence and should
 * not have to reason about. So every registration of a workflow service is kept, a
 * contract arriving later is applied to all of them, and a workflow service arriving
 * later is scanned for every contract known by then. Whichever order the two happen in,
 * the result is the same.
 */
public class ExtensionHandlerRegistry implements ExtensionHandlers {

  private record RegistryKey(
                             String workflowModuleId,
                             String bpmnProcessId,
                             Class<? extends Annotation> annotationType) {
  }

  /**
   * A workflow service class registered for one BPMN process - everything a contract
   * needs to scan it, whenever it arrives.
   */
  private record RegisteredWorkflowService(
                                           String workflowModuleId,
                                           String bpmnProcessId,
                                           Class<?> workflowServiceClass,
                                           Class<?> workflowAggregateClass,
                                           Supplier<Object> workflowServiceBean,
                                           Function<Class<?>, Object> beanResolver,
                                           MigrationProcessService<?> processService) {
  }

  private final TransactionRunner transactionRunner;

  private final Map<Class<? extends Annotation>, HandlerContract> contracts = new ConcurrentHashMap<>();

  private final List<RegisteredWorkflowService> workflowServices = new LinkedList<>();

  private final Map<RegistryKey, List<ExtensionHandlerMethod>> methods = new ConcurrentHashMap<>();

  private final Map<RegistryKey, MigrationProcessService<?>> processServices = new ConcurrentHashMap<>();

  /**
   * @param transactionRunner The platform's transaction runner, which wraps every
   *          invocation the way it wraps a workflow task
   */
  public ExtensionHandlerRegistry(
      final TransactionRunner transactionRunner) {

    this.transactionRunner = transactionRunner;

  }

  @Override
  public void register(
      final HandlerContract contract) {

    final var previous = contracts.putIfAbsent(contract.getAnnotationType(), contract);
    if (previous != null) {
      throw new IllegalStateException(
          """
              The annotation '@%s' is claimed by extension '%s' and by extension '%s'! An annotation \
              belongs to exactly one extension - the second registration would silently replace the \
              first, so the boot ends here instead."""
              .formatted(
                  contract.getAnnotationType().getSimpleName(),
                  previous.getExtensionId(),
                  contract.getExtensionId()));
    }
    synchronized (workflowServices) {
      workflowServices.forEach(service -> scan(contract, service));
    }

  }

  /**
   * Registers a workflow service class for a BPMN process - called from the same place
   * the <code>&#64;WorkflowTask</code> methods of that class are registered.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowServiceClass The <code>&#64;WorkflowService</code> class
   * @param workflowServiceBean Supplies the bean instance of that class
   * @param beanResolver Resolves beans by class
   * @param processService The process service of the BPMN process
   */
  public void registerWorkflowService(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<?> workflowServiceClass,
      final Supplier<Object> workflowServiceBean,
      final Function<Class<?>, Object> beanResolver,
      final MigrationProcessService<?> processService) {

    final var service = new RegisteredWorkflowService(
        workflowModuleId, bpmnProcessId, workflowServiceClass, processService
            .getWorkflowAggregateClass(), workflowServiceBean, beanResolver, processService);
    synchronized (workflowServices) {
      workflowServices.add(service);
      contracts
          .values()
          .forEach(contract -> scan(contract, service));
    }

  }

  private void scan(
      final HandlerContract contract,
      final RegisteredWorkflowService service) {

    final var found = ExtensionHandlerScanner
        .scan(
            contract,
            service.workflowServiceClass(),
            service.workflowAggregateClass(),
            service.workflowServiceBean(),
            service.beanResolver());
    final var key = new RegistryKey(service.workflowModuleId(), service.bpmnProcessId(), contract.getAnnotationType());
    processServices.putIfAbsent(key, service.processService());
    if (found.isEmpty()) {
      return;
    }
    final var registered = methods.computeIfAbsent(key, ignored -> new LinkedList<>());
    synchronized (registered) {
      found
          .forEach(method -> {
            failOnDuplicateWiring(contract, service, registered, method);
            registered.add(method);
          });
    }

  }

  private static void failOnDuplicateWiring(
      final HandlerContract contract,
      final RegisteredWorkflowService service,
      final List<ExtensionHandlerMethod> registered,
      final ExtensionHandlerMethod method) {

    registered
        .stream()
        .filter(existing -> existing.overlaps(method))
        .findFirst()
        .ifPresent(existing -> {
          throw new IllegalStateException(
              """
                  The @%s methods '%s' (%s) and '%s' (%s) of extension '%s' both serve BPMN process \
                  '%s' of workflow module '%s'! Which of them is meant cannot be guessed - remove one \
                  of them or name what each of them serves."""
                  .formatted(
                      contract.getAnnotationType().getSimpleName(),
                      existing.describe(),
                      existing.describeWiring(),
                      method.describe(),
                      method.describeWiring(),
                      contract.getExtensionId(),
                      service.bpmnProcessId(),
                      service.workflowModuleId()));
        });

  }

  @Override
  public boolean hasHandler(
      final Class<? extends Annotation> annotationType,
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<String> lookupKeys) {

    requireContract(annotationType);
    return find(annotationType, workflowModuleId, bpmnProcessId, lookupKeys) != null;

  }

  @Override
  public Optional<Object> invoke(
      final HandlerCall call) {

    final var contract = requireContract(call.getAnnotationType());
    final var method = find(
        call.getAnnotationType(),
        call.getWorkflowModuleId(),
        call.getBpmnProcessId(),
        call.getLookupKeys());
    if (method == null) {
      return Optional.empty();
    }

    final var processService = processServices
        .get(
            new RegistryKey(call.getWorkflowModuleId(), call.getBpmnProcessId(), call.getAnnotationType()));
    final var returned = AggregateWrite
        .inTransaction(
            transactionRunner,
            false,
            call.getWorkflowModuleId(),
            call.getBpmnProcessId(),
            call.getWorkflowAggregateId(),
            "the @%s notification of extension '%s'"
                .formatted(contract.getAnnotationType().getSimpleName(), contract.getExtensionId()),
            () -> run(contract, method, call, processService));

    return contract.deliversReturnValue()
        ? Optional.ofNullable(returned)
        : Optional.empty();

  }

  private Object run(
      final HandlerContract contract,
      final ExtensionHandlerMethod method,
      final HandlerCall call,
      final MigrationProcessService<?> processService) {

    final var workflowAggregate = call.isWorkflowAggregateProvided()
        ? call.getWorkflowAggregate()
        : loadWorkflowAggregate(contract, call, processService);

    final var context = HandlerContexts
        .of(
            workflowAggregate,
            call.getPayload(),
            name -> call.getVariables().get(name),
            call::getMultiInstances);
    final var returned = method.invoke(context);

    if (call.savesWorkflowAggregate() && (workflowAggregate != null)) {
      saveWorkflowAggregate(processService, workflowAggregate);
    }
    return returned;

  }

  private static Object loadWorkflowAggregate(
      final HandlerContract contract,
      final HandlerCall call,
      final MigrationProcessService<?> processService) {

    if (processService == null) {
      throw new IllegalStateException(
          """
              Extension '%s' asks for the workflow aggregate '%s' of BPMN process '%s' of workflow \
              module '%s', but no @WorkflowService of this application declares that process! Check \
              the workflow module and the BPMN process id the extension passes."""
              .formatted(
                  contract.getExtensionId(),
                  call.getWorkflowAggregateId(),
                  call.getBpmnProcessId(),
                  call.getWorkflowModuleId()));
    }
    final var workflowAggregate = processService
        .loadWorkflowAggregateById(processService.convertAggregateId(String.valueOf(call.getWorkflowAggregateId())));
    if (workflowAggregate == null) {
      throw new IllegalStateException(
          """
              No workflow aggregate '%s' of BPMN process '%s' of workflow module '%s' exists, but \
              extension '%s' has a notification about it! Either the workflow's aggregate was deleted \
              or the extension names an id which was never one."""
              .formatted(
                  call.getWorkflowAggregateId(),
                  call.getBpmnProcessId(),
                  call.getWorkflowModuleId(),
                  contract.getExtensionId()));
    }
    return workflowAggregate;

  }

  @SuppressWarnings("unchecked")
  private static <A> void saveWorkflowAggregate(
      final MigrationProcessService<A> processService,
      final Object workflowAggregate) {

    processService.saveWorkflowAggregate((A) workflowAggregate);

  }

  private HandlerContract requireContract(
      final Class<? extends Annotation> annotationType) {

    final var contract = contracts.get(annotationType);
    if (contract == null) {
      throw new IllegalStateException(
          """
              No extension registered a handler contract for the annotation '@%s'! Register it \
              through ExtensionHandlers#register before using it. Registered annotations: %s."""
              .formatted(
                  annotationType.getSimpleName(),
                  contracts.isEmpty()
                      ? "none"
                      : contracts
                          .keySet()
                          .stream()
                          .map(Class::getSimpleName)
                          .map("@%s"::formatted)
                          .sorted()
                          .collect(java.util.stream.Collectors.joining(", "))));
    }
    return contract;

  }

  private ExtensionHandlerMethod find(
      final Class<? extends Annotation> annotationType,
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<String> lookupKeys) {

    final var registered = methods.get(new RegistryKey(workflowModuleId, bpmnProcessId, annotationType));
    if (registered == null) {
      return null;
    }
    synchronized (registered) {
      return registered
          .stream()
          .filter(method -> method.matches(lookupKeys))
          .findFirst()
          .orElse(null);
    }

  }

}
