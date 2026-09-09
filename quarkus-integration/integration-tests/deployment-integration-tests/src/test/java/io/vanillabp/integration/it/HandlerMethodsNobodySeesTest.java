package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Level;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.inheritance.InvisibleHandlersAggregate;
import io.vanillabp.integration.test.inheritance.InvisibleHandlersAggregatePersistence;
import io.vanillabp.integration.test.inheritance.InvisibleHandlersWorkflowService;
import io.vanillabp.integration.test.inheritance.OverriddenHandlerBase;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Handler methods which a developer reads in their own source and VanillaBP never sees: a
 * <code>&#64;WorkflowTask</code> method which is not public, an override which repeated none
 * of the annotations, and the same defect in the annotation of an EXTENSION. All of them used
 * to end in a task nobody serves - the extension's one in nothing at all, since an extension
 * simply behaves as if the application had never written the method - so the boot now names
 * them, the class each is declared in and what to do about it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class HandlerMethodsNobodySeesTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("inheritance/application.yaml", "application.yaml")
          .addClass(InvisibleHandlersAggregate.class)
          .addClass(InvisibleHandlersAggregatePersistence.class)
          .addClass(OverriddenHandlerBase.class)
          .addClass(InvisibleHandlersWorkflowService.class)
          .addAsResource("inheritance-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .setLogRecordPredicate(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
      .assertLogRecords(records -> {
        final var reports = records
            .stream()
            .map(record -> record.getMessage() == null
                ? ""
                : record.getMessage())
            .filter(message -> message.contains("which VanillaBP does not see"))
            .toList();
        assertEquals(2, reports.size(), "one report per workflow service class and handler annotation: "
            + records
                .stream()
                .map(java.util.logging.LogRecord::getMessage)
                .toList());
        final var ownAnnotations = reports
            .stream()
            .filter(report -> report.contains("@WorkflowTask"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no report about VanillaBP's own annotations: "
                + reports));
        assertTrue(ownAnnotations.contains(InvisibleHandlersWorkflowService.class.getName()), ownAnnotations);
        assertTrue(ownAnnotations.contains("tooWellHidden"), ownAnnotations);
        assertTrue(ownAnnotations.contains("is protected"), ownAnnotations);
        assertTrue(ownAnnotations.contains("Make the method public"), ownAnnotations);
        assertTrue(ownAnnotations.contains("overriddenTask"), ownAnnotations);
        assertTrue(ownAnnotations.contains(OverriddenHandlerBase.class.getName()), ownAnnotations);
        assertTrue(ownAnnotations.contains("carries no annotation of its own"), ownAnnotations);
        assertTrue(ownAnnotations.contains("Repeat the annotation on the override"), ownAnnotations);

        final var extensionAnnotation = reports
            .stream()
            .filter(report -> report.contains("@SampleNote"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no report about the extension's annotation: "
                + reports));
        assertTrue(extensionAnnotation.contains("noteNobodyReaches"), extensionAnnotation);
        assertTrue(extensionAnnotation.contains("is protected"), extensionAnnotation);
        assertTrue(extensionAnnotation.contains("Make the method public"), extensionAnnotation);
      });

  @Test
  @DisplayName("A handler which is not public, one whose override dropped the annotation, and an extension's own")
  public void everyInvisibleHandlerIsReported() {
    // the assertion happens on the boot's log records (assertLogRecords above)
  }

}
