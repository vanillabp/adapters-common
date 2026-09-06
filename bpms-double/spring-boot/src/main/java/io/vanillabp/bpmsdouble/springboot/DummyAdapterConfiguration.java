package io.vanillabp.bpmsdouble.springboot;

import org.springframework.boot.autoconfigure.AutoConfiguration;

import io.vanillabp.bpmsdouble.DummyAdapter;
import io.vanillabp.integration.adapter.AdapterConfigurationBase;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;

/**
 * Announces the BPMS double to the platform. It carries no other bean definition,
 * because it has to run before the platform's own auto-configuration.
 */
@AutoConfiguration(before = SpringBootMigrationAdapterAutoConfiguration.class)
public class DummyAdapterConfiguration extends AdapterConfigurationBase {

  public static final String ADAPTER_TYPE = DummyAdapter.ADAPTER_TYPE;

  /**
   * @return The ID of the adapter
   */
  @Override
  public String getAdapterType() {
    return ADAPTER_TYPE;
  }

}
