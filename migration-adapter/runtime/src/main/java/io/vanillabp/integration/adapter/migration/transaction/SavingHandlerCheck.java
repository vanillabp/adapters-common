package io.vanillabp.integration.adapter.migration.transaction;

import java.lang.annotation.Annotation;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The startup hint about the second writer an extension brings: a handler method of the
 * application which an extension may have VanillaBP save after it ran. It reads the
 * workflow aggregate, writes what it reported and is saved, which is one writer more on an
 * aggregate whose workflow already has one.
 * <p>
 * The same finding as {@link ConcurrentTokenCheck}, with a different second writer, so it
 * ends in the same advice and the same page of the wiki. It is kept apart because both can
 * be true of one BPMN process and each of them is worth saying once: one is about the model,
 * which a developer can change, and this one is about a dependency, which they usually
 * cannot.
 * <p>
 * Whether a single call really saves is the caller's decision and is made per call, so a
 * boot can only say that the handler MAY save. An extension asking for a handler which
 * reads says so at the call, and the message would then be about something which never
 * happens - the price of warning about what is allowed rather than about what was observed,
 * and the alternative is to say nothing until a write is already lost.
 * <p>
 * Nothing is asked of a database here, which is what makes this cheap enough for a start:
 * the question goes to the persistence of the aggregate and is answered from the class.
 * <p>
 * Why a second writer is warned about rather than resolved is decision 14 in the
 * repository's DECISIONS.md, and which handlers the warning reaches is decision 45.
 */
public class SavingHandlerCheck {

  private static final Logger log = LoggerFactory.getLogger(SavingHandlerCheck.class);

  /**
   * The (workflow module, BPMN process) pairs already reported. An extension scans a
   * workflow service once per BPMN process it serves, and a process served by two
   * extensions says it once - the advice is the same whichever of them is named.
   */
  private final Set<String> reported = ConcurrentHashMap.newKeySet();

  /**
   * Reports that an extension has handler methods for a BPMN process whose workflow
   * aggregate cannot notice a second writer.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowAggregateClass The workflow aggregate's class
   * @param aggregateNoticesASecondWriter What the aggregate's persistence answers about
   *          noticing a concurrent change
   * @param extensionId The id of the extension owning the handlers
   * @param annotationType The annotation the extension's handlers carry
   */
  public void reportHandlersWhichMaySave(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<?> workflowAggregateClass,
      final boolean aggregateNoticesASecondWriter,
      final String extensionId,
      final Class<? extends Annotation> annotationType) {

    if ((workflowAggregateClass == null) || aggregateNoticesASecondWriter) {
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
                Extension '{}' has @{} method(s) for BPMN process '{}' of workflow module '{}' which \
                may change the workflow aggregate '{}', and that aggregate has nothing which would \
                notice a second writer (a version attribute: @Version with JPA as well as with \
                Spring Data). Such a method reads the aggregate, writes what it reported and is \
                saved - so a change your own code made to the same aggregate in between is written \
                over without any error. Ways out: a version attribute plus a retry in the \
                transaction your application opens, an attribute only the handler writes, or an own \
                entity for what the extension notes down. The wiki page 'Workflow aggregates' shows \
                them in its section 'Two writers on one aggregate', together with the other pairs of \
                writers which meet this way. VanillaBP neither checks for the conflict nor repeats \
                the report - a version attribute is what turns the silent loss into an exception it \
                can report.""",
            extensionId,
            annotationType.getSimpleName(),
            bpmnProcessId,
            workflowModuleId,
            workflowAggregateClass.getName());

  }

}
