package io.vanillabp.integration.adapter.migration.transaction;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The startup hint about two writers on one workflow aggregate: a BPMN
 * process which can hold more than one token at a time has branches writing the same
 * workflow aggregate, and without a version attribute the branch committing second
 * writes back what it read when it started - silently, since an ORM saves the whole
 * record.
 * <p>
 * Reading a model is the adapter's job (only it can parse its BPMN dialect), deciding
 * what the finding means is the core's: the adapter reports the elements which can put
 * a second token into a running workflow, and the core knows the aggregate class.
 * <p>
 * Which models are read is the second half of the question. The adapter reports what it
 * finds while wiring the model of THIS boot, and the core asks it again about the versions
 * the BPMS still holds with workflows running on them
 * ({@link io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog#concurrentTokenElementsOfVersion}),
 * because an older version with a parallel gateway the new model dropped keeps forking every
 * workflow started before it. The warning names the versions it was drawn from and stays one
 * per BPMN process either way.
 * <p>
 * A persistence which notices the collision silences the hint - it then raises an
 * exception instead of overwriting, which is what {@link AggregateWrite} reports, and
 * whether it notices is the persistence's own answer. So does an adapter which cannot read
 * its models (the Process-Engine-API): it reports nothing, and nothing is guessed from the
 * absence.
 * <p>
 * Why a model which can produce a second token is only warned about, and only where the aggregate
 * has no version attribute, is decision 14 in the repository's DECISIONS.md.
 */
public class ConcurrentTokenCheck {

  private static final Logger log = LoggerFactory.getLogger(ConcurrentTokenCheck.class);

  /**
   * The (workflow module, BPMN process) pairs already reported - the hint is a design
   * message, not a linter running per deployed file.
   */
  private final Set<String> reported = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /**
   * Reports the elements an adapter found in a BPMN process which can put a second
   * token into a running workflow, and warns if that process' workflow aggregate has
   * no version attribute.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowAggregateClass The workflow aggregate's class
   * @param aggregateNoticesASecondWriter What the aggregate's persistence answers about
   *          noticing a concurrent change
   * @param elementIds The BPMN element IDs producing the second token
   */
  public void reportConcurrentTokenElements(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<?> workflowAggregateClass,
      final boolean aggregateNoticesASecondWriter,
      final Collection<String> elementIds) {

    if ((elementIds == null) || elementIds.isEmpty()) {
      return;
    }
    report(
        workflowModuleId,
        bpmnProcessId,
        workflowAggregateClass,
        aggregateNoticesASecondWriter,
        "The BPMN process '%s' of workflow module '%s' can hold more than one token at a time (%s)"
            .formatted(bpmnProcessId, workflowModuleId, describe(elementIds)));

  }

  /**
   * The same finding for the versions the BPMS STILL HOLDS with workflows running on them -
   * a parallel gateway the model of this boot dropped is still forking every workflow which
   * was started before, and those are the workflows which run longest.
   * <p>
   * Still one warning per BPMN process, whichever model carried the elements: the message is
   * about an aggregate which cannot survive two writers, and saying it once per version an
   * application ever deployed would bury it. Which is also why the deployed model speaks
   * first where both would speak - it is the one a developer can still change.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowAggregateClass The workflow aggregate's class
   * @param aggregateNoticesASecondWriter What the aggregate's persistence answers about
   *          noticing a concurrent change
   * @param elementIdsByVersion The elements producing a second token, per version identifier
   *          the BPMS reported
   */
  public void reportConcurrentTokenElementsOfHeldVersions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<?> workflowAggregateClass,
      final boolean aggregateNoticesASecondWriter,
      final java.util.Map<String, Collection<String>> elementIdsByVersion) {

    if ((elementIdsByVersion == null) || elementIdsByVersion.isEmpty()) {
      return;
    }
    final var carryingVersions = elementIdsByVersion
        .entrySet()
        .stream()
        .filter(version -> (version.getValue() != null) && !version.getValue().isEmpty())
        .toList();
    if (carryingVersions.isEmpty()) {
      return;
    }
    final var elementIds = carryingVersions
        .stream()
        .flatMap(version -> version.getValue().stream())
        .toList();
    report(
        workflowModuleId,
        bpmnProcessId,
        workflowAggregateClass,
        aggregateNoticesASecondWriter,
        """
            Version(s) %s of BPMN process '%s' of workflow module '%s', which the BPMS still holds \
            and workflows still run on, can hold more than one token at a time (%s)"""
            .formatted(
                carryingVersions
                    .stream()
                    .map(java.util.Map.Entry::getKey)
                    .collect(Collectors.joining("', '", "'", "'")),
                bpmnProcessId,
                workflowModuleId,
                describe(elementIds)));

  }

  /**
   * The warning itself, once per BPMN process, given the clause saying WHICH model can hold
   * two tokens - the model of this boot or the versions the BPMS still holds.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowAggregateClass The workflow aggregate's class
   * @param aggregateNoticesASecondWriter What the aggregate's persistence answers about
   *          noticing a concurrent change
   * @param whatCanHoldTwoTokens The clause the warning opens with
   */
  private void report(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<?> workflowAggregateClass,
      final boolean aggregateNoticesASecondWriter,
      final String whatCanHoldTwoTokens) {

    if (workflowAggregateClass == null) {
      return;
    }
    if (aggregateNoticesASecondWriter) {
      return;
    }
    if (!reported.add(workflowModuleId
        + "#"
        + bpmnProcessId)) {
      return;
    }

    log
        .warn(
            """
                {}, but its workflow aggregate '{}' has no version attribute (@Version): two \
                branches load the aggregate, change different things and save it - and since the \
                persistence layer writes the whole record, whatever the branch committing first \
                changed is lost without any error. Ways out: one entity per phase of the workflow, \
                @DynamicUpdate where the branches write different attributes, a version attribute \
                plus a retry in the transaction your application opens, or an additive relation \
                instead of a mutated attribute. The wiki page 'Workflow aggregates' compares them. \
                A version attribute turns the collision into an exception VanillaBP reports and the \
                BPMS retries, which is why this message is about its absence.""",
            whatCanHoldTwoTokens,
            workflowAggregateClass.getName());

  }

  /**
   * The element ids as the warning names them, free of duplicates and in a stable order.
   */
  private static String describe(
      final Collection<String> elementIds) {

    return elementIds
        .stream()
        .distinct()
        .sorted()
        .collect(Collectors.joining("', '", "e.g. '", "'"));

  }

}
