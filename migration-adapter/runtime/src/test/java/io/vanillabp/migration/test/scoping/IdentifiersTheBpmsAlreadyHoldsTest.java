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
import io.vanillabp.integration.adapter.migration.config.AdapterProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.adapter.migration.scoping.NameClashAvoidanceService;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.IdentifierHeldElsewhere;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ScopedIdentifierKind;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What an adapter reports about the identifiers its BPMS already held, and what a
 * developer reads about it: the scoped form the BPMS sees, the mode and property which
 * produced it, the holder as the adapter described it, and the change which frees the
 * name. A finding never ends a boot, because the holder may be an application which runs
 * correctly.
 */
@ExtendWith(SuppressOutputExtension.class)
public class IdentifiersTheBpmsAlreadyHoldsTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "RiskAssessment";

  private static final String ADAPTER = "c7";

  private static NameClashAvoidanceService serviceWith(
      final NameClashAvoidance adapterLevel,
      final NameClashAvoidance moduleLevel) {

    final var adapter = AdapterConfigProperties.ofType("camunda7");
    adapter.setNameClashAvoidance(adapterLevel);

    final var module = new WorkflowModuleAdapterProperties();
    final var moduleAdapter = new AdapterProperties();
    moduleAdapter.setNameClashAvoidance(moduleLevel);
    module.setAdapters(Map.of(ADAPTER, moduleAdapter));

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, adapter))
        .prioritizedAdapters(List.of(ADAPTER))
        .workflowModules(Map.of(MODULE, module))
        .build();
    properties.validateAndLink();
    return new NameClashAvoidanceService(properties);

  }

  /**
   * Everything logged while the given findings are reported - the message is the whole
   * feature, so it is read rather than mocked.
   */
  private static List<ILoggingEvent> reportOf(
      final NameClashAvoidanceService testee,
      final List<IdentifierHeldElsewhere> found) {

    final var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    final var recorded = new ListAppender<ILoggingEvent>();
    recorded.start();
    root.addAppender(recorded);
    try {
      testee.reportIdentifiersTheBpmsAlreadyHolds(ADAPTER, MODULE, found);
    } finally {
      root.detachAppender(recorded);
    }
    return recorded.list;

  }

  private static String warningOf(
      final NameClashAvoidanceService testee,
      final List<IdentifierHeldElsewhere> found) {

    final var recorded = reportOf(testee, found);
    assertEquals(1, recorded.size(), () -> "one warning per workflow module and adapter, but was "
        + recorded);
    final var warning = recorded.getFirst();
    assertEquals(Level.WARN, warning.getLevel(), "a holder may be an application which runs correctly");
    return warning.getFormattedMessage();

  }

  @Test
  @DisplayName("Every kind of identifier is named with the form its BPMS sees")
  public void everyKindIsNamedWithItsScopedForm() {

    final var reported = warningOf(
        serviceWith(NameClashAvoidance.USE_PREFIX, null),
        List.of(
            new IdentifierHeldElsewhere(ScopedIdentifierKind.BPMN_PROCESS_ID, PROCESS, null, "another deployment", true),
            new IdentifierHeldElsewhere(
                ScopedIdentifierKind.MESSAGE_NAME, "PaymentReceived", null, "another deployment", true),
            new IdentifierHeldElsewhere(
                ScopedIdentifierKind.ERROR_CODE, "PAYMENT_FAILED", null, "another deployment", true),
            new IdentifierHeldElsewhere(
                ScopedIdentifierKind.TASK_DEFINITION, "scoreApplicant", PROCESS, "another deployment", true),
            new IdentifierHeldElsewhere(ScopedIdentifierKind.DMN_DECISION_ID, "scoring", null, "another deployment", true)));

    assertTrue(reported.contains(MODULE), reported);
    assertTrue(reported.contains("BPMN process id 'RiskAssessment'"), reported);
    assertTrue(reported.contains("'loan-approval__RiskAssessment'"), reported);
    // the kinds are named in the words a developer sees in a modeller, so a line says
    // which of the five module-wide names it is about
    assertTrue(reported.contains("message name 'PaymentReceived'"), reported);
    assertTrue(reported.contains("'loan-approval__PaymentReceived'"), reported);
    assertTrue(reported.contains("BPMN error code 'PAYMENT_FAILED'"), reported);
    assertTrue(reported.contains("'loan-approval__PAYMENT_FAILED'"), reported);
    // a task definition is the one kind scoped by its BPMN process as well, so the
    // warning names the process it belongs to
    assertTrue(reported.contains("task definition 'scoreApplicant' of BPMN process 'RiskAssessment'"), reported);
    assertTrue(reported.contains("'loan-approval__RiskAssessment__scoreApplicant'"), reported);
    // a decision is called by several processes of a module, so it is scoped by the
    // module alone
    assertTrue(reported.contains("DMN decision id 'scoring'"), reported);
    assertTrue(reported.contains("'loan-approval__scoring'"), reported);
    assertTrue(reported.contains("another deployment"), reported);

  }

  @Test
  @DisplayName("A finding the adapter cannot prove foreign says so in the same line")
  public void aFindingWhichMayBeOurOwnSaysSo() {

    final var provable = warningOf(
        serviceWith(NameClashAvoidance.USE_PREFIX, null),
        List.of(
            new IdentifierHeldElsewhere(
                ScopedIdentifierKind.BPMN_PROCESS_ID, PROCESS, null, "a deployment of another source", true)));
    assertFalse(provable.contains("cannot tell"), provable);

    // a reader who cannot see which of the two they got learns to ignore the whole
    // message, so the uncertainty belongs into the line it applies to
    final var unprovable = warningOf(
        serviceWith(NameClashAvoidance.USE_PREFIX, null),
        List.of(
            new IdentifierHeldElsewhere(
                ScopedIdentifierKind.BPMN_PROCESS_ID, PROCESS, null, "a definition of another resource name", false)));
    assertTrue(
        unprovable.contains("cannot tell this from an earlier deployment of this application"),
        unprovable);
    assertTrue(unprovable.contains("may be harmless"), unprovable);

  }

  @Test
  @DisplayName("Ten findings are one warning, one line each")
  public void manyFindingsAreOneWarningWithALinePerFinding() {

    final var found = java.util.stream.IntStream
        .range(0, 10)
        .mapToObj(
            number -> new IdentifierHeldElsewhere(
                ScopedIdentifierKind.BPMN_PROCESS_ID, "Process%d".formatted(number), null, "deployment 'other-%d'"
                    .formatted(number), true))
        .toList();

    final var reported = warningOf(serviceWith(NameClashAvoidance.USE_PREFIX, null), found);

    assertEquals(
        10,
        reported
            .lines()
            .filter(line -> line.contains("is already held by"))
            .count(),
        () -> reported);
    assertTrue(reported.contains("got 10 of them back"), reported);
    // the fix is the same for all ten, so it is written once
    assertEquals(
        1,
        reported
            .lines()
            .filter(line -> line.contains("where the mode is"))
            .count(),
        () -> reported);

  }

  @Test
  @DisplayName("A BPMS holding nothing of ours produces no line at all")
  public void aBpmsHoldingNothingIsSilent() {

    final var testee = serviceWith(NameClashAvoidance.USE_PREFIX, null);

    assertTrue(reportOf(testee, List.of()).isEmpty(), "an empty answer is an answer");
    assertTrue(reportOf(testee, null).isEmpty(), "an adapter which cannot ask its BPMS reports nothing");

  }

  @Test
  @DisplayName("Each mode names its own way out, and the property which set it")
  public void theFixDiffersPerMode() {

    final var found = List
        .of(new IdentifierHeldElsewhere(ScopedIdentifierKind.BPMN_PROCESS_ID, PROCESS, null, "a deployment", true));

    final var prefixing = warningOf(serviceWith(NameClashAvoidance.USE_PREFIX, null), found);
    assertTrue(prefixing.contains("mode 'use-prefix'"), prefixing);
    assertTrue(prefixing.contains("vanillabp.adapters.c7.name-clash-avoidance"), prefixing);
    assertTrue(prefixing.contains("Rename workflow module 'loan-approval'"), prefixing);

    final var unscoped = warningOf(serviceWith(NameClashAvoidance.NONE, null), found);
    assertTrue(unscoped.contains("mode 'none'"), unscoped);
    assertTrue(unscoped.contains("vanillabp.adapters.c7.name-clash-avoidance: use-prefix"), unscoped);
    assertTrue(unscoped.contains("vanillabp.adapters.c7.name-clash-avoidance: by-adapter"), unscoped);

    final var isolated = warningOf(serviceWith(NameClashAvoidance.BY_ADAPTER, null), found);
    assertTrue(isolated.contains("mode 'by-adapter'"), isolated);
    assertTrue(isolated.contains("vanillabp.adapters.c7.tenant-id"), isolated);

    // the mode is resolvable per workflow module, and the warning names the level which
    // decided rather than the one the developer would have to add
    final var perModule = warningOf(serviceWith(NameClashAvoidance.NONE, NameClashAvoidance.USE_PREFIX), found);
    assertTrue(
        perModule.contains("vanillabp.workflow-modules.loan-approval.adapters.c7.name-clash-avoidance"),
        perModule);

    // ... and where nothing is configured at all it says which default applies
    final var unconfigured = warningOf(new NameClashAvoidanceService(null), found);
    assertTrue(unconfigured.contains("nothing is configured"), unconfigured);

  }

  @Test
  @DisplayName("Nothing a report can carry ends the boot")
  public void aReportNeverFailsTheBoot() {

    final var testee = serviceWith(NameClashAvoidance.USE_PREFIX, null);

    // an adapter holding back parts of a finding, a module nobody configured, a kind
    // without an identifier: a diagnostic must not be the reason a deployment fails
    final var recorded = reportOf(
        testee,
        java.util.Arrays
            .asList(
                null,
                new IdentifierHeldElsewhere(ScopedIdentifierKind.BPMN_PROCESS_ID, null, null, null, true),
                new IdentifierHeldElsewhere(null, PROCESS, null, null, false),
                new IdentifierHeldElsewhere(ScopedIdentifierKind.TASK_DEFINITION, "scoreApplicant", null, "  ", false)));

    assertEquals(1, recorded.size(), () -> "the findings which can be worded are, the others are dropped: "
        + recorded);
    assertEquals(Level.WARN, recorded.getFirst().getLevel(), "a finding is never an error");
    final var reported = recorded
        .getFirst()
        .getFormattedMessage();
    assertTrue(reported.contains("task definition 'scoreApplicant'"), reported);
    assertTrue(reported.contains("cannot describe any further"), reported);

  }

}
