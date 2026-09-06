package io.vanillabp.integration.deployment.processservice;

import static io.quarkus.gizmo.Type.classType;
import static io.quarkus.gizmo.Type.parameterizedType;

import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Type;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.GeneratedBeanBuildItem;
import io.quarkus.arc.deployment.GeneratedBeanGizmoAdaptor;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.SignatureBuilder;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowServiceBelongsOnAClass;
import io.vanillabp.integration.deployment.config.MigrationAdapterPropertiesBuildItem;
import io.vanillabp.integration.deployment.validation.EnsureClassIsBeanValidationBuildItem;
import io.vanillabp.integration.deployment.workflowmodule.VanillaBpWorkflowModulesBuildItem;
import io.vanillabp.integration.runtime.processservice.ProcessServiceBaseCdiBean;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.WorkflowService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * VanillaBP extension build step processor, responsible for building {@link ProcessService} beans.
 */
@Slf4j
public class ProcessServiceBuildStepProcessor {

  public static final String ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_AGGREGATECLASS = "workflowAggregateClass";
  public static final String ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_BPMNPROCESS = "bpmnProcess";
  public static final String ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_BPMNPROCESS_BPMNPROCESSID = "bpmnProcessId";

  public static final String ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_SECONDARYBPMNPROCESSES = "secondaryBpmnProcesses";

  /**
   * Beans implementing {@link AggregatePersistenceAware} are not necessarily injected by
   * application code but looked up dynamically at runtime (see
   * {@link ProcessServiceBaseCdiBean}). This build step prevents ArC from removing them
   * as unused beans.
   *
   * @return The unremovable-bean build item covering all {@link AggregatePersistenceAware} beans
   */
  @BuildStep
  UnremovableBeanBuildItem preserveAggregatePersistenceAwareBeans() {

    return UnremovableBeanBuildItem.beanTypes(AggregatePersistenceAware.class);

  }

  /**
   * Build step for build {@link ProcessService} beans for all services
   * annotated by {@link WorkflowService} at class level.
   *
   * @param applicationArchivesBuildItem Information about All archives (JARs and directories) of the project
   * @param combinedIndex The index of the application and of all indexed dependencies, used to
   *        determine the persistence idiom of an aggregate the application provides no
   *        {@link AggregatePersistenceAware} for
   * @param migrationAdapterProperties Properties of the migration adapter previously built and validated as a dependency
   * @param workflowModulesFound Information about all workflow modules found in the project
   * @param generatedBeanBuildItemBuildProducer {@link BuildProducer} used to collect generated {@link ProcessService} beans
   * @param reflectiveClassBuildItemProducer {@link BuildProducer} used to register the classes VanillaBP scans by reflection: the workflow services and the aggregates whose ID it reads
   * @param unremovableBeanBuildItemProducer {@link BuildProducer} used to keep repositories alive which only VanillaBP uses
   * @param additionalBeanBuildItemBuildProducer {@link BuildProducer} used to collect beans provided in module "runtime"
   */
  @BuildStep
  void buildProcessServices(
      final ApplicationArchivesBuildItem applicationArchivesBuildItem,
      final CombinedIndexBuildItem combinedIndex,
      final MigrationAdapterPropertiesBuildItem migrationAdapterProperties,
      final VanillaBpWorkflowModulesBuildItem workflowModulesFound,
      final BuildProducer<EnsureClassIsBeanValidationBuildItem> ensureClassIsBeanBuildItemProducer,
      final BuildProducer<GeneratedBeanBuildItem> generatedBeanBuildItemBuildProducer,
      final BuildProducer<ReflectiveClassBuildItem> reflectiveClassBuildItemProducer,
      final BuildProducer<UnremovableBeanBuildItem> unremovableBeanBuildItemProducer,
      final BuildProducer<AdditionalBeanBuildItem> additionalBeanBuildItemBuildProducer) {

    final var aggregatePersistenceAwares = applicationArchivesBuildItem
        // search all archives of the project
        .getAllArchives()
        .stream()
        .flatMap(archive -> archive
            // collect each archive's known implementations
            .getIndex()
            .getAllKnownImplementations(AggregatePersistenceAware.class)
            .stream()
            .map(aware -> Map.entry(aware, archive)))
        .toList();

    // scan for classes annotated by @WorkflowService and group them by aggregate
    // type: ONE injectable ProcessService bean per aggregate (the SPI's injection
    // contract), whose primary BPMN process is the first class found declaring the
    // aggregate. ALL classes declaring the aggregate and ALL their declared BPMN
    // process IDs (bpmnProcess + secondaryBpmnProcesses) are recorded for
    // phase-two routing and @WorkflowTask processing at runtime.
    // "First class found" needs a stable archive order to be reproducible, which is
    // why getAllArchives is used: it lists the root archive first and the remaining
    // archives in the order Quarkus resolved them.
    final var workflowServiceAnnotations = applicationArchivesBuildItem
        // search all archives of the project
        .getAllArchives()
        .stream()
        .flatMap(archive -> archive
            .getIndex()
            .getAnnotations(WorkflowService.class)
            .stream())
        .toList();

    // an annotated type which is no class serves nothing, and this is where that is
    // still visible: what Jandex reports here is the type the annotation SITS on, and
    // the build ends before the ensure-is-bean item below turns the defect into a
    // question about CDI beans
    refuseAnnotationsNotOnAClass(combinedIndex.getIndex(), workflowServiceAnnotations);

    // an annotated class is a DECLARATION, and @Inherited says that every subclass of it
    // is a workflow service of its own. Jandex reports the declaration sites, so the
    // classes which will serve the processes are resolved here, while the index can still
    // be asked who extends whom
    final var workflowServices = workflowServiceClassesOf(combinedIndex.getIndex(), workflowServiceAnnotations);

    final var servicesByAggregate = new LinkedHashMap<Type, List<WorkflowServiceClass>>();
    workflowServices
        .forEach(service -> servicesByAggregate
            .computeIfAbsent(
                service
                    .annotation()
                    .value(ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_AGGREGATECLASS)
                    .asClass(),
                aggregateType -> new LinkedList<>())
            .add(service));

    servicesByAggregate
        // and build an adapter-aware process service for each workflow aggregate class
        .forEach((
            workflowAggregateType,
            services) -> {

          // record every class of this aggregate under all its declared BPMN
          // process IDs and make sure each class is a CDI bean at runtime
          final var workflowTaskRegistrations = new LinkedList<String>();
          for (final var service : services) {
            final var declaringClass = service.clazz();
            ensureClassIsBeanBuildItemProducer
                .produce(EnsureClassIsBeanValidationBuildItem
                    .builder()
                    .className(declaringClass.name())
                    .usageDescription("Workflow service annotated with @"
                        + WorkflowService.class.getName())
                    // a bean of a subclass is a workflow service of its own here, and one
                    // serving a BPMN process of its own wherever the process ID follows the
                    // class name - so it cannot stand in for this class
                    .aBeanOfASubclassCounts(false)
                    .remedy(notABeanRemedy(service))
                    .build());
            // the core finds the @WorkflowTask, @WorkflowStartedByBpms and @WorkflowEnded
            // methods by scanning the class at RUNTIME, and a native image reflects only
            // on what it was told about - without this every BPMN process is reported as
            // incompletely wired at startup, although the methods are right there. The
            // superclasses come along, because a handler the class inherits is declared
            // there and nowhere else
            declarationChainOf(combinedIndex.getIndex(), service)
                .forEach(scannedClass -> reflectiveClassBuildItemProducer
                    .produce(ReflectiveClassBuildItem
                        .builder(scannedClass.toString())
                        .methods()
                        .reason("VanillaBP scans the workflow service's methods by reflection")
                        .build()));
            final var declaringModuleId = workflowModulesFound
                .getWorkflowModuleId(
                    applicationArchivesBuildItem,
                    declaringClass);
            for (final var declaredProcessId : declaredBpmnProcessIds(service.annotation(), declaringClass)) {
              workflowTaskRegistrations.add(
                  "%s|%s|%s".formatted(declaringModuleId, declaringClass.name(), declaredProcessId));
            }
          }

          // ONE ProcessService per aggregate is the SPI's injection contract, so ONE
          // of the classes declares the process startWorkflow starts. Which one used
          // to be whichever class was found first. Several classes
          // declaring the SAME process are fine (handlers split across classes),
          // different ones are ambiguous and end the build.
          final var primaryService = primaryWorkflowService(services, workflowAggregateType);
          final var annotation = primaryService.annotation();
          final var serviceClass = primaryService.clazz();

          final var workflowModuleId = workflowModulesFound
              .getWorkflowModuleId(
                  applicationArchivesBuildItem,
                  serviceClass);
          final var bpmnProcessId = primaryBpmnProcessId(annotation, serviceClass);

          // find persistence support class for the aggregate class: an implementation
          // provided by the application always wins, the most specific one of them
          final var applicationPersistenceType = aggregatePersistenceAwares
              .stream()
              // calculate distance of classes
              .map(awareEntry -> Map.entry(
                  awareEntry.getKey(),
                  AggregatePersistenceResolver.distance(
                      awareEntry.getValue().getIndex(),
                      awareEntry.getKey(),
                      workflowAggregateType.name()
                  )))
              // filter persistence awares those aggregate type is not assignable to the current aggregate type
              .filter(awareEntry -> awareEntry.getValue() != Integer.MAX_VALUE)
              // choose the most specific persistence support in terms of inheritance class distance
              .min(Comparator.comparingInt(Map.Entry::getValue))
              .map(Map.Entry::getKey);

          final String aggregatePersistenceClassName;
          if (applicationPersistenceType.isPresent()) {
            // ensure service class will be a CDI bean at runtime
            ensureClassIsBeanBuildItemProducer
                .produce(EnsureClassIsBeanValidationBuildItem
                    .builder()
                    .className(applicationPersistenceType
                        .get()
                        .name())
                    .usageDescription("Service implementing the interface "
                        + AggregatePersistenceAware.class.getName())
                    .build());
            aggregatePersistenceClassName = applicationPersistenceType
                .get()
                .name()
                .toString();
          } else {
            // no implementation of the application: use the one matching the
            // persistence idiom the aggregate is written in
            final var defaultPersistence = DefaultAggregatePersistenceResolver
                .resolve(combinedIndex.getIndex(), workflowAggregateType.name())
                .orElseThrow(() -> new IllegalStateException(
                    missingAggregatePersistenceMessage(workflowAggregateType, workflowModuleId)));
            log.info(
                "Using VanillaBP's {} persistence for workflow aggregate '{}'{}",
                defaultPersistence.idiom(),
                workflowAggregateType.name(),
                defaultPersistence.repositoryClass() == null
                    ? ""
                    : " (repository '%s')".formatted(defaultPersistence.repositoryClass()));
            aggregatePersistenceClassName = "%s.AggregatePersistence_%s".formatted(
                workflowAggregateType
                    .name()
                    .packagePrefix(),
                workflowAggregateType
                    .name()
                    .withoutPackagePrefix());
            generateDefaultAggregatePersistence(
                generatedBeanBuildItemBuildProducer,
                aggregatePersistenceClassName,
                defaultPersistence,
                workflowAggregateType);
            if (defaultPersistence.repositoryClass() != null) {
              // an application whose repository is used by VanillaBP alone injects it
              // nowhere, and Quarkus removes beans nobody injects while building the
              // application (the same shape as the multi-instance
              // resolvers). Panache and Spring Data keep their repositories
              // themselves today, so this is insurance rather than a fix - VanillaBP
              // looks the bean up by class and should not depend on another
              // extension's decision
              unremovableBeanBuildItemProducer
                  .produce(UnremovableBeanBuildItem.beanTypes(defaultPersistence.repositoryClass()));
            }
            // the ID is read by reflection (AggregateIdTypes), which a native image
            // has to be told about - the persistence frameworks register their
            // entities themselves, but an aggregate may be neither
            reflectiveClassBuildItemProducer
                .produce(ReflectiveClassBuildItem
                    .builder(workflowAggregateType
                        .name()
                        .toString())
                    .fields()
                    .methods()
                    .reason("VanillaBP reads the workflow aggregate's ID by reflection")
                    .build());
          }

          // generate process service CDI bean specific to the workflow aggregate
          generateProcessService(
              generatedBeanBuildItemBuildProducer,
              workflowModuleId,
              bpmnProcessId,
              "%s.ProcessService_%s".formatted(
                  workflowAggregateType.name().packagePrefix(),
                  workflowAggregateType.name().withoutPackagePrefix()),
              aggregatePersistenceClassName,
              workflowAggregateType,
              String.join(";", workflowTaskRegistrations));

        });

  }

  /**
   * One class which IS a workflow service, together with the declaration it is one by:
   * either its own <code>&#64;WorkflowService</code> or the one it inherited from a
   * superclass.
   *
   * @param clazz The class serving the BPMN processes of the declaration
   * @param annotation The declaration, whose attributes {@link java.lang.annotation.Inherited}
   *        answers on the class as well
   * @param declaringClass The class the declaration sits on, equal to <code>clazz</code>
   *        wherever the class carries the annotation itself
   */
  private record WorkflowServiceClass(
                                      ClassInfo clazz,
                                      AnnotationInstance annotation,
                                      ClassInfo declaringClass) {
  }

  /**
   * The classes which serve the BPMN processes of the declarations found, which is what
   * <code>&#64;Inherited</code> promises the developer: a subclass of an annotated class is a
   * workflow service, and the class the handler methods are read off is the SUBCLASS. Jandex
   * resolves no <code>&#64;Inherited</code>, so the walk down to the subclasses happens here,
   * where the index can still be asked - and it is the same reading Spring Boot arrives at by
   * registering the class of the bean.
   * <p>
   * Abstract classes are left out: VanillaBP asks CDI for an instance of a workflow service, so
   * a class nobody can instantiate serves nothing. A declaration whose whole family is abstract
   * is therefore a declaration nobody serves, and the build ends naming it.
   *
   * @param index The index of the application and its indexed dependencies
   * @param annotations Every <code>&#64;WorkflowService</code> of the application archives
   * @return The workflow service classes, each of them once
   * @throws IllegalStateException If a declaration has no class which could serve it
   */
  private static List<WorkflowServiceClass> workflowServiceClassesOf(
      final IndexView index,
      final List<AnnotationInstance> annotations) {

    final var byClassName = new LinkedHashMap<DotName, WorkflowServiceClass>();
    final var declarationsNobodyServes = new LinkedHashMap<DotName, ClassInfo>();
    for (final var annotation : annotations) {
      final var declaringClass = annotation.target().asClass();
      final var serviceClasses = new LinkedList<ClassInfo>();
      if (!Modifier.isAbstract(declaringClass.flags())) {
        serviceClasses.add(declaringClass);
      }
      index
          .getAllKnownSubclasses(declaringClass.name())
          .stream()
          .filter(subclass -> !Modifier.isAbstract(subclass.flags()))
          .sorted(Comparator.comparing(subclass -> subclass.name().toString()))
          .forEach(serviceClasses::add);
      if (serviceClasses.isEmpty()) {
        declarationsNobodyServes.putIfAbsent(declaringClass.name(), declaringClass);
        continue;
      }
      for (final var serviceClass : serviceClasses) {
        final var known = byClassName.get(serviceClass.name());
        // a class inherits the annotation of its NEAREST annotated superclass, and its own
        // one - distance zero - replaces everything it inherited
        if ((known == null) || (inheritanceDistance(index, serviceClass,
            declaringClass.name()) < inheritanceDistance(index, serviceClass, known.declaringClass().name()))) {
          byClassName.put(
              serviceClass.name(),
              new WorkflowServiceClass(serviceClass, annotation, declaringClass));
        }
      }
    }
    if (!declarationsNobodyServes.isEmpty()) {
      throw new IllegalStateException(declarationsNobodyServes
          .values()
          .stream()
          .map(ProcessServiceBuildStepProcessor::declarationNobodyServesMessage)
          .collect(java.util.stream.Collectors.joining("\n")));
    }
    return List.copyOf(byClassName.values());

  }

  /**
   * How many superclasses lie between a class and the one carrying the declaration it serves,
   * zero where the class carries the declaration itself and
   * {@link Integer#MAX_VALUE} where the index does not connect the two.
   */
  private static int inheritanceDistance(
      final IndexView index,
      final ClassInfo serviceClass,
      final DotName declaringClassName) {

    var distance = 0;
    var current = serviceClass;
    while (current != null) {
      if (current.name().equals(declaringClassName)) {
        return distance;
      }
      final var superClass = current.superClassType();
      if (superClass == null) {
        return Integer.MAX_VALUE;
      }
      current = index.getClassByName(superClass.name());
      distance++;
    }
    return Integer.MAX_VALUE;

  }

  /**
   * The class and every superclass up to the one carrying the declaration: the handler
   * methods of a workflow service may be declared in any of them, and a native image reflects
   * only on what it was told about.
   */
  private static List<DotName> declarationChainOf(
      final IndexView index,
      final WorkflowServiceClass service) {

    final var chain = new LinkedList<DotName>();
    var current = service.clazz();
    while (current != null) {
      chain.add(current.name());
      if (current.name().equals(service.declaringClass().name())) {
        break;
      }
      final var superClass = current.superClassType();
      current = superClass == null
          ? null
          : index.getClassByName(superClass.name());
    }
    return chain;

  }

  /**
   * What to do about a workflow service class which is no CDI bean, which reads differently for
   * a class inheriting its declaration: there the developer may not have meant this class to be
   * a workflow service at all.
   */
  private static String notABeanRemedy(
      final WorkflowServiceClass service) {

    if (service.clazz().name().equals(service.declaringClass().name())) {
      return "Please annotate it with a bean-defining annotation such as @ApplicationScoped.";
    }
    return """
        It inherits @WorkflowService from
          %s
        and @WorkflowService is @Inherited, so this class is a workflow service of its own and \
        VanillaBP asks CDI for an instance of THIS class. Either annotate it with a \
        bean-defining annotation such as @ApplicationScoped, or, where it is meant to carry the \
        declaration only, make it abstract. Where it is no workflow service at all, let it \
        extend a class which does not carry the annotation."""
        .formatted(service.declaringClass().name());

  }

  /**
   * The message ending the build where a declaration has no class which could serve it.
   */
  private static String declarationNobodyServesMessage(
      final ClassInfo declaringClass) {

    return """
        @WorkflowService sits on the class
          %s
        which is abstract, and no class of this application extends it.
        VanillaBP reads the handler methods off the workflow service class and asks CDI for an \
        instance of it, so an abstract declaration reaches a BPMN process through a subclass \
        only: @WorkflowService is @Inherited, which makes every subclass of an annotated class a \
        workflow service of its own. Either write that subclass and make it a CDI bean, or move \
        the annotation onto the class holding the handler methods. If the subclass does exist, \
        the build did not see it: classes of a workflow module in its own Maven module have to be \
        indexed to be seen (see the jandex-maven-plugin)."""
        .formatted(declaringClass.name());

  }

  /**
   * Ends the build where <code>&#64;WorkflowService</code> does not sit on a class: on an
   * interface, or on an annotation of the application composing it - Jandex resolves no
   * meta-annotation and reports the annotation type itself as the annotated one, so both
   * shapes arrive here as an annotated INTERFACE. Every one of them is reported, so a
   * developer does not meet the second on the next build.
   *
   * @param index The index of the application and its indexed dependencies, asked which
   *        classes brought the declaration in
   * @param annotations Every <code>&#64;WorkflowService</code> of the application archives
   */
  private static void refuseAnnotationsNotOnAClass(
      final IndexView index,
      final List<AnnotationInstance> annotations) {

    final var declaringClasses = new LinkedHashMap<DotName, ClassInfo>();
    annotations
        .stream()
        .filter(annotation -> annotation.target().kind() == AnnotationTarget.Kind.CLASS)
        .map(annotation -> annotation.target().asClass())
        .filter(ClassInfo::isInterface)
        .forEach(declaringClass -> declaringClasses.putIfAbsent(declaringClass.name(), declaringClass));
    if (declaringClasses.isEmpty()) {
      return;
    }
    throw new IllegalStateException(declaringClasses
        .values()
        .stream()
        .map(declaringClass -> declaringClass.isAnnotation()
            ? WorkflowServiceBelongsOnAClass.foundOnAnAnnotation(
                declaringClass.name().toString(),
                classNamesOf(index
                    .getAnnotations(declaringClass.name())
                    .stream()
                    .filter(usage -> usage.target().kind() == AnnotationTarget.Kind.CLASS)
                    .map(usage -> usage.target().asClass())
                    .toList()))
            : WorkflowServiceBelongsOnAClass.foundOnAnInterface(
                declaringClass.name().toString(),
                classNamesOf(index.getAllKnownImplementors(declaringClass.name()))))
        .collect(java.util.stream.Collectors.joining("\n")));

  }

  /**
   * The names of the classes which brought a declaration in, sorted so the message does
   * not depend on the order the archives happened to be scanned in.
   */
  private static List<String> classNamesOf(
      final Collection<ClassInfo> classes) {

    return classes
        .stream()
        .map(clazz -> clazz.name().toString())
        .sorted()
        .toList();

  }

  /**
   * The message shown when neither the application nor one of the persistence idioms
   * VanillaBP knows answers for an aggregate. It names what was looked for, because
   * "provide a bean" alone leaves the reader guessing which of the Quarkus ways they
   * were expected to use.
   */
  private static String missingAggregatePersistenceMessage(
      final Type workflowAggregateType,
      final String workflowModuleId) {

    // the workflow module travels along, the way Spring Boot
    // report the same defect: an application with several modules should not have to
    // guess which one the aggregate belongs to, and the two platforms report the same
    // thing in the same words
    return """
        VanillaBP does not know how to persist the workflow aggregate '%s' of workflow module '%s'!
        Either the aggregate uses one of the persistence idioms VanillaBP serves out of the box:
        - a Panache repository for the aggregate (PanacheRepository/PanacheRepositoryBase, or \
        PanacheMongoRepository/PanacheMongoRepositoryBase): https://quarkus.io/guides/hibernate-orm-panache#solution-2-using-the-repository-pattern
        - the aggregate itself being a Panache active record (extending PanacheEntity/PanacheEntityBase, or \
        PanacheMongoEntity/PanacheMongoEntityBase): https://quarkus.io/guides/hibernate-orm-panache#solution-1-using-the-active-record-pattern
        - a Spring Data repository for the aggregate (extension quarkus-spring-data-jpa): https://quarkus.io/guides/spring-data-jpa
        None of them was found for this aggregate (mind that classes of workflow modules have to be \
        indexed using the jandex-maven-plugin to be seen).
        Or, for any other kind of persistence, provide a CDI bean implementing
          %s<%s>
        which is responsible to persist this aggregate."""
        .formatted(
            workflowAggregateType.name(),
            workflowModuleId,
            AggregatePersistenceAware.class.getName(),
            workflowAggregateType
                .name()
                .withoutPackagePrefix());

  }

  /**
   * Generates the CDI bean providing one of VanillaBP's persistence implementations
   * for a specific aggregate:
   *
   * <pre>
   * &#64;Singleton
   * public class AggregatePersistence_Aggregate extends PanacheRepositoryAggregatePersistence&lt;Aggregate&gt; {
   *   public AggregatePersistence_Aggregate() {
   *     super(Aggregate.class, AggregateRepository.class);
   *   }
   * }
   * </pre>
   *
   * The generic superclass is spelled out (not raw) on purpose: the bean type has to
   * be {@code AggregatePersistenceAware<Aggregate>} for the runtime lookup of
   * {@link ProcessServiceBaseCdiBean} to find it.
   *
   * @param className The class name of the bean to build
   * @param defaultPersistence The implementation chosen for the aggregate
   * @param workflowAggregateType The workflow aggregate type the persistence is for
   */
  private void generateDefaultAggregatePersistence(
      final BuildProducer<GeneratedBeanBuildItem> generatedBeanBuildItemBuildProducer,
      final String className,
      final DefaultAggregatePersistenceResolver.DefaultPersistence defaultPersistence,
      final Type workflowAggregateType) {

    final var beanClassOutput = new GeneratedBeanGizmoAdaptor(generatedBeanBuildItemBuildProducer);
    final var implementationClass = DotName.createSimple(defaultPersistence.implementationClass());
    final var cc = ClassCreator
        .builder()
        .classOutput(beanClassOutput)
        .className(className)
        .signature(SignatureBuilder
            .forClass()
            .setSuperClass(
                parameterizedType(classType(implementationClass), classType(workflowAggregateType.name()))))
        .build();

    cc.addAnnotation(Singleton.class);
    // the bean is never injected but looked up by its class at runtime
    cc.addAnnotation(Unremovable.class);

    final var constructor = cc.getConstructorCreator(new String[0]);
    final var aggregateClass = constructor.loadClass(workflowAggregateType
        .name()
        .toString());
    if (defaultPersistence.repositoryClass() == null) {
      constructor.invokeSpecialMethod(
          MethodDescriptor.ofConstructor(defaultPersistence.implementationClass(), Class.class.getName()),
          constructor.getThis(),
          aggregateClass);
    } else {
      constructor.invokeSpecialMethod(
          MethodDescriptor.ofConstructor(
              defaultPersistence.implementationClass(), Class.class.getName(), Class.class.getName()),
          constructor.getThis(),
          aggregateClass,
          constructor.loadClass(defaultPersistence
              .repositoryClass()
              .toString()));
    }
    constructor.returnVoid();

    cc.close();

  }

  /**
   * The primary BPMN process ID of a workflow service class:
   * {@code @WorkflowService.bpmnProcess().bpmnProcessId()} or, by convention, the
   * class' simple name.
   */
  private static String primaryBpmnProcessId(
      final AnnotationInstance annotation,
      final ClassInfo serviceClass) {

    return Optional
        .ofNullable(annotation.value(ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_BPMNPROCESS))
        .map(AnnotationValue::asNested)
        .map(a -> a.value(ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_BPMNPROCESS_BPMNPROCESSID))
        .map(AnnotationValue::asString)
        .filter(Predicate.not(String::isEmpty))
        .orElse(serviceClass.simpleName());

  }

  /**
   * The workflow service class declaring the process of the aggregate's process service.
   *
   * @param services All workflow service classes of this aggregate
   * @param workflowAggregateType The aggregate
   * @return The class whose primary BPMN process the process service serves
   * @throws IllegalStateException If the classes declare different primary processes
   *         for one aggregate - which of them {@code startWorkflow} would start
   *         cannot be decided here (the BPMN models are read later, by the adapter,
   *         while deploying), so the application decides it by naming one process as
   *         the primary one and the others as secondary
   */
  private static WorkflowServiceClass primaryWorkflowService(
      final List<WorkflowServiceClass> services,
      final Type workflowAggregateType) {

    // reproducible: with several classes on one process the choice must not depend on
    // the order the archives happened to be scanned in
    final var sorted = services
        .stream()
        .sorted(Comparator.comparing(candidate -> candidate
            .clazz()
            .name()
            .toString()))
        .toList();
    final var distinctProcesses = sorted
        .stream()
        .map(candidate -> primaryBpmnProcessId(candidate.annotation(), candidate.clazz()))
        .distinct()
        .toList();
    if (distinctProcesses.size() > 1) {
      final var declarations = sorted
          .stream()
          .map(candidate -> "  %s declares '%s'".formatted(
              candidate
                  .clazz()
                  .name(),
              primaryBpmnProcessId(candidate.annotation(), candidate.clazz())))
          .collect(java.util.stream.Collectors.joining("\n"));
      throw new IllegalStateException(
          """
              Several workflow service classes declare a DIFFERENT BPMN process for the workflow \
              aggregate '%s':
              %s
              VanillaBP provides one ProcessService per workflow aggregate (that is what \
              'ProcessService<%s>' injects), so exactly one of these processes is the one \
              'startWorkflow' starts - and picking one of them here would be a coin flip.
              Declare the process to be started as the 'bpmnProcess' of ONE class and move the \
              others into that class' 'secondaryBpmnProcesses' (a process called by a call \
              activity is the typical case). Handlers of a secondary process may stay in their own \
              class as long as that class declares the same 'bpmnProcess'.
              A class inheriting @WorkflowService from a superclass is one of these classes too, \
              and without an explicit 'bpmnProcess' each of them names its process after ITSELF - \
              so several subclasses of one annotated base need that 'bpmnProcess' on the base."""
              .formatted(
                  workflowAggregateType.name(),
                  declarations,
                  workflowAggregateType
                      .name()
                      .withoutPackagePrefix()));
    }
    return sorted.getFirst();

  }

  /**
   * All BPMN process IDs a workflow service class declares: the primary
   * {@code bpmnProcess} plus every {@code secondaryBpmnProcesses} entry. Secondary
   * entries have to be explicit - there is no class-name convention for them.
   */
  private static List<String> declaredBpmnProcessIds(
      final AnnotationInstance annotation,
      final ClassInfo serviceClass) {

    final var bpmnProcessIds = new LinkedList<String>();
    bpmnProcessIds.add(primaryBpmnProcessId(annotation, serviceClass));
    final var secondaries = annotation.value(ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_SECONDARYBPMNPROCESSES);
    if (secondaries != null) {
      for (final var secondary : secondaries.asNestedArray()) {
        final var secondaryProcessId = Optional
            .ofNullable(secondary.value(ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_BPMNPROCESS_BPMNPROCESSID))
            .map(AnnotationValue::asString)
            .filter(Predicate.not(String::isEmpty))
            .orElseThrow(() -> new IllegalStateException(
                """
                    A secondaryBpmnProcesses entry of @WorkflowService at '%s' has no bpmnProcessId! \
                    Secondary BPMN processes have to be declared explicitly, e.g. \
                    @BpmnProcess(bpmnProcessId = "MyOtherProcess")."""
                    .formatted(serviceClass.name())));
        bpmnProcessIds.add(secondaryProcessId);
      }
    }
    return bpmnProcessIds;

  }

  /**
   * Generate process service CDI bean specific to the workflow aggregate type given.
   *
   * @param generatedBeanBuildItemBuildProducer The producer used to build multiple beans if necessary
   * @param workflowModuleId The ID of the workflow module the service belongs to
   * @param bpmnProcessId The BPMN process ID the service is for
   * @param className The class name of the service to build
   * @param aggregatePersistenceClassName The aggregate persistence class to be used by the service
   * @param workflowAggregateType The workflow aggregate type the service is for
   */
  private void generateProcessService(
      final BuildProducer<GeneratedBeanBuildItem> generatedBeanBuildItemBuildProducer,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String className,
      final String aggregatePersistenceClassName,
      final Type workflowAggregateType,
      final String workflowTaskRegistrations) {

    final var aggregateClassName = workflowAggregateType.name().toString();

    /*
     * public class ProcessService_Aggregate extends ProcessServiceBaseCdiBean<Aggregate> {
     */
    final var beanClassOutput = new GeneratedBeanGizmoAdaptor(generatedBeanBuildItemBuildProducer);
    final var cc = ClassCreator
        .builder()
        .classOutput(beanClassOutput)
        .className(className)
        .signature(SignatureBuilder
            .forClass()
            .setSuperClass(
                parameterizedType(classType(ProcessServiceBaseCdiBean.class), classType(workflowAggregateType.name()))))
        .build();

    // @ApplicationScoped
    cc.addAnnotation(ApplicationScoped.class);
    // @Unremovable: the bean is injected by aggregate class specific injection points
    // (e.g. "ProcessService<MyAggregate>") which ArC's removal detection does not
    // recognize in all situations (e.g. if only accessed programmatically)
    cc.addAnnotation(Unremovable.class);

    /*
     * Class<AggregatePersistenceAware<A>> getAggregatePersistenceClass()
     */
    final var getAggregatePersistenceClass = cc.getMethodCreator(
        "getAggregatePersistenceClass",
        Class.class
    );
    // return AggregatePersistence.class;
    getAggregatePersistenceClass
        .returnValue(getAggregatePersistenceClass.loadClass(aggregatePersistenceClassName));

    /*
     * Class<A> getWorkflowAggregateClass()
     */
    final var getAggregateClass = cc.getMethodCreator(
        "getWorkflowAggregateClass",
        Class.class
    );
    // return A.class;
    getAggregateClass.returnValue(
        getAggregateClass.loadClass(aggregateClassName));

    /*
     * String getWorkflowModuleId()
     */
    final var getWorkflowModuleId = cc.getMethodCreator(
        "getWorkflowModuleId",
        String.class);
    // return "wmid";
    getWorkflowModuleId.returnValue(
        getWorkflowModuleId.load(workflowModuleId));

    /*
     * String getBpmnProcessId()
     */
    final var getBpmnProcessId = cc.getMethodCreator(
        "getBpmnProcessId",
        String.class);
    // return "pid";
    getBpmnProcessId.returnValue(
        getBpmnProcessId.load(bpmnProcessId));

    /*
     * String getWorkflowTaskRegistrations()
     */
    final var getWorkflowTaskRegistrations = cc.getMethodCreator(
        "getWorkflowTaskRegistrations",
        String.class);
    // return "module|class|pid;...";
    getWorkflowTaskRegistrations.returnValue(
        getWorkflowTaskRegistrations.load(workflowTaskRegistrations));

    cc.close();

  }

}
