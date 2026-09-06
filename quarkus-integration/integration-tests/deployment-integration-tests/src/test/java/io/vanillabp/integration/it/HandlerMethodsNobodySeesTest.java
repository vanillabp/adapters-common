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
 * Two handler methods which a developer reads in their own source and VanillaBP never sees: a
 * <code>&#64;WorkflowTask</code> method which is not public, and an override which repeated
 * none of the annotations. Both used to end in the wiring validation asking for a method the
 * developer can point at, so the boot now names them, the class each is declared in and what
 * to do about it.
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
        assertEquals(1, reports.size(), "one report per workflow service class: "
            + records
                .stream()
                .map(java.util.logging.LogRecord::getMessage)
                .toList());
        final var report = reports.getFirst();
        assertTrue(report.contains(InvisibleHandlersWorkflowService.class.getName()), report);
        assertTrue(report.contains("tooWellHidden"), report);
        assertTrue(report.contains("is protected"), report);
        assertTrue(report.contains("Make the method public"), report);
        assertTrue(report.contains("overriddenTask"), report);
        assertTrue(report.contains(OverriddenHandlerBase.class.getName()), report);
        assertTrue(report.contains("carries no annotation of its own"), report);
        assertTrue(report.contains("Repeat the annotation on the override"), report);
      });

  @Test
  @DisplayName("A handler which is not public and one whose override dropped the annotation are named")
  public void bothInvisibleHandlersAreReported() {
    // the assertion happens on the boot's log records (assertLogRecords above)
  }

}
