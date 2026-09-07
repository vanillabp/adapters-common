package io.vanillabp.integration.adapter.spi;

import java.util.List;
import java.util.Map;

/**
 * Turns a workflow aggregate into the values shared with the BPMS, honoring
 * {@code @SyncWithBPMS}/{@code @NoSyncWithBPMS} and the adapter's own default
 * ({@link AggregateSyncMode}). Implemented ONCE by the core (the model is
 * BPMS-neutral) and handed to every adapter by the platform integration.
 * <p>
 * Every adapter pushes the values as process variables at every sync point (instance
 * creation, task completion, message correlation, user-task completion), an embedded
 * BPMS included: an engine evaluates its models against its own variables, so a
 * model reading anything else would work on one BPMS and fail on the next. Camunda 7
 * read the aggregate live once and does not any more.
 * <p>
 * <b>The workflow aggregate's ID is never part of these values</b> - how a BPMS
 * identifies the workflow is the adapter's concern (Camunda 7: the business key;
 * Camunda 8 / Process-Engine-API: a process variable named after the aggregate's
 * ID property, see {@code AggregatePersistenceAware#getAggregateIdName()}). That
 * variable is technical and is ALWAYS set, no matter what the sync model says: an
 * aggregate annotated {@code @NoSyncWithBPMS} would otherwise be unaddressable. Both
 * halves are held, the first by {@code WorkflowTaskRegistryTest} of the migration
 * adapter, the second by {@code PeaSharedValuesTest} respectively
 * {@code Camunda8SharedValuesTest} of the adapters storing the ID in a variable.
 * <p>
 * What decides which values of an aggregate leave for the BPMS, and why the variable named after
 * the id attribute travels regardless, is decision 10 in the repository's DECISIONS.md.
 */
public interface WorkflowAggregateSync {

  /**
   * The values of the given workflow aggregate shared with the BPMS.
   *
   * @param workflowAggregate The workflow aggregate (may be <code>null</code>)
   * @param adapterDefault The adapter's default for aggregates carrying no
   *          annotation of their own
   * @return The values by attribute name - possibly empty, never
   *         <code>null</code>; nested objects are maps, collections are lists
   */
  Map<String, Object> syncedValues(
      Object workflowAggregate,
      AggregateSyncMode adapterDefault);

  /**
   * Validates the sync model of one workflow-aggregate class AND of every type
   * reachable from its attributes: as long as an aggregate carries no annotation at
   * all the adapter decides, but as soon as the application annotates something the
   * intent has to be unambiguous - attributes annotated BOTH ways without the class
   * stating its own mode cannot be interpreted.
   * <p>
   * Called by the PLATFORM INTEGRATION at startup, once per registered
   * workflow-aggregate class (not by adapters): a defect must abort the boot, not
   * surface at the first sync point. Types reachable only at runtime (e.g. a
   * subclass assigned to a supertype attribute, or nested deeper than the model
   * follows) still fail with the same message when they are first shared.
   *
   * @param workflowAggregateClass The workflow-aggregate class (may be
   *          <code>null</code>)
   * @throws IllegalStateException Naming every ambiguous class, its conflicting
   *           attributes and the fix
   */
  void validateSyncModel(
      Class<?> workflowAggregateClass);

  /**
   * Whether the given name is a readable attribute of the workflow-aggregate class -
   * asked about a name a BPMN model reads, so an adapter can tell "the application
   * clearly meant its aggregate" from "this is a variable of the model".
   *
   * @param workflowAggregateClass The workflow-aggregate class (may be
   *          <code>null</code>)
   * @param propertyName The name read by the model
   * @return Whether the class has such a readable attribute
   */
  default boolean isAggregateProperty(
      final Class<?> workflowAggregateClass,
      final String propertyName) {

    return false;

  }

  /**
   * Whether that attribute is SHARED with the BPMS, which is what decides whether an
   * expression reading it finds a value or always <code>null</code>.
   *
   * @param workflowAggregateClass The workflow-aggregate class (may be
   *          <code>null</code>)
   * @param propertyName The attribute's name
   * @param adapterDefault The adapter's default for aggregates carrying no annotation
   *          of their own
   * @return Whether the attribute is shared
   */
  default boolean isSharedWithBpms(
      final Class<?> workflowAggregateClass,
      final String propertyName,
      final AggregateSyncMode adapterDefault) {

    return true;

  }

  /**
   * What an expression reading a PATH of attributes finds in the BPMS -
   * <code>order.customer.vip</code> rather than the single name
   * {@link #isSharedWithBpms} answers about.
   * <p>
   * The values a BPMS holds are a nested structure of plain types, so a model may
   * navigate into them, and everything the single-name question answers about the first
   * segment is true of every segment below it: a segment the sync model keeps back makes
   * the whole expression read <code>null</code>. This walks the DECLARED types of the
   * segments and says where the path stops finding anything.
   * <p>
   * <b>It refuses to answer wherever the declared type cannot decide</b> - a
   * {@link Map}, a raw or wildcard collection, an interface or an abstract type, and
   * anything past the nesting limit the values themselves are cut at. Then the answer is
   * {@link PathVerdict.Kind#UNDECIDABLE}, because a warning about a model which works is
   * worse than a missing one. The walk judges no method call either: what
   * <code>order.total.doubleValue()</code> resolves to depends on the runtime class the
   * BPMS' serialization produced, which is not knowable from a declared type.
   * <p>
   * Why the core answers this and where it stays silent is decision 37 in the repository's
   * DECISIONS.md.
   *
   * @param workflowAggregateClass The workflow-aggregate class (may be
   *          <code>null</code>)
   * @param path The segments of the path, the first one read against the aggregate
   *          itself (may be <code>null</code> or empty)
   * @param adapterDefault The adapter's default for aggregates carrying no annotation
   *          of their own
   * @return What the path finds, never <code>null</code>; the default implementation
   *         decides nothing
   */
  default PathVerdict whatAPathFinds(
      final Class<?> workflowAggregateClass,
      final List<String> path,
      final AggregateSyncMode adapterDefault) {

    return PathVerdict.undecidable();

  }

  /**
   * What a walk along a path of attributes found, and at which segment.
   *
   * @param kind What the path finds
   * @param segment The segment the path stops at, <code>null</code> where it stops
   *          nowhere
   * @param segmentIndex The position of that segment in the path, counted from zero, and
   *          <code>-1</code> where the path stops nowhere
   * @param segmentOwner The simple name of the declared type the segment was read
   *          against, so a message can say whose attribute is missing;
   *          <code>null</code> where the path stops nowhere
   */
  record PathVerdict(
                     Kind kind,
                     String segment,
                     int segmentIndex,
                     String segmentOwner) {

    /**
     * What a path finds.
     */
    public enum Kind {

      /**
       * Every segment is an attribute the BPMS is given, so the expression reads a
       * value.
       */
      SHARED_VALUE,

      /**
       * The declared type the segment was read against has no readable attribute of
       * that name, so the BPMS holds no such member.
       */
      NO_SUCH_ATTRIBUTE,

      /**
       * The segment IS a readable attribute and the sync model keeps it back.
       */
      NOT_SHARED,

      /**
       * The segment before this one travels as a single value, a number or a text, so
       * there is nothing below it to read. An enum arrives as its name and therefore
       * counts as a text.
       */
      NOTHING_BELOW,

      /**
       * The declared types cannot decide, so nothing is claimed.
       */
      UNDECIDABLE

    }

    /**
     * @return Whether the path stops short of a value the BPMS holds, which is what a
     *         check reports
     */
    public boolean pathIsCut() {

      return (kind == Kind.NO_SUCH_ATTRIBUTE) || (kind == Kind.NOT_SHARED) || (kind == Kind.NOTHING_BELOW);

    }

    /**
     * @return The path reaches a value the BPMS holds
     */
    public static PathVerdict aSharedValue() {

      return new PathVerdict(Kind.SHARED_VALUE, null, -1, null);

    }

    /**
     * @return The declared types cannot decide
     */
    public static PathVerdict undecidable() {

      return new PathVerdict(Kind.UNDECIDABLE, null, -1, null);

    }

    /**
     * @param segment The segment which is no attribute
     * @param segmentIndex Its position in the path
     * @param segmentOwner The simple name of the type it was read against
     * @return The path stops because that type has no such attribute
     */
    public static PathVerdict noSuchAttribute(
        final String segment,
        final int segmentIndex,
        final String segmentOwner) {

      return new PathVerdict(Kind.NO_SUCH_ATTRIBUTE, segment, segmentIndex, segmentOwner);

    }

    /**
     * @param segment The unshared attribute
     * @param segmentIndex Its position in the path
     * @param segmentOwner The simple name of the type it was read against
     * @return The path stops because that attribute is not shared
     */
    public static PathVerdict notShared(
        final String segment,
        final int segmentIndex,
        final String segmentOwner) {

      return new PathVerdict(Kind.NOT_SHARED, segment, segmentIndex, segmentOwner);

    }

    /**
     * @param segment The segment which finds nothing
     * @param segmentIndex Its position in the path
     * @param segmentOwner The simple name of the type it was read against, which is the
     *          one travelling as a single value
     * @return The path stops because the value above it carries no members
     */
    public static PathVerdict nothingBelow(
        final String segment,
        final int segmentIndex,
        final String segmentOwner) {

      return new PathVerdict(Kind.NOTHING_BELOW, segment, segmentIndex, segmentOwner);

    }

  }

}
