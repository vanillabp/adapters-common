package io.vanillabp.bpmsdouble;

/**
 * The names the BPMS double answers to in an application's configuration. They are
 * the same on both platforms and they are part of what this artifact promises: a
 * test which writes <code>type: dummy</code> or switches
 * {@link #PROPERTY_AT_LEAST_ONCE_DELIVERY} on keeps working across releases (see
 * the module's readme).
 */
public final class DummyAdapter {

  /**
   * The adapter type an application configures to get this double, at
   * <code>vanillabp.adapters.&lt;id&gt;.type</code>. Any number of adapter ids may
   * carry it, which is how a test plays the migration from one BPMS to another.
   */
  public static final String ADAPTER_TYPE = "dummy";

  /**
   * Makes the double report the task delivery of a BPMS which repeats a task it did
   * not learn the outcome of, so the tests of the delivery record and of a
   * {@link io.vanillabp.integration.spi.PhaseTwoOutbox} see the behaviour they are
   * written for.
   */
  public static final String PROPERTY_AT_LEAST_ONCE_DELIVERY = "dummy-adapter.at-least-once-delivery";

  /**
   * Makes the double READ the workflow aggregate in phase two, the way an adapter of
   * a remote BPMS does because it builds the variables it sends out of the aggregate.
   * Off by default: most test doubles of
   * {@link io.vanillabp.integration.spi.AggregatePersistenceAware} implement nothing
   * but save.
   */
  public static final String PROPERTY_READ_AGGREGATE_IN_PHASE_TWO = "dummy-adapter.read-aggregate-in-phase-two";

  private DummyAdapter() {
  }

}
