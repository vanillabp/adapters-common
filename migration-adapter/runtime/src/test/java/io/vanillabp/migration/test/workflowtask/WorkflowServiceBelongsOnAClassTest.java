package io.vanillabp.migration.test.workflowtask;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowServiceBelongsOnAClass;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The refusal both platforms answer <code>&#64;WorkflowService</code> on an interface or on
 * an annotation of the application with. That both platforms say it is covered by their
 * own tests (Spring Boot: {@code WorkflowServiceDiscoveryTest}, Quarkus:
 * {@code WorkflowServiceOnAnInterfaceTest} and
 * {@code WorkflowServiceViaAnAnnotationOfItsOwnTest}); what is left here is the case
 * neither of them can produce, an annotated type nobody in the application uses.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowServiceBelongsOnAClassTest {

  @Test
  @DisplayName("An interface nobody implements is named without a list of implementations")
  public void anInterfaceNobodyImplementsIsNamedAlone() {

    final var message = WorkflowServiceBelongsOnAClass
        .foundOnAnInterface("io.example.LoanApprovalHandlers", List.of());

    assertTrue(
        message.startsWith("""
            @WorkflowService sits on the interface
              io.example.LoanApprovalHandlers
            An interface cannot be"""),
        message);
    assertFalse(
        message.contains("implemented by"),
        "there is no implementation to name, so the message must not ask for one: "
            + message);
    assertTrue(
        message.contains("Move @WorkflowService onto the class holding the @WorkflowTask methods"),
        message);

  }

  @Test
  @DisplayName("An annotation nobody uses is named without a list of classes")
  public void anAnnotationNobodyUsesIsNamedAlone() {

    final var message = WorkflowServiceBelongsOnAClass
        .foundOnAnAnnotation("io.example.OurOwnWorkflowService", List.of());

    assertTrue(
        message.startsWith("""
            @WorkflowService sits on the annotation
              @io.example.OurOwnWorkflowService
            An annotation of your own"""),
        message);
    assertFalse(
        message.contains("used on"),
        "there is no class to name, so the message must not ask for one: "
            + message);

  }

  @Test
  @DisplayName("The types which brought the declaration in are listed sorted, one per line")
  public void theTypesWhichBroughtItInAreListed() {

    final var message = WorkflowServiceBelongsOnAClass
        .foundOnAnInterface(
            "io.example.LoanApprovalHandlers",
            List.of("io.example.FirstHalf", "io.example.SecondHalf"));

    assertTrue(
        message.contains("""
            implemented by
              io.example.FirstHalf
              io.example.SecondHalf"""),
        message);

  }

}
