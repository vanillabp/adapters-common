package io.vanillabp.integration.processservice;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

import io.vanillabp.integration.adapter.migration.deployment.UnclaimedBpmnProcessHints;
import io.vanillabp.integration.workflowmodule.WorkflowModule;
import io.vanillabp.integration.workflowmodule.WorkflowModules;
import io.vanillabp.spi.service.WorkflowService;
import lombok.extern.slf4j.Slf4j;

/**
 * The class which carries <code>&#64;WorkflowService</code>, declares a BPMN process nothing
 * serves, and never became a bean - a forgotten <code>&#64;Service</code>, and the reason an
 * application looks like it is doing nothing at all with a workflow service somebody wrote.
 * <p>
 * On Spring Boot a workflow service is found because it is a bean (decision 21 in the
 * repository's DECISIONS.md), which is why the discovery cannot see such a class: it walks the
 * bean definitions, and a class nobody registered a bean of has no definition to walk. Reading
 * class resources is the only way to the answer, and it is expensive - the measurement of that
 * entry is 42 816 class resources and 15.9 of 24.4 seconds of a start under
 * <code>spring-boot:run</code>. So it is done exactly once, on a boot which is already
 * reporting a BPMN process nothing claims, and never on a healthy one: no report, no scan.
 *
 * <h2>What narrows the answer</h2>
 *
 * A class produces a line only where BOTH hold: it declares one of the processes being
 * reported, and its classpath root belongs to the workflow module being reported. Without the
 * second half a class which another profile brings for a DIFFERENT module fires on every boot
 * of an application which has one unclaimed process anywhere, and a warning which cries wolf is
 * read once.
 * <p>
 * The whole classpath is read only where the module's own root yielded nothing, because the
 * workflow services of the GLOBAL workflow module live in a root with no marker file at all. A
 * class found that way is kept only where its root carries no workflow module descriptor
 * either: a root with one belongs to that module, not to the one being reported.
 *
 * <h2>What the sentence may not claim</h2>
 *
 * That the class is a defect. The boot cannot tell a forgotten bean-defining annotation from a
 * class belonging to a profile which is not active or to another application - decision 21 says
 * so in as many words - so the line names both readings and lets the developer decide.
 */
@Slf4j
public class WorkflowServicesWhichAreNoBeans implements UnclaimedBpmnProcessHints {

  private static final String CLASS_RESOURCES_OF_A_ROOT = "**/*.class";

  private static final String CLASS_RESOURCES_OF_THE_CLASSPATH = "classpath*:**/*.class";

  private final ResourceLoader resourceLoader;

  private final WorkflowModules allWorkflowModules;

  public WorkflowServicesWhichAreNoBeans(
      final ResourceLoader resourceLoader,
      final WorkflowModules allWorkflowModules) {

    this.resourceLoader = resourceLoader;
    this.allWorkflowModules = allWorkflowModules;

  }

  @Override
  public List<String> whatElseIsWorthSaying(
      final String workflowModuleId,
      final Collection<String> bpmnProcessIds) {

    if ((bpmnProcessIds == null) || bpmnProcessIds.isEmpty()) {
      return List.of();
    }
    final var resolver = new PathMatchingResourcePatternResolver(resourceLoader);
    final var metadata = new CachingMetadataReaderFactory(resourceLoader);
    final var moduleRoots = allWorkflowModules
        .getWorkflowModules()
        .stream()
        .map(WorkflowModule::getSourceUri)
        .filter(java.util.Objects::nonNull)
        .toList();
    final var ownRoot = allWorkflowModules
        .getWorkflowModules()
        .stream()
        .filter(module -> module.getId().equals(workflowModuleId))
        .map(WorkflowModule::getSourceUri)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(null);

    final var found = ownRoot == null
        ? List.<String>of()
        : classesDeclaring(
            ownRoot + CLASS_RESOURCES_OF_A_ROOT,
            bpmnProcessIds,
            resolver,
            metadata,
            List.of());
    if (!found.isEmpty()) {
      return found;
    }
    // the global workflow module: its descriptor sits in a root of its own while its
    // workflow services live in roots which carry no descriptor at all, so the module's
    // root cannot lead to them and the whole classpath has to be read
    return classesDeclaring(
        CLASS_RESOURCES_OF_THE_CLASSPATH,
        bpmnProcessIds,
        resolver,
        metadata,
        moduleRoots);

  }

  /**
   * The classes matching the given resource pattern which declare one of the reported BPMN
   * processes, as the lines the report appends.
   *
   * @param pattern The class resources to read
   * @param bpmnProcessIds The BPMN process ids nothing claims
   * @param resolver Resolves the pattern
   * @param metadata Reads a class' annotations without loading the class
   * @param rootsWhichDisqualify Classpath roots a candidate must NOT come from - the roots of
   *          the workflow modules, where the whole classpath is being read
   * @return One line per class, in the order the classes were read
   */
  private List<String> classesDeclaring(
      final String pattern,
      final Collection<String> bpmnProcessIds,
      final PathMatchingResourcePatternResolver resolver,
      final MetadataReaderFactory metadata,
      final Collection<String> rootsWhichDisqualify) {

    final Resource[] classResources;
    try {
      classResources = resolver.getResources(pattern);
    } catch (final IOException e) {
      // a classpath which cannot be read is not this report's business to complain about
      log.debug("Could not read the class resources of '{}'", pattern, e);
      return List.of();
    }
    final var lines = new LinkedList<String>();
    for (final var classResource : classResources) {
      if (comesFromOneOf(classResource, rootsWhichDisqualify)) {
        continue;
      }
      final var declared = declaredProcessesOf(classResource, metadata);
      if (declared.getKey().isEmpty()) {
        continue;
      }
      final var reported = declared
          .getValue()
          .stream()
          .filter(bpmnProcessIds::contains)
          .toList();
      if (reported.isEmpty()) {
        continue;
      }
      lines.add(describe(declared.getKey(), declared.getValue()));
    }
    return List.copyOf(lines);

  }

  private static boolean comesFromOneOf(
      final Resource classResource,
      final Collection<String> roots) {

    if (roots.isEmpty()) {
      return false;
    }
    final String url;
    try {
      url = classResource.getURL().toString();
    } catch (final IOException e) {
      return true;
    }
    return roots.stream().anyMatch(url::startsWith);

  }

  /**
   * The class behind the given resource together with the BPMN process ids its
   * <code>&#64;WorkflowService</code> declares, or an empty entry where it carries none.
   * <p>
   * The annotation is <code>&#64;Inherited</code>, so a class extending an annotated one is a
   * workflow service too and the walk goes up the superclasses - and it is the class at the
   * BOTTOM which names itself where the declaration names no process, because that is the class
   * which serves (decision 32 in the repository's DECISIONS.md).
   */
  private static Map.Entry<String, List<String>> declaredProcessesOf(
      final Resource classResource,
      final MetadataReaderFactory metadata) {

    final MetadataReader reader;
    try {
      reader = metadata.getMetadataReader(classResource);
    } catch (final IOException e) {
      // a class resource which cannot be parsed says nothing about a workflow service
      return Map.entry("", List.of());
    }
    final var className = reader.getClassMetadata().getClassName();
    var current = reader;
    while (current != null) {
      final var attributes = current
          .getAnnotationMetadata()
          .getAnnotationAttributes(WorkflowService.class.getName());
      if (attributes != null) {
        return Map.entry(className, declaredProcessesOf(attributes, className));
      }
      final var superClassName = current.getClassMetadata().getSuperClassName();
      if ((superClassName == null) || superClassName.startsWith("java.")) {
        return Map.entry("", List.of());
      }
      try {
        current = metadata.getMetadataReader(superClassName);
      } catch (final IOException e) {
        return Map.entry("", List.of());
      }
    }
    return Map.entry("", List.of());

  }

  @SuppressWarnings("unchecked")
  private static List<String> declaredProcessesOf(
      final Map<String, Object> workflowService,
      final String className) {

    final var declared = new LinkedHashMap<String, Boolean>();
    final var primary = ((Map<String, Object>) workflowService.get("bpmnProcess")).get("bpmnProcessId");
    // an empty id is the convention: the simple name of the class which serves
    declared
        .put(
            (primary == null) || String.valueOf(primary).isEmpty()
                ? className.substring(Math.max(className.lastIndexOf('.'), className.lastIndexOf('$')) + 1)
                : String.valueOf(primary),
            Boolean.TRUE);
    final var secondary = workflowService.get("secondaryBpmnProcesses");
    if (secondary instanceof final Object[] entries) {
      for (final var entry : entries) {
        if (entry instanceof final Map<?, ?> attributes) {
          final var bpmnProcessId = attributes.get("bpmnProcessId");
          if ((bpmnProcessId != null) && !String.valueOf(bpmnProcessId).isEmpty()) {
            declared.put(String.valueOf(bpmnProcessId), Boolean.TRUE);
          }
        }
      }
    }
    return List.copyOf(declared.keySet());

  }

  /**
   * One line per class, naming the processes it declares: the reader of this report is looking
   * for a class to fix, and a class declaring several processes is one finding, not several.
   */
  private static String describe(
      final String className,
      final List<String> declaredProcessIds) {

    return """
          The class '%s' carries @WorkflowService (for %s) and is no bean of this application, so \
        VanillaBP built no ProcessService from it and no handler object could be resolved if a task \
        of that process arrived. Add a bean-defining annotation to it (@Service, @Component), a \
        @Bean method returning it, or, where it belongs to a workflow module you publish, the \
        auto-configuration of that module (see the wiki, 'Workflow modules in Spring Boot', \
        'Publishing a workflow module'). It reads the same way where nothing is wrong: the class \
        may belong to a profile which is not active here, or to another application sharing this \
        classpath."""
        .formatted(
            className,
            declaredProcessIds
                .stream()
                .collect(java.util.stream.Collectors.joining("', '", "'", "'")));

  }

}
