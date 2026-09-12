package io.vanillabp.migration.test.scoping;

import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.AdapterProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.adapter.migration.scoping.NameClashAvoidanceService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.DeployedProcess;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Two BPMN processes of one application which reach the BPMS under one identifier. A
 * deployment is per workflow module, so the two sides of such a clash arrive in two separate
 * calls, and the order of the modules is not the application's choice: every case here is
 * driven from both sides where the order could matter.
 * <p>
 * The mode decides what an equal pair of strings means, which is why there is a case per
 * mode rather than one for all of them. Under <code>by-adapter</code> nothing is prefixed and
 * the BPMS is supposed to keep the modules apart, so the adapter is asked and a correct
 * application must not be refused.
 */
@ExtendWith(SuppressOutputExtension.class)
public class CollidingProcessIdsAcrossWorkflowModulesTest {

  private static final String LOANS = "loan-approval";

  private static final String PAYMENTS = "payment-handling";

  private static final String PROCESS = "RiskAssessment";

  private static final String ADAPTER = "c7";

  private static final String SECOND_ADAPTER = "c7-new";

  /**
   * The configuration of one adapter id: a mode at the adapter level, modes per workflow
   * module and modes per workflow of a module.
   */
  private static MigrationAdapterProperties configuration(
      final NameClashAvoidance adapterLevel,
      final Map<String, NameClashAvoidance> perWorkflowModule,
      final Map<String, Map<String, NameClashAvoidance>> perWorkflow) {

    return configuration(List.of(ADAPTER), adapterLevel, perWorkflowModule, perWorkflow);

  }

  private static MigrationAdapterProperties configuration(
      final List<String> adapterIds,
      final NameClashAvoidance adapterLevel,
      final Map<String, NameClashAvoidance> perWorkflowModule,
      final Map<String, Map<String, NameClashAvoidance>> perWorkflow) {

    final var adapters = new LinkedHashMap<String, AdapterConfigProperties>();
    adapterIds
        .forEach(adapterId -> {
          final var adapter = AdapterConfigProperties.ofType("camunda7");
          adapter.setNameClashAvoidance(adapterLevel);
          adapters.put(adapterId, adapter);
        });

    final var modules = new LinkedHashMap<String, WorkflowModuleAdapterProperties>();
    perWorkflowModule
        .forEach((
            moduleId,
            mode) -> modules.put(moduleId, moduleWith(adapterIds, mode, Map.of())));
    perWorkflow
        .forEach((
            moduleId,
            workflows) -> modules
                .put(
                    moduleId,
                    moduleWith(adapterIds, perWorkflowModule.get(moduleId), workflows)));

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(adapters)
        .prioritizedAdapters(adapterIds)
        .workflowModules(modules)
        .build();
    properties.validateAndLink();
    return properties;

  }

  private static WorkflowModuleAdapterProperties moduleWith(
      final List<String> adapterIds,
      final NameClashAvoidance moduleLevel,
      final Map<String, NameClashAvoidance> perWorkflow) {

    final var module = new WorkflowModuleAdapterProperties();
    module.setAdapters(adaptersWith(adapterIds, moduleLevel));
    final var workflows = new LinkedHashMap<String, WorkflowAdapterProperties>();
    perWorkflow
        .forEach((
            bpmnProcessId,
            mode) -> {
          final var workflow = new WorkflowAdapterProperties();
          workflow.setAdapters(adaptersWith(adapterIds, mode));
          workflows.put(bpmnProcessId, workflow);
        });
    module.setWorkflows(workflows);
    return module;

  }

  private static Map<String, AdapterProperties> adaptersWith(
      final List<String> adapterIds,
      final NameClashAvoidance mode) {

    final var adapters = new LinkedHashMap<String, AdapterProperties>();
    adapterIds
        .forEach(adapterId -> {
          final var adapter = new AdapterProperties();
          adapter.setNameClashAvoidance(mode);
          adapters.put(adapterId, adapter);
        });
    return adapters;

  }

  /**
   * A boot of an application whose adapter is not known to the core, which is every
   * platform-free test: the modes are configuration and need nobody to resolve them.
   */
  private static NameClashAvoidanceService bootWith(
      final NameClashAvoidance adapterLevel) {

    return new NameClashAvoidanceService(configuration(adapterLevel, Map.of(), Map.of()));

  }

  /**
   * An adapter which answers the isolation question as told. Each boot gets its own, so the
   * count of questions belongs to that boot.
   */
  @SuppressWarnings("unchecked")
  private static AdapterDeploymentService<Object, Object> adapterWhoseIsolationSeparates(
      final boolean separates) {

    final AdapterDeploymentService<Object, Object> adapter = Mockito.mock(AdapterDeploymentService.class);
    Mockito
        .lenient()
        .when(adapter.getAdapterId())
        .thenReturn(ADAPTER);
    Mockito
        .lenient()
        .when(adapter.ownIsolationSeparatesWorkflowModules(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(separates);
    return adapter;

  }

  private static NameClashAvoidanceService bootWith(
      final NameClashAvoidance adapterLevel,
      final AdapterDeploymentService<Object, Object> adapter) {

    return new NameClashAvoidanceService(
        configuration(adapterLevel, Map.of(), Map.of()), () -> List.of(adapter));

  }

  /**
   * One workflow module being deployed, with the processes it hands over.
   */
  private static void deploys(
      final NameClashAvoidanceService boot,
      final String adapterId,
      final String workflowModuleId,
      final String... bpmnProcessIds) {

    boot
        .validateNoCollidingProcessIds(
            adapterId,
            java.util.Arrays
                .stream(bpmnProcessIds)
                .map(bpmnProcessId -> new DeployedProcess(workflowModuleId, bpmnProcessId))
                .toList());

  }

  private static String refusalOf(
      final Runnable deployment) {

    return assertThrowsExactly(IllegalStateException.class, deployment::run).getMessage();

  }

  private static void namesBothSides(
      final String message) {

    assertTrue(message.contains("'%s'".formatted(LOANS)), () -> message);
    assertTrue(message.contains("'%s'".formatted(PAYMENTS)), () -> message);
    assertTrue(message.contains("'%s'".formatted(PROCESS)), () -> message);

  }

  @Test
  @DisplayName("Two workflow modules sharing a BPMN process id under 'none' end the boot, whichever deploys first")
  public void twoModulesSharingAProcessIdUnderNone() {

    final var loansFirst = refusalOf(
        () -> {
          final var boot = bootWith(NameClashAvoidance.NONE);
          deploys(boot, ADAPTER, LOANS, PROCESS);
          deploys(boot, ADAPTER, PAYMENTS, PROCESS);
        });
    namesBothSides(loansFirst);
    assertTrue(loansFirst.contains("mode 'none'"), () -> loansFirst);
    assertTrue(
        loansFirst.contains("vanillabp.adapters.c7.name-clash-avoidance"),
        () -> loansFirst);

    // the order of the workflow modules is not the application's choice, so the refusal may
    // not depend on it
    final var paymentsFirst = refusalOf(
        () -> {
          final var boot = bootWith(NameClashAvoidance.NONE);
          deploys(boot, ADAPTER, PAYMENTS, PROCESS);
          deploys(boot, ADAPTER, LOANS, PROCESS);
        });
    namesBothSides(paymentsFirst);

  }

  @Test
  @DisplayName("Under 'by-adapter' an adapter which separates the two modules lets the boot pass")
  public void byAdapterWhereTheIsolationSeparates() {

    final var adapter = adapterWhoseIsolationSeparates(true);
    final var boot = bootWith(NameClashAvoidance.BY_ADAPTER, adapter);

    deploys(boot, ADAPTER, LOANS, PROCESS);
    deploys(boot, ADAPTER, PAYMENTS, PROCESS);

    // this is the application the naive widening would have refused: nothing is prefixed
    // under the default mode, so the core sees one string twice
    Mockito
        .verify(adapter)
        .ownIsolationSeparatesWorkflowModules(LOANS, PAYMENTS);

  }

  @Test
  @DisplayName("Under 'by-adapter' an adapter which separates nothing ends the boot")
  public void byAdapterWhereTheIsolationSeparatesNothing() {

    final var boot = bootWith(NameClashAvoidance.BY_ADAPTER, adapterWhoseIsolationSeparates(false));

    final var refusal = refusalOf(
        () -> {
          deploys(boot, ADAPTER, LOANS, PROCESS);
          deploys(boot, ADAPTER, PAYMENTS, PROCESS);
        });

    namesBothSides(refusal);
    assertTrue(refusal.contains("mode 'by-adapter'"), () -> refusal);
    // the way out of this one is a scope per module, not a rename
    assertTrue(refusal.contains("vanillabp.adapters.c7.tenant-id"), () -> refusal);

  }

  @Test
  @DisplayName("An adapter which answers nothing is treated like a BPMS without isolation")
  public void anAdapterAnsweringTheDefault() {

    // the interface's own answer, not a stub: what an adapter written before the question
    // existed says is 'my isolation separates nothing'
    @SuppressWarnings("unchecked")
    final AdapterDeploymentService<Object, Object> adapter = Mockito
        .mock(AdapterDeploymentService.class, Mockito.CALLS_REAL_METHODS);
    Mockito
        .lenient()
        .doReturn(ADAPTER)
        .when(adapter)
        .getAdapterId();
    final var boot = bootWith(NameClashAvoidance.BY_ADAPTER, adapter);

    final var refusal = refusalOf(
        () -> {
          deploys(boot, ADAPTER, LOANS, PROCESS);
          deploys(boot, ADAPTER, PAYMENTS, PROCESS);
        });

    namesBothSides(refusal);

  }

  @Test
  @DisplayName("A prefixed form of one module meets the plain id of another under 'none'")
  public void aPrefixedFormMeetsAPlainId() {

    // module 'a' prefixes its process 'b' to 'a__b', and the other module deploys a process
    // which is literally called 'a__b' without any scoping at all
    final var boot = new NameClashAvoidanceService(
        configuration(NameClashAvoidance.NONE, Map.of("a", NameClashAvoidance.USE_PREFIX), Map.of()));

    final var refusal = refusalOf(
        () -> {
          deploys(boot, ADAPTER, "a", "b");
          deploys(boot, ADAPTER, PAYMENTS, "a__b");
        });

    assertTrue(refusal.contains("reach the BPMS as 'a__b'"), () -> refusal);
    // a mixed configuration is what produced it, so the message names a mode per side
    assertTrue(refusal.contains("mode 'use-prefix'"), () -> refusal);
    assertTrue(refusal.contains("mode 'none'"), () -> refusal);
    assertTrue(
        refusal.contains("vanillabp.workflow-modules.a.adapters.c7.name-clash-avoidance"),
        () -> refusal);

  }

  @Test
  @DisplayName("Inside one workflow module a prefixed process meets an unprefixed one, and only a rename helps")
  public void onePrefixedAndOnePlainProcessOfOneModule() {

    // the mode is resolvable per workflow, so one module can prefix one of its processes and
    // leave the next one alone
    final var boot = new NameClashAvoidanceService(
        configuration(
            NameClashAvoidance.NONE,
            Map.of(),
            Map.of("m", Map.of("x", NameClashAvoidance.USE_PREFIX))));

    final var refusal = refusalOf(() -> deploys(boot, ADAPTER, "m", "x", "m__x"));

    assertTrue(refusal.contains("reach the BPMS as 'm__x'"), () -> refusal);
    assertTrue(refusal.contains("only a rename helps"), () -> refusal);
    // nothing a tenant or a prefix could do separates two processes of one module
    assertTrue(!refusal.contains("tenant-id"), () -> refusal);

  }

  @Test
  @DisplayName("Two adapter ids do not see each other's processes")
  public void twoAdapterIdsStayApart() {

    final var boot = new NameClashAvoidanceService(
        configuration(List.of(ADAPTER, SECOND_ADAPTER), NameClashAvoidance.NONE, Map.of(), Map.of()));

    // what a migration looks like: one workflow module deployed to two adapter ids, each of
    // them its own engine or its own scope of one
    deploys(boot, ADAPTER, LOANS, PROCESS);
    deploys(boot, SECOND_ADAPTER, LOANS, PROCESS);
    // and a second module colliding in the first id is still refused there
    final var refusal = refusalOf(() -> deploys(boot, ADAPTER, PAYMENTS, PROCESS));

    namesBothSides(refusal);
    assertTrue(refusal.contains("adapter '%s'".formatted(ADAPTER)), () -> refusal);

  }

  @Test
  @DisplayName("One process handed over twice is not a collision")
  public void theSameProcessTwice() {

    final var boot = bootWith(NameClashAvoidance.NONE);

    // one BPMN file may hold several processes and a module may be handed over once per
    // adapter, so the same pair arrives more than once
    deploys(boot, ADAPTER, LOANS, PROCESS, PROCESS);
    deploys(boot, ADAPTER, LOANS, PROCESS);
    boot.validateNoCollidingProcessIds(ADAPTER, null);
    boot.validateNoCollidingProcessIds(ADAPTER, List.of(new DeployedProcess(null, null)));

  }

  @Test
  @DisplayName("Several collisions of one deployment are reported together")
  public void severalCollisionsAtOnce() {

    final var boot = bootWith(NameClashAvoidance.NONE);
    deploys(boot, ADAPTER, LOANS, PROCESS, "PaymentHandling");

    final var refusal = refusalOf(() -> deploys(boot, ADAPTER, PAYMENTS, PROCESS, "PaymentHandling"));

    assertTrue(refusal.contains("'%s'".formatted(PROCESS)), () -> refusal);
    assertTrue(refusal.contains("'PaymentHandling'"), () -> refusal);
    // one line per collision and one fix, however many of them there are
    assertTrue(refusal.contains("What collides:") && refusal.contains("What to change:"), () -> refusal);

  }

}
