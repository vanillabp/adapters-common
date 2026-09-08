package io.vanillabp.integration.test.extension;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A method carrying the extension's own annotation which is not public, and therefore is
 * not among the methods VanillaBP wires. For a <code>&#64;WorkflowTask</code> the defect
 * surfaces later as a task nobody serves; for an extension nothing surfaces at all, since
 * an extension asking whether a method exists is simply told no and does whatever it does
 * instead. So the boot names the method, the class it is declared in and the way out.
 * <p>
 * The application is started INSIDE the test, because the report is written while the
 * workflow services are scanned and a context booted before the test began would have
 * written it where nothing captures output.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ExtensionHandlerMethodsNobodySeesTest {

  @Test
  @DisplayName("A method of the extension's annotation which is not public is named while booting")
  public void theInvisibleExtensionHandlerIsReported(
      final CapturedOutput output) {

    try (var context = new SpringApplicationBuilder(TestApplication.class)
        .web(WebApplicationType.NONE)
        // a database of its own: the scenario's other context is still cached and its
        // schema must not be created twice
        .properties("spring.datasource.url=jdbc:h2:mem:extension-nobody-sees;DB_CLOSE_DELAY=-1")
        .run()) {

      final var reported = output.getAll();
      Assertions.assertTrue(
          reported.contains("which VanillaBP does not see"),
          "no report about the handler methods nobody sees: "
              + reported);
      Assertions.assertTrue(reported.contains(NotedWorkflowService.class.getName()), reported);
      Assertions.assertTrue(reported.contains("@SampleNote"), reported);
      Assertions.assertTrue(reported.contains("noteNobodyReaches"), reported);
      Assertions.assertTrue(reported.contains("is protected"), reported);
      Assertions.assertTrue(reported.contains("Make the method public"), reported);

    }

  }

}
