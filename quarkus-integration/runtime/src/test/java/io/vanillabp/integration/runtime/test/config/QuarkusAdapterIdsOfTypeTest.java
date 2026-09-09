package io.vanillabp.integration.runtime.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterPropertiesMapper;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which adapter ids one adapter TYPE serves, asked of the configuration a QUARKUS
 * application binds. The rule itself is the core's
 * ({@code MigrationAdapterPropertiesTest.AdapterIdsOfType} holds it); what this asks is
 * whether the answer survives the way to it - the config mapping and the generated
 * mapper onto the core model - because an adapter registering its beans per configured
 * engine, and an extension registering one bridge per engine, ask it here.
 * <p>
 * The migration setup is the case worth the test: the new BPMS configured, the old one
 * merely named in the order. It is where the Spring Boot helper once lost the old
 * adapter's beans, and Quarkus never had a helper at all.
 */
@ExtendWith(SuppressOutputExtension.class)
public class QuarkusAdapterIdsOfTypeTest {

  private static MigrationAdapterProperties configured(
      final Map<String, String> properties) {

    final var config = new SmallRyeConfigBuilder()
        .withMapping(QuarkusMigrationAdapterProperties.class)
        .withSources(new PropertiesConfigSource(properties, "test", 500))
        .build();
    return QuarkusMigrationAdapterPropertiesMapper.INSTANCE
        .toCore(config.getConfigMapping(QuarkusMigrationAdapterProperties.class));

  }

  @Test
  @DisplayName("The migration setup serves both adapters, the one configured and the one only named")
  public void theMigrationSetupServesBoth() {

    final var properties = configured(
        Map.of(
            "vanillabp.prioritized-adapters", "camunda8,camunda7",
            "vanillabp.adapters.camunda8.deployment-failure", "warn"));

    assertEquals(List.of("camunda8"), properties.adapterIdsOfType("camunda8"));
    assertEquals(List.of("camunda7"), properties.adapterIdsOfType("camunda7"));

  }

  @Test
  @DisplayName("A custom id naming its type is served, and an id of another type is not")
  public void aCustomIdNamesItsType() {

    final var properties = configured(
        Map.of(
            "vanillabp.prioritized-adapters", "camunda8,legacy",
            "vanillabp.adapters.camunda8.deployment-failure", "warn",
            "vanillabp.adapters.legacy.type", "camunda7"));

    assertEquals(List.of("legacy"), properties.adapterIdsOfType("camunda7"));
    assertEquals(List.of("camunda8"), properties.adapterIdsOfType("camunda8"));
    assertEquals(List.of(), properties.adapterIdsOfType("process-engine-api"));

  }

  @Test
  @DisplayName("Without any configuration the adapter's own type is the id it serves")
  public void withoutConfigurationTheTypeIsTheId() {

    assertEquals(List.of("camunda7"), configured(Map.of()).adapterIdsOfType("camunda7"));

  }

}
