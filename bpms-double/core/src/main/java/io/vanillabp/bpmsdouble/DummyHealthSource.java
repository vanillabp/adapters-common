package io.vanillabp.bpmsdouble;

import io.vanillabp.integration.adapter.spi.health.AdapterHealth;

/**
 * Optional hook of the dummy adapter used by integration tests to play what a real
 * adapter finds when it asks its BPMS: without a bean the adapter contributes
 * nothing to the health endpoint (which is what an adapter without a check does),
 * with one it contributes whatever the test returns - including an exception, to
 * cover the adapter which cannot answer its own question.
 */
@FunctionalInterface
public interface DummyHealthSource {

  /**
   * @param adapterId The adapter ID being asked
   * @return What the adapter found, or <code>null</code> to contribute nothing
   */
  AdapterHealth healthOf(
      String adapterId);

}
