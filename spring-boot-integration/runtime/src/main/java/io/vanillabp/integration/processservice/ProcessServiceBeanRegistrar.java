package io.vanillabp.integration.processservice;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.AwareSelection;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.utils.SpringDataUtil;
import io.vanillabp.integration.utils.impl.SpringDataUtilBasedAggregatePersistenceSupport;
import io.vanillabp.integration.workflowmodule.WorkflowModule;
import io.vanillabp.integration.workflowmodule.WorkflowModules;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.MultiInstanceElementResolver;
import io.vanillabp.spi.service.WorkflowService;
import lombok.extern.slf4j.Slf4j;

/**
 * A Spring Framework {@link BeanRegistrar} building
 * {@link io.vanillabp.spi.process.ProcessService} beans for each aggregate type of the
 * workflow services handed to it. Who those are is answered by
 * {@link WorkflowServiceDiscovery}, which runs while the bean definitions of the
 * application are complete and hands the classes in.
 * <p>
 * At registration time no bean is touched. Each bean is registered with a
 * generics-aware target type ({@code ProcessService<WorkflowAggregate>}), so generic
 * autowiring works, and with a lazy supplier: all dependencies (properties,
 * persistence support, adapter process services) are resolved through the
 * {@link BeanRegistry.SupplierContext} at bean-creation time. This way neither
 * Hibernate/DataSource nor adapter beans are materialized during the bean-factory
 * post-processing phase, keeping AOP proxying and
 * {@code @ConfigurationProperties} binding intact for all beans involved.
 */
@Slf4j
public class ProcessServiceBeanRegistrar implements BeanRegistrar {

  /**
   * The classes annotated by {@link WorkflowService} the application registered a bean
   * of, in the order the discovery walked the bean definitions.
   */
  private final List<Class<?>> workflowServiceClasses;

  /**
   * The service interfaces the extensions of this application offer per workflow
   * aggregate, from their {@code AggregateServiceFactory} beans. One bean of each is
   * registered per aggregate, next to its {@code ProcessService}.
   */
  private final List<Class<?>> aggregateServiceInterfaces;

  public ProcessServiceBeanRegistrar(
      final List<Class<?>> workflowServiceClasses) {

    this(workflowServiceClasses, List.of());

  }

  public ProcessServiceBeanRegistrar(
      final List<Class<?>> workflowServiceClasses,
      final List<Class<?>> aggregateServiceInterfaces) {

    this.workflowServiceClasses = workflowServiceClasses;
    this.aggregateServiceInterfaces = aggregateServiceInterfaces;

  }

  @Override
  public void register(
      final BeanRegistry registry,
      final Environment environment) {

    // build ProcessService<A> bean definitions: ONE injectable bean per
    // aggregate type (the SPI's injection contract), whose primary BPMN process
    // is picked among the classes declaring the aggregate. ALL classes declaring
    // the aggregate and ALL their declared BPMN process IDs (bpmnProcess +
    // secondaryBpmnProcesses) are additionally registered for phase-two routing
    // and @WorkflowTask processing.
    //
    // Both loops know which class respectively which aggregate they are on, so every
    // failure is reported with that name - a defect nobody foresaw included, which is
    // what this used to swallow into one message naming nothing
    final var classesByAggregate = new LinkedHashMap<Class<?>, List<Class<?>>>();
    for (final var serviceClass : workflowServiceClasses) {
      try {
        classesByAggregate
            .computeIfAbsent(
                serviceClass.getAnnotation(WorkflowService.class).workflowAggregateClass(),
                aggregateType -> new LinkedList<>())
            .add(serviceClass);
      } catch (Exception e) {
        throw new IllegalStateException(
            "Could not read @WorkflowService of class '%s'".formatted(serviceClass.getName()), e);
      }
    }
    classesByAggregate
        .forEach((
            workflowAggregateType,
            serviceClasses) -> {
          try {
            registerProcessServiceBean(
                registry,
                workflowServiceClasses,
                serviceClasses,
                workflowAggregateType);
            aggregateServiceInterfaces
                .forEach(
                    serviceInterface -> registerAggregateServiceBean(
                        registry,
                        serviceInterface,
                        workflowAggregateType));
          } catch (Exception e) {
            throw new IllegalStateException(
                "Could not register the ProcessService of workflow aggregate '%s' declared by %s"
                    .formatted(
                        workflowAggregateType.getName(),
                        serviceClasses
                            .stream()
                            .map(Class::getName)
                            .sorted()
                            .collect(Collectors.joining(", "))), e);
          }
        });

  }

  /**
   * The primary BPMN process ID of a workflow service class:
   * {@code @WorkflowService.bpmnProcess().bpmnProcessId()} or, by convention, the
   * class' simple name.
   */
  static String primaryBpmnProcessId(
      final Class<?> serviceClass) {

    return Optional.of(serviceClass
        .getAnnotation(WorkflowService.class)
        .bpmnProcess()
        .bpmnProcessId())
        .filter(Predicate.not(String::isEmpty))
        .orElse(serviceClass.getSimpleName());

  }

  /**
   * The class declaring the process of the aggregate's {@code ProcessService}.
   *
   * @param serviceClasses All classes declaring this aggregate
   * @param workflowAggregateType The aggregate
   * @return The class whose primary BPMN process the process service serves
   * @throws IllegalStateException If the classes declare different primary processes
   *         for one aggregate - which of them {@code startWorkflow} would start
   *         cannot be decided here (the BPMN models are read later, by the adapter,
   *         while deploying), so the application decides it by naming one process as
   *         the primary one and the others as secondary
   */
  static Class<?> primaryWorkflowServiceClass(
      final List<Class<?>> serviceClasses,
      final Class<?> workflowAggregateType) {

    return primaryWorkflowServiceClass(
        serviceClasses,
        workflowAggregateType,
        ProcessServiceBeanRegistrar::primaryBpmnProcessId);

  }

  /**
   * The rule itself, with the process of a class handed in - the tests use it that
   * way, so a fixture standing in for a workflow service needs no annotation at all.
   *
   * @param serviceClasses All classes declaring this aggregate
   * @param workflowAggregateType The aggregate
   * @param primaryProcessOf The primary BPMN process of a class
   * @return The class whose primary BPMN process the process service serves
   */
  static Class<?> primaryWorkflowServiceClass(
      final List<Class<?>> serviceClasses,
      final Class<?> workflowAggregateType,
      final Function<Class<?>, String> primaryProcessOf) {

    // reproducible: with several classes on one process the choice must not depend on
    // the order the bean definitions happened to be registered in
    final var sorted = serviceClasses
        .stream()
        .sorted(Comparator.comparing(Class::getName))
        .toList();
    final var distinctProcesses = sorted
        .stream()
        .map(primaryProcessOf)
        .distinct()
        .toList();
    if (distinctProcesses.size() > 1) {
      throw new IllegalStateException(
          ambiguousPrimaryProcessMessage(sorted, workflowAggregateType, primaryProcessOf));
    }
    return sorted.getFirst();

  }

  /**
   * The message reporting several BPMN processes declared as the primary one for a
   * single workflow aggregate.
   *
   * @param serviceClasses The classes declaring the aggregate, sorted
   * @param workflowAggregateType The aggregate
   * @param primaryProcessOf The primary BPMN process of a class
   * @return The message
   */
  static String ambiguousPrimaryProcessMessage(
      final List<Class<?>> serviceClasses,
      final Class<?> workflowAggregateType,
      final Function<Class<?>, String> primaryProcessOf) {

    final var declarations = serviceClasses
        .stream()
        .map(serviceClass -> "  %s declares '%s'".formatted(
            serviceClass.getName(),
            primaryProcessOf.apply(serviceClass)))
        .collect(Collectors.joining("\n"));
    return """
        Several classes annotated with @WorkflowService declare a DIFFERENT BPMN process for the \
        workflow aggregate '%s':
        %s
        VanillaBP provides one ProcessService per workflow aggregate (that is what \
        'ProcessService<%s>' injects), so exactly one of these processes is the one \
        'startWorkflow' starts - and picking one of them here would be a coin flip.
        Declare the process to be started as the 'bpmnProcess' of ONE class and move the others \
        into that class' 'secondaryBpmnProcesses' (a process called by a call activity is the \
        typical case). Handlers of a secondary process may stay in their own class as long as \
        that class declares the same 'bpmnProcess'."""
        .formatted(
            workflowAggregateType.getName(),
            declarations,
            workflowAggregateType.getSimpleName());

  }

  /**
   * All BPMN process IDs a workflow service class declares: the primary
   * {@code bpmnProcess} plus every {@code secondaryBpmnProcesses} entry. Secondary
   * entries have to be explicit - there is no class-name convention for them.
   */
  static List<String> declaredBpmnProcessIds(
      final Class<?> serviceClass) {

    final var annotation = serviceClass.getAnnotation(WorkflowService.class);
    final var bpmnProcessIds = new LinkedList<String>();
    bpmnProcessIds.add(primaryBpmnProcessId(serviceClass));
    for (final var secondary : annotation.secondaryBpmnProcesses()) {
      if (secondary.bpmnProcessId().isEmpty()) {
        throw new IllegalStateException(
            """
                A secondaryBpmnProcesses entry of @WorkflowService at '%s' has no bpmnProcessId! \
                Secondary BPMN processes have to be declared explicitly, e.g. \
                @BpmnProcess(bpmnProcessId = "MyOtherProcess")."""
                .formatted(serviceClass.getName()));
      }
      bpmnProcessIds.add(secondary.bpmnProcessId());
    }
    return bpmnProcessIds;

  }

  /**
   * Registers the bean definition for a single
   * {@link io.vanillabp.spi.process.ProcessService} bean: registering a definition
   * having a lazy supplier (a promise to Spring that this bean will be available)
   * instead of an instance avoids circular dependencies between the class annotated
   * by {@link WorkflowService} and the ProcessService bean.
   */
  @SuppressWarnings({
      "unchecked", "rawtypes"
  })
  private <A> void registerProcessServiceBean(
      final BeanRegistry registry,
      final List<Class<?>> allWorkflowServiceClasses,
      final List<Class<?>> serviceClasses,
      final Class<A> workflowAggregateType) {

    // ONE ProcessService per aggregate is the SPI's injection contract, so ONE of
    // the classes declares the process startWorkflow starts, and it is picked by a
    // rule instead of by the order the classes were found in. Several classes
    // declaring the SAME process are fine (handlers split across classes);
    // different ones are ambiguous and end the boot.
    final var serviceClass = primaryWorkflowServiceClass(serviceClasses, workflowAggregateType);
    final var bpmnProcessId = primaryBpmnProcessId(serviceClass);

    final var beanType = ParameterizedTypeReference
        .<ProcessService<A>>forType(ResolvableType
            .forClassWithGenerics(ProcessService.class, workflowAggregateType)
            .getType());

    registry.registerBean(
        "VanillaBP_ProcessService_%s".formatted(workflowAggregateType.getName()),
        beanType,
        spec -> spec
            .supplier(supplierContext -> {

              // associate workflow services with workflow modules (done only once;
              // uses the application's resource loader to find the modules)
              final var allWorkflowModules = supplierContext.bean(WorkflowModules.class);
              allWorkflowModules.associateWorkflowServices(allWorkflowServiceClasses);

              final var workflowModuleId = workflowModuleOf(allWorkflowModules, serviceClass);

              final var properties = supplierContext.bean(
                  SpringBootMigrationAdapterAutoConfiguration.BEANNAME_MIGRATIONADAPERPROPERTIES,
                  MigrationAdapterProperties.class);

              // find persistence support for the aggregate class (most specific
              // aggregate class wins - selection shared with the core)
              final List<AggregatePersistenceAware<?>> aggregatePersistenceAwares = supplierContext
                  .beanProvider(AggregatePersistenceAware.class)
                  .stream()
                  .<AggregatePersistenceAware<?>>map(aware -> (AggregatePersistenceAware<?>) aware)
                  .toList();
              final var aggregatePersistenceAware = (AggregatePersistenceAware<A>) AwareSelection
                  .mostSpecific(
                      aggregatePersistenceAwares,
                      AggregatePersistenceAware::getAggregateClass,
                      workflowAggregateType)
                  // if none found, fall back to persistence support based on Spring Data Util bean
                  .orElseGet(() -> {
                    final var springDataUtil = supplierContext
                        .beanProvider(SpringDataUtil.class)
                        .getIfAvailable();
                    if (springDataUtil == null) {
                      throw new IllegalStateException(
                          """
                              Spring Data Util bean not found! To solve this either
                              - add spring-boot-starter-data-jpa to classpath and configure a data source, if you use JPA for persistence of aggregates
                              - add spring-boot-starter-data-mongodb to classpath and configure the MongoDb connection, if you use MongoDb for persistence of aggregates
                              - add your own implementation of io.vanillabp.integration.utils.SpringDataUtil, if you use an alternative persistence""");
                    }
                    // the workflow module travels along for the message:
                    // an aggregate without a repository is reported while the
                    // application starts, and naming the module is what makes the
                    // report actionable in an application with several of them
                    return new SpringDataUtilBasedAggregatePersistenceSupport(
                        springDataUtil, workflowAggregateType, workflowModuleId);
                  });

              final var migratableProcessServices = supplierContext
                  .beanProvider(MigratableProcessService.class)
                  .stream()
                  .map(processService -> (MigratableProcessService<A>) processService)
                  .toList();

              // resolves the outbox per aggregate (mixed persistence, dedicated
              // outboxes) - invoked at startup by the platform's startup
              // validation, never mid-bean-construction
              final var phaseTwoOutboxResolver = supplierContext
                  .bean(SpringPhaseTwoOutboxResolver.class);

              // the same, for the log of processed task deliveries: a record has to
              // ride the aggregate's own transaction, so the store is picked per
              // aggregate as well
              final var taskDeliveryLogResolver = supplierContext
                  .bean(SpringTaskDeliveryLogResolver.class);

              // and the same for the transaction the work runs in: an application
              // storing this aggregate in a system Spring does not manage contributes
              // its own runner
              final var transactionRunnerResolver = supplierContext
                  .bean(SpringTransactionRunnerResolver.class);

              // the bean registers itself with the router as phase-two dispatch
              // target of this workflow module/BPMN process
              final var phaseTwoRouter = supplierContext
                  .bean(PhaseTwoRouter.class);

              // the election cache (in-memory default or the application's own
              // bean, e.g. cluster-shared), counted by the application's statistics
              final var workflowAdapterCache = io.vanillabp.integration.adapter.migration.processservice.InstrumentedWorkflowAdapterCache
                  .instrument(
                      selectWorkflowAdapterCache(
                          supplierContext
                              .beanProvider(io.vanillabp.integration.spi.WorkflowAdapterCache.class)
                              .stream()
                              .toList()),
                      supplierContext.bean(
                          io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheStatistics.class));

              // what deliveries of this process are counted into; absent
              // where the application brings no metrics backend
              final var metrics = SpringBootMigrationAdapterAutoConfiguration
                  .vanillaBpMetricsOf(
                      supplierContext
                          .beanProvider(
                              io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.class));

              final var processServiceBean = new ProcessServiceSpringBean<A>(
                  workflowModuleId, bpmnProcessId, workflowAggregateType, properties, aggregatePersistenceAware, migratableProcessServices, phaseTwoOutboxResolver, phaseTwoRouter, workflowAdapterCache, taskDeliveryLogResolver, transactionRunnerResolver);
              processServiceBean
                  .getMigrationProcessService()
                  .setMetrics(metrics);

              // register ALL classes declaring this aggregate under ALL their
              // declared BPMN process IDs: @WorkflowTask handlers per (module,
              // process) plus phase-two routing for secondary processes
              final var taskRegistry = supplierContext.bean(WorkflowTaskRegistry.class);
              // resolver beans (@MultiInstanceElement(resolverBean = ...)) are
              // looked up lazily among all MultiInstanceElementResolver beans
              final var resolverBeans = supplierContext.beanProvider(MultiInstanceElementResolver.class);
              final Function<Class<?>, Object> beanResolver = type -> resolverBeans
                  .stream()
                  .filter(type::isInstance)
                  .findFirst()
                  .orElse(null);
              // insertion ordered, and the primary service goes in first: the startup
              // validations run over this list and a message about the primary id is the one
              // a reader expects to meet first
              final var processServicesByKey = new LinkedHashMap<String, MigrationProcessService<A>>();
              processServicesByKey.put(
                  "%s|%s".formatted(workflowModuleId, bpmnProcessId),
                  processServiceBean.getMigrationProcessService());
              // What an awareness probe is asked about is the workflow module
              // AND every BPMN process serving this aggregate there - a secondary process
              // of the same @WorkflowService runs on the same workflow, so an instance of
              // it is a legitimate answer. Collected while the services are registered,
              // because this is the only place which sees all declaring classes at once.
              final var processIdsByModule = new java.util.LinkedHashMap<String, java.util.List<String>>();
              final var moduleOfProcessService = new java.util.LinkedHashMap<MigrationProcessService<A>, String>();
              moduleOfProcessService.put(processServiceBean.getMigrationProcessService(), workflowModuleId);
              processIdsByModule
                  .computeIfAbsent(workflowModuleId, module -> new java.util.LinkedList<>())
                  .add(bpmnProcessId);
              for (final var declaringClass : serviceClasses) {
                final var declaringModuleId = workflowModuleOf(allWorkflowModules, declaringClass);
                for (final var declaredProcessId : declaredBpmnProcessIds(declaringClass)) {
                  final var processService = processServicesByKey.computeIfAbsent(
                      "%s|%s".formatted(declaringModuleId, declaredProcessId),
                      key -> {
                        final var secondaryProcessService = MigrationProcessService
                            .<A>forBpmnProcess(declaringModuleId, declaredProcessId, workflowAggregateType)
                            .properties(properties)
                            .aggregatePersistence(aggregatePersistenceAware)
                            .processServices(migratableProcessServices)
                            .phaseTwoOutboxResolver(phaseTwoOutboxResolver)
                            .workflowAdapterCache(workflowAdapterCache)
                            .taskDeliveryLogResolver(taskDeliveryLogResolver)
                            .transactionRunnerResolver(transactionRunnerResolver)
                            .build();
                        secondaryProcessService.setMetrics(metrics);
                        if (phaseTwoRouter != null) {
                          phaseTwoRouter.register(secondaryProcessService);
                        }
                        return secondaryProcessService;
                      });
                  moduleOfProcessService.put(processService, declaringModuleId);
                  final var declaredIds = processIdsByModule
                      .computeIfAbsent(declaringModuleId, module -> new java.util.LinkedList<>());
                  if (!declaredIds.contains(declaredProcessId)) {
                    declaredIds.add(declaredProcessId);
                  }
                  final var declaringBeanProvider = supplierContext.beanProvider(declaringClass);
                  taskRegistry.registerWorkflowService(
                      declaringModuleId,
                      declaredProcessId,
                      declaringClass,
                      declaringBeanProvider::getObject,
                      beanResolver,
                      processService);
                }
              }

              // everything configurable per workflow is configurable for a secondary or
              // declared-only id as well, so the startup validations get every declared id
              // rather than the primary one alone
              processServiceBean.setProcessServicesOfDeclaredIds(processServicesByKey.values());

              // every process service of this aggregate answers for the processes of ITS
              // workflow module
              moduleOfProcessService
                  .forEach((
                      processService,
                      moduleId) -> processService
                          .setServedBpmnProcessIds(processIdsByModule.get(moduleId)));

              return processServiceBean;

            }));

  }

  /**
   * Registers the per-aggregate bean of one service an extension offers - the same shape
   * the {@code ProcessService} of that aggregate has, so an injection point naming
   * {@code TheService<TheAggregate>} resolves.
   * <p>
   * The bean is LAZY, which is what makes injecting it optional: an application which
   * never asks for the service never has the extension's factory called. The factory
   * itself is resolved when the bean is built rather than at definition time, because
   * that is what keeps its own dependencies out of the bean-factory post-processing
   * phase.
   */
  @SuppressWarnings({
      "unchecked", "rawtypes"
  })
  private <S> void registerAggregateServiceBean(
      final BeanRegistry registry,
      final Class<S> serviceInterface,
      final Class<?> workflowAggregateType) {

    final var beanType = ParameterizedTypeReference
        .<S>forType(ResolvableType
            .forClassWithGenerics(serviceInterface, workflowAggregateType)
            .getType());

    registry
        .registerBean(
            "VanillaBP_ExtensionService_%s_%s".formatted(serviceInterface.getName(), workflowAggregateType.getName()),
            beanType,
            spec -> spec
                .lazyInit()
                .supplier(supplierContext -> {

                  final var factory = supplierContext
                      .beanProvider(io.vanillabp.integration.extension.spi.service.AggregateServiceFactory.class)
                      .stream()
                      .filter(candidate -> serviceInterface.equals(candidate.getServiceInterface()))
                      .findFirst()
                      .orElseThrow(() -> new IllegalStateException(
                          """
                              No AggregateServiceFactory of this application builds a '%s'! It was one \
                              while the bean definitions were read, so the factory bean disappeared \
                              afterwards - check the conditions on the extension's configuration."""
                              .formatted(serviceInterface.getName())));

                  final var processService = supplierContext
                      .beanProvider(io.vanillabp.spi.process.ProcessService.class)
                      .stream()
                      .filter(ProcessServiceSpringBean.class::isInstance)
                      .map(ProcessServiceSpringBean.class::cast)
                      .filter(
                          candidate -> workflowAggregateType
                              .equals(candidate.getMigrationProcessService().getWorkflowAggregateClass()))
                      .findFirst()
                      .orElseThrow(() -> new IllegalStateException(
                          """
                              No ProcessService of the workflow aggregate '%s' exists, so the '%s' of \
                              extension cannot be built either!"""
                              .formatted(workflowAggregateType.getName(), serviceInterface.getName())));

                  final var context = new io.vanillabp.integration.adapter.migration.processservice.ExtensionAggregateServiceContext(
                      processService.getMigrationProcessService(), supplierContext
                          .bean(io.vanillabp.integration.extension.spi.handler.ExtensionHandlers.class), supplierContext
                              .bean(io.vanillabp.integration.extension.spi.election.WorkflowElection.class));

                  return serviceInterface.cast(factory.createService(context));

                }));

  }

  /**
   * Selects the election cache, by the rule which is asked in two places (see
   * {@link WorkflowAdapterCacheSelection}).
   *
   * @param candidates All {@code WorkflowAdapterCache} beans
   * @return The cache to use or <code>null</code> if none exists (elections then
   *         probe every time)
   */
  private static io.vanillabp.integration.spi.WorkflowAdapterCache selectWorkflowAdapterCache(
      final List<io.vanillabp.integration.spi.WorkflowAdapterCache> candidates) {

    return WorkflowAdapterCacheSelection.theCacheInUse(candidates);

  }

  private static String workflowModuleOf(
      final WorkflowModules allWorkflowModules,
      final Class<?> serviceClass) {

    return allWorkflowModules
        .getWorkflowModules()
        .stream()
        .filter(workflowModule -> workflowModule.isWorkflowServiceKnown(serviceClass))
        .findFirst()
        .map(WorkflowModule::getId)
        .orElseThrow(() -> new IllegalStateException(
            """
                Workflow service class '%s' does not belong to any workflow module! Every \
                @WorkflowService class must be part of a workflow module (a classpath \
                entry having a 'META-INF/workflow-module' marker file) or of the global \
                workflow module (no marker file anywhere in the application)."""
                .formatted(serviceClass.getName())));

  }

}
