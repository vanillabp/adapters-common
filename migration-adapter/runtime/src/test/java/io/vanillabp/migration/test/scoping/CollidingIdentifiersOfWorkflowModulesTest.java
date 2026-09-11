package io.vanillabp.migration.test.scoping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.scoping.NameClashAvoidanceService;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ScopedIdentifierKind;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The identifiers two workflow modules of ONE application share: the names the adapter
 * read out of the models it deploys, and the names a version the BPMS still holds carries.
 * Both are warnings which name the two modules, the form the BPMS sees and the way out,
 * and both stay silent where the modules are scoped apart.
 */
@ExtendWith(SuppressOutputExtension.class)
public class CollidingIdentifiersOfWorkflowModulesTest {

  private static final String LOANS = "loan-approval";

  private static final String PAYMENTS = "payment-handling";

  private static final String ADAPTER = "c7";

  private static NameClashAvoidanceService serviceWith(
      final NameClashAvoidance mode) {

    final var adapter = AdapterConfigProperties.ofType("camunda7");
    adapter.setNameClashAvoidance(mode);

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, adapter))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    properties.validateAndLink();
    return new NameClashAvoidanceService(properties);

  }

  private static ModelIdentifier message(
      final String name) {

    return new ModelIdentifier(ScopedIdentifierKind.MESSAGE_NAME, name);

  }

  /**
   * Everything logged while the given block runs - the message is the whole feature, so it
   * is read rather than mocked.
   */
  private static List<ILoggingEvent> recorded(
      final Runnable reporting) {

    final var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    final var recorded = new ListAppender<ILoggingEvent>();
    recorded.start();
    root.addAppender(recorded);
    try {
      reporting.run();
    } finally {
      root.detachAppender(recorded);
    }
    return recorded.list;

  }

  private static String theOnlyWarning(
      final List<ILoggingEvent> events) {

    assertEquals(1, events.size(), () -> "one warning, but was "
        + events);
    assertEquals(Level.WARN, events.getFirst().getLevel(), "an application may have arranged the sharing");
    return events
        .getFirst()
        .getFormattedMessage();

  }

  @Test
  @DisplayName("Two workflow modules declaring one message name are named with both sides")
  public void twoModulesSharingAMessageNameAreReported() {

    final var testee = serviceWith(NameClashAvoidance.NONE);

    final var reported = theOnlyWarning(
        recorded(
            () -> {
              testee.reportIdentifiersTheModelsDeclare(ADAPTER, LOANS, List.of(message("PaymentReceived")));
              testee.reportIdentifiersTheModelsDeclare(ADAPTER, PAYMENTS, List.of(message("PaymentReceived")));
            }));

    assertTrue(reported.contains("message name 'PaymentReceived'"), reported);
    assertTrue(reported.contains("'"
        + LOANS
        + "'"), reported);
    assertTrue(reported.contains("'"
        + PAYMENTS
        + "'"), reported);
    assertTrue(reported.contains("mode 'none'"), reported);
    assertTrue(reported.contains("vanillabp.adapters.c7.name-clash-avoidance"), reported);
    // with nothing scoped the collision is certain, so nothing is hedged
    assertFalse(reported.contains("cannot see whether"), reported);

  }

  @Test
  @DisplayName("Prefixing keeps the modules apart, so there is nothing to report")
  public void prefixingReportsNothing() {

    final var testee = serviceWith(NameClashAvoidance.USE_PREFIX);

    final var events = recorded(
        () -> {
          testee.reportIdentifiersTheModelsDeclare(ADAPTER, LOANS, List.of(message("PaymentReceived")));
          testee.reportIdentifiersTheModelsDeclare(ADAPTER, PAYMENTS, List.of(message("PaymentReceived")));
        });

    assertEquals(List.of(), events, () -> "the scoped forms differ by the module id, but was "
        + events);

  }

  @Test
  @DisplayName("A tenant shared by every module is the case by-adapter has to report")
  public void byAdapterReportsTheSharedTenant() {

    final var testee = serviceWith(NameClashAvoidance.BY_ADAPTER);

    final var reported = theOnlyWarning(
        recorded(
            () -> {
              testee.reportIdentifiersTheModelsDeclare(ADAPTER, LOANS, List.of(message("PaymentReceived")));
              testee.reportIdentifiersTheModelsDeclare(ADAPTER, PAYMENTS, List.of(message("PaymentReceived")));
            }));

    // the fix under by-adapter is the scope the BPMS puts the modules into, not a prefix
    assertTrue(reported.contains("vanillabp.adapters.c7.tenant-id"), reported);
    // and whether that scope really separates them is the adapter's knowledge, so the line
    // says so instead of presenting a question as a verdict
    assertTrue(reported.contains("VanillaBP cannot see whether it does"), reported);

  }

  @Test
  @DisplayName("One workflow module sharing a name across its processes is ordinary")
  public void oneModuleMayShareANameAcrossItsProcesses() {

    final var testee = serviceWith(NameClashAvoidance.NONE);

    // the scope of such a name IS the workflow module: two of its processes waiting for the
    // same message is how a module is built, and reporting it would teach a developer to
    // ignore the message
    final var events = recorded(
        () -> {
          testee
              .reportIdentifiersTheModelsDeclare(
                  ADAPTER,
                  LOANS,
                  List.of(message("PaymentReceived"), message("PaymentReceived")));
          testee.reportIdentifiersTheModelsDeclare(ADAPTER, LOANS, List.of(message("PaymentReceived")));
        });

    assertEquals(List.of(), events, () -> String.valueOf(events));

  }

  @Test
  @DisplayName("A name equal to one of another kind collides with nothing")
  public void kindsAreKeptApart() {

    final var testee = serviceWith(NameClashAvoidance.NONE);

    final var events = recorded(
        () -> {
          testee.reportIdentifiersTheModelsDeclare(ADAPTER, LOANS, List.of(message("PaymentFailed")));
          testee
              .reportIdentifiersTheModelsDeclare(
                  ADAPTER,
                  PAYMENTS,
                  List.of(new ModelIdentifier(ScopedIdentifierKind.ERROR_CODE, "PaymentFailed")));
        });

    assertEquals(List.of(), events, () -> "a BPMS keeps a message name and an error code apart: "
        + events);

  }

  @Test
  @DisplayName("Nothing an adapter can report ends the boot")
  public void reportingNeverFailsTheBoot() {

    final var testee = serviceWith(NameClashAvoidance.NONE);

    final var events = recorded(
        () -> {
          testee.reportIdentifiersTheModelsDeclare(ADAPTER, LOANS, null);
          testee.reportIdentifiersTheModelsDeclare(ADAPTER, LOANS, List.of());
          testee.reportIdentifiersTheModelsDeclare(ADAPTER, null, List.of(message("PaymentReceived")));
          testee
              .reportIdentifiersTheModelsDeclare(
                  ADAPTER,
                  LOANS,
                  java.util.Arrays
                      .asList(
                          null,
                          new ModelIdentifier(null, "PaymentReceived"),
                          new ModelIdentifier(ScopedIdentifierKind.MESSAGE_NAME, null)));
        });

    assertEquals(List.of(), events, () -> String.valueOf(events));

  }

  @Test
  @DisplayName("A held version carrying the name another module deploys now is reported")
  public void aHeldVersionSharingANameIsReported() {

    final var testee = serviceWith(NameClashAvoidance.NONE);
    testee.reportIdentifiersTheModelsDeclare(ADAPTER, LOANS, List.of(message("PaymentReceived")));

    final var reported = theOnlyWarning(
        recorded(
            () -> testee
                .reportIdentifiersOfHeldVersion(
                    ADAPTER,
                    PAYMENTS,
                    "Settlement",
                    "7",
                    3L,
                    List.of(message("PaymentReceived")))));

    assertTrue(reported.contains("Version 7"), reported);
    assertTrue(reported.contains("'Settlement'"), reported);
    assertTrue(reported.contains("'"
        + PAYMENTS
        + "'"), reported);
    assertTrue(reported.contains("'"
        + LOANS
        + "'"), reported);
    // how urgent the line is depends on the workflows still running on that version, and
    // the count is the one the check around it already asked for
    assertTrue(reported.contains("3 workflows still running"), reported);
    assertTrue(reported.contains("message name 'PaymentReceived'"), reported);

  }

  @Test
  @DisplayName("What a held version says about the count is what the message says")
  public void theMessageSaysWhatIsKnownAboutTheWorkflows() {

    final var names = List.of(message("PaymentReceived"));

    final var empty = serviceWith(NameClashAvoidance.NONE);
    empty.reportIdentifiersTheModelsDeclare(ADAPTER, LOANS, names);
    assertTrue(
        theOnlyWarning(
            recorded(
                () -> empty.reportIdentifiersOfHeldVersion(ADAPTER, PAYMENTS, "Settlement", "7", 0L, names)))
            .contains("no workflow left on it"));

    final var one = serviceWith(NameClashAvoidance.NONE);
    one.reportIdentifiersTheModelsDeclare(ADAPTER, LOANS, names);
    assertTrue(
        theOnlyWarning(
            recorded(() -> one.reportIdentifiersOfHeldVersion(ADAPTER, PAYMENTS, "Settlement", "7", 1L, names)))
            .contains("one workflow still running on it"));

    // a BPMS which cannot count says that instead of a number, rather than the line
    // reading as if nobody were on that version
    final var unknown = serviceWith(NameClashAvoidance.NONE);
    unknown.reportIdentifiersTheModelsDeclare(ADAPTER, LOANS, names);
    assertTrue(
        theOnlyWarning(
            recorded(
                () -> unknown.reportIdentifiersOfHeldVersion(ADAPTER, PAYMENTS, "Settlement", "7", null, names)))
            .contains("cannot count the workflows"));

  }

  @Test
  @DisplayName("A held version of the module which still deploys the name is continuity")
  public void aHeldVersionOfTheSameModuleIsQuiet() {

    final var testee = serviceWith(NameClashAvoidance.NONE);
    testee.reportIdentifiersTheModelsDeclare(ADAPTER, LOANS, List.of(message("PaymentReceived")));

    final var events = recorded(
        () -> {
          // the same module, which is its own model carried forward
          testee
              .reportIdentifiersOfHeldVersion(
                  ADAPTER,
                  LOANS,
                  "RiskAssessment",
                  "2",
                  5L,
                  List.of(message("PaymentReceived")));
          // a name nothing deploys today
          testee
              .reportIdentifiersOfHeldVersion(
                  ADAPTER,
                  PAYMENTS,
                  "Settlement",
                  "2",
                  5L,
                  List.of(message("OrderCancelled")));
          // a BPMS which cannot read the held model
          testee.reportIdentifiersOfHeldVersion(ADAPTER, PAYMENTS, "Settlement", "2", 5L, null);
        });

    assertEquals(List.of(), events, () -> String.valueOf(events));

  }

  @Test
  @DisplayName("Prefixed modules share nothing with a held version either")
  public void aHeldVersionOfAPrefixedModuleIsQuiet() {

    final var testee = serviceWith(NameClashAvoidance.USE_PREFIX);
    testee.reportIdentifiersTheModelsDeclare(ADAPTER, LOANS, List.of(message("PaymentReceived")));

    final var events = recorded(
        () -> testee
            .reportIdentifiersOfHeldVersion(
                ADAPTER,
                PAYMENTS,
                "Settlement",
                "7",
                3L,
                List.of(message("PaymentReceived"))));

    assertEquals(List.of(), events, () -> "the held version carries the prefix of its own module: "
        + events);

  }

}
