package io.vanillabp.integration.test.deployment;

import java.util.function.Function;

import io.vanillabp.bpmsdouble.DummyHealthSource;
import io.vanillabp.integration.adapter.spi.health.AdapterHealth;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Plays what the dummy adapter finds when it asks its BPMS. The answer is a static
 * field so a test can change it between assertions without rebuilding the
 * application.
 */
@ApplicationScoped
public class TestHealthSource implements DummyHealthSource {

  /**
   * What to answer, set by the test; the default contributes nothing, which is what
   * an adapter without a check does.
   */
  public static Function<String, AdapterHealth> answer = adapterId -> null;

  @Override
  public AdapterHealth healthOf(
      final String adapterId) {

    return answer.apply(adapterId);

  }

}
