package io.vanillabp.bpmsdouble.springboot;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

import io.vanillabp.bpmsdouble.DummyAdapter;

/**
 * Wires the double's process-service and deployment-service beans: ONE
 * element bean per configured adapter id of the dummy type, registered by the
 * imported {@link DummyAdapterBeanRegistrar} (the reference implementation of the
 * per-id bean convention).
 * <p>
 * Additionally registers the adapter's OVERLAY of the shared
 * <code>vanillabp.*</code> tree ({@link DummyAdapterOverlayProperties}) - the
 * reference implementation of contributing adapter-specific keys to the canonical
 * per-adapter location <code>vanillabp.adapters.&lt;id&gt;.*</code>.
 */
@AutoConfiguration
@EnableConfigurationProperties(DummyAdapterOverlayProperties.class)
@Import(DummyAdapterBeanRegistrar.class)
public class DummyAdapterProcessServiceConfiguration {

  /**
   * See {@link DummyAdapter#PROPERTY_AT_LEAST_ONCE_DELIVERY}.
   */
  public static final String PROPERTY_AT_LEAST_ONCE_DELIVERY = DummyAdapter.PROPERTY_AT_LEAST_ONCE_DELIVERY;

  /**
   * See {@link DummyAdapter#PROPERTY_READ_AGGREGATE_IN_PHASE_TWO}.
   */
  public static final String PROPERTY_READ_AGGREGATE_IN_PHASE_TWO = DummyAdapter.PROPERTY_READ_AGGREGATE_IN_PHASE_TWO;

}
