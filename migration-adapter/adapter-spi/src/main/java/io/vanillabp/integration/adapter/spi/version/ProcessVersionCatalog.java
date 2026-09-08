package io.vanillabp.integration.adapter.spi.version;

import java.util.List;

/**
 * What a BPMS knows about the deployed versions of a BPMN process - implemented by an
 * adapter whose BPMS can tell, handed to the core during <code>wireBpmn</code> using
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#registerProcessVersions}.
 * <p>
 * The core needs it only for version SPECIFICATIONS naming a version TAG
 * (<code>&#64;WorkflowTask(version = "release-2024")</code>, <code>version = "&gt;v1.4"</code>):
 * a specification consisting of numbers is compared to the version the BPMS reported
 * without asking anybody. That is why an adapter of a BPMS which cannot report tags
 * simply registers nothing - version specifications made of numbers keep working, and
 * a specification naming a tag matches nothing and is reported once with a guiding
 * message.
 * <p>
 * <b>Both directions are expected to be cheap:</b> {@link #resolveVersion} is called
 * while a task is dispatched, so an implementation caches what it learned and asks the
 * BPMS only for a version it has not seen yet. That case is real: during a rolling
 * deployment another cluster node may already have deployed a version this node does
 * not know, and the BPMS delivers a task of it before this node deploys. See
 * {@link CachingProcessVersionCatalog}, which implements exactly that and leaves the
 * BPMS query to the adapter.
 * <p>
 * Why an adapter answers two questions here instead of running the check for old process versions
 * itself is decision 15 in the repository's DECISIONS.md.
 */
public interface ProcessVersionCatalog {

  /**
   * All versions of the given process the BPMS knows, ordered by deployment, oldest
   * first. Called once per process at startup (the deployment pipeline has finished
   * then, so the version deployed by this very boot is included) to resolve the
   * version tags the application's annotations name.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @return The deployed versions, oldest first; empty if the BPMS cannot tell
   */
  List<DeployedProcessVersion> deployedVersionsOf(
      String workflowModuleId,
      String bpmnProcessId);

  /**
   * The version identified by a version identifier the BPMS reported, or the newest
   * version carrying the given version tag.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param versionOrVersionTag A version identifier or a version tag
   * @return The version or <code>null</code> if the BPMS does not know it
   */
  DeployedProcessVersion resolveVersion(
      String workflowModuleId,
      String bpmnProcessId,
      String versionOrVersionTag);

  /**
   * The tasks of ONE deployed version of a BPMN process, read from the model the BPMS
   * still holds - what the startup check for old process versions needs to tell whether
   * the application still serves one. The specs are built exactly like the
   * ones handed to
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#validateTaskWiring},
   * so both directions of the wiring speak about the same thing.
   * <p>
   * Reading a model is BPMS-specific and not every BPMS can do it: an adapter which
   * cannot returns <code>null</code>, which switches the check off for that adapter
   * instead of pretending the version is fine. An adapter which CAN read models but
   * finds nothing for that version returns an empty collection.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param version The version identifier the BPMS reported
   * @return The tasks of that version, or <code>null</code> if this BPMS cannot say
   */
  default java.util.Collection<io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec> tasksOfVersion(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    return null;

  }

  /**
   * The start events a BPMS fires on its own - a timer, a signal, a condition - in ONE
   * deployed version of a BPMN process, read from the model the BPMS still holds. The
   * sibling of {@link #tasksOfVersion} for the other direction of the wiring: the specs
   * are built exactly like the ones handed to
   * {@link io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker#validateBpmsInitiatedStarts},
   * so both speak about the same thing.
   * <p>
   * What the core does with the answer is judge the
   * <code>&#64;WorkflowStartedByBpms</code> methods of a BPMN process id the application
   * DECLARES without deploying a model for it - the id a renamed process left behind.
   * Nothing wires such an id during this boot, so those methods are judged by nothing
   * today, while the BPMS may well fire the old model's timer every day. A method naming
   * a start event no held version has is then said out loud instead of silently never
   * running.
   * <p>
   * Reading a model is BPMS-specific and not every BPMS can do it: an adapter which
   * cannot returns <code>null</code>, and the core stays silent about that id rather
   * than judging it by an answer it does not have. An adapter which CAN read models but
   * finds no such start event in that version returns an empty collection.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param version The version identifier the BPMS reported
   * @return The BPMS-initiated start events of that version, or <code>null</code> if
   *         this BPMS cannot say
   */
  default java.util.Collection<io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec> startEventsOfVersion(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    return null;

  }

  /**
   * How many workflows still run on ONE deployed version - what decides whether an
   * unserved task definition of that version is a warning or a defect, and whether
   * outfading it (<code>outfaded-versions</code>) leaves workflows behind.
   * <p>
   * A BPMS which cannot be asked returns <code>null</code>, and the core says so once
   * with a guiding message rather than turning an unanswerable question into a boot
   * failure.
   * <p>
   * The BPMS does the counting: a <code>count()</code> on an engine, the total of a search
   * on a cluster. Fetching the workflows and counting what came back answers the same
   * question and makes the boot slower every year the application runs, which is what
   * decision 19 in the repository's DECISIONS.md forbids. The core asks once per version
   * and per boot, so an adapter needs no cache of its own.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param version The version identifier the BPMS reported
   * @return The number of active workflows of that version, or <code>null</code> if
   *         this BPMS cannot say
   */
  default Long activeInstanceCountOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    return null;

  }

  /**
   * What a workflow running on an OLDER version of this process does not get, in words
   * an operator can act on - named by the adapter, because only it knows what it
   * attaches and how.
   * <p>
   * The question exists because the two ways an adapter can bring VanillaBP's behaviour
   * to a workflow differ exactly here. An adapter which attaches while the engine PARSES
   * a process definition reaches every version its BPMS holds, so nothing is missing and
   * it answers <code>null</code>. An adapter which writes into the MODEL it deploys
   * reaches the version it deployed and no earlier one, so it names what those workflows
   * will never get.
   * <p>
   * Asked once per BPMN process at startup and only where workflows really do run on an
   * older version. The default answers <code>null</code>, which keeps the report to the
   * bare count.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @return A sentence naming what is missing, or <code>null</code> where nothing is
   */
  default String whatOlderVersionsMiss(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return null;

  }

}
