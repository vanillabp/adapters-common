package io.vanillabp.integration.adapter.migration.workflowend;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.migration.handler.HandlerContexts;
import io.vanillabp.integration.adapter.migration.workflowtask.InheritedVersions;
import io.vanillabp.integration.adapter.migration.workflowtask.VersionRange;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;
import io.vanillabp.integration.extension.spi.handler.HandlerValueSource;

/**
 * One <code>&#64;WorkflowEnded</code> method of a workflow service class, with its
 * parameter binders and the end event it serves.
 */
// see decision 1 in the repository's DECISIONS.md
@SuppressWarnings("LombokGetterMayBeUsed")
public class WorkflowEndedHandler {

  private final Class<?> workflowServiceClass;

  private final Method method;

  private final Supplier<Object> workflowServiceBean;

  private final List<HandlerValueSource> binders;

  /**
   * The BPMN id of the end event this method serves, or <code>null</code> for every
   * end of the workflow.
   */
  private final String endEventId;

  private final List<VersionRange> versions;

  /**
   * Where the version range came from if the method named none itself: the
   * <code>&#64;BpmnProcess</code> declaration the handlers of this class were
   * registered for. <code>null</code> where the method names its own range, whose
   * messages need no origin - the attribute is in front of whoever reads them.
   */
  private final String versionsInheritedFrom;

  WorkflowEndedHandler(
      final Class<?> workflowServiceClass,
      final Method method,
      final Supplier<Object> workflowServiceBean,
      final List<HandlerValueSource> binders,
      final String endEventId,
      final InheritedVersions.EffectiveVersions versions) {

    this.workflowServiceClass = workflowServiceClass;
    this.method = method;
    this.workflowServiceBean = workflowServiceBean;
    this.binders = binders;
    this.endEventId = endEventId;
    this.versions = versions.versions();
    this.versionsInheritedFrom = versions.inheritedFrom();

  }

  public String getEndEventId() {

    return endEventId;

  }

  /**
   * @return The method, for guiding messages
   */
  public String describe() {

    return "%s#%s".formatted(workflowServiceClass.getName(), method.getName());

  }

  /**
   * @return What this handler is wired to, for guiding messages
   */
  public String describeWiring() {

    return endEventId == null
        ? "every end of the workflow"
        : "end event '%s'".formatted(endEventId);

  }

  boolean matchesEndEvent(
      final String eventId) {

    // a BPMS reporting no end event id serves the methods which asked for none
    return (endEventId == null) || endEventId.equals(eventId);

  }

  /**
   * The version specification(s) this method names, for messages about a method
   * serving no version the BPMS holds.
   *
   * @return The specifications, comma separated
   */
  String describeVersions() {

    return versions
        .stream()
        .map(VersionRange::toString)
        .map("'%s'"::formatted)
        .collect(java.util.stream.Collectors.joining(", "));

  }

  /**
   * The version specification(s) plus, where the method named none itself, the
   * declaration they came from. Every message about an ambiguity or a method serving
   * nothing uses this: a complaint about a range the reader cannot see in front of the
   * method reads like a defect of VanillaBP.
   *
   * @return The specifications and their origin
   */
  String describeVersionsWithOrigin() {

    return versionsInheritedFrom == null
        ? describeVersions()
        : "%s, inherited from %s".formatted(describeVersions(), versionsInheritedFrom);

  }

  /**
   * @return Whether this handler serves the range of its class rather than one of its
   *         own
   */
  boolean inheritsVersions() {

    return versionsInheritedFrom != null;

  }

  /**
   * Where the range came from, ready to be appended to a description of the method -
   * empty where the method names its range itself.
   *
   * @return The clause to append, or an empty string
   */
  String describeVersionOrigin() {

    return versionsInheritedFrom == null
        ? ""
        : ", which inherits the range of %s".formatted(versionsInheritedFrom);

  }

  boolean matchesVersion(
      final String processVersion) {

    return matchesVersion(processVersion, VersionRange.NO_RESOLVER);

  }

  boolean matchesVersion(
      final String processVersion,
      final VersionRange.ProcessVersionResolver resolver) {

    return versions
        .stream()
        .anyMatch(version -> version.matches(processVersion, resolver));

  }

  /**
   * Whether this handler and the given one serve at least one common process version -
   * two handlers serving the same end event are ambiguous exactly then.
   *
   * @param other The other handler
   * @param resolver Resolves version tags of the BPMN process both belong to
   * @return Whether both serve a common version
   */
  boolean overlapsVersions(
      final WorkflowEndedHandler other,
      final VersionRange.ProcessVersionResolver resolver) {

    return versions
        .stream()
        .anyMatch(version -> other.versions
            .stream()
            .anyMatch(otherVersion -> version.overlaps(otherVersion, resolver)));

  }

  /**
   * @return The version tags this handler's version specifications name
   */
  List<String> versionTags() {

    return versions
        .stream()
        .flatMap(version -> version.versionTags().stream())
        .distinct()
        .toList();

  }

  /**
   * Invokes the method with bound parameters. Runtime exceptions propagate - the
   * transaction rolls back and the BPMS applies its retry semantics.
   *
   * @param workflowAggregate The workflow aggregate of the ended workflow
   * @param context The adapter's notification
   */
  void invoke(
      final Object workflowAggregate,
      final WorkflowEndedContext context) {

    final var handlerContext = HandlerContexts.of(workflowAggregate, context, java.util.Map.of());
    final var arguments = binders
        .stream()
        .map(binder -> binder.valueFor(handlerContext))
        .toArray();
    try {
      method.invoke(workflowServiceBean.get(), arguments);
    } catch (final IllegalAccessException e) {
      throw new IllegalStateException(
          "Could not invoke @WorkflowEnded method '%s'!".formatted(describe()), e);
    } catch (final InvocationTargetException e) {
      if (e.getTargetException() instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IllegalStateException(
          "The @WorkflowEnded method '%s' threw a checked exception!".formatted(describe()), e.getTargetException());
    }

  }

}
