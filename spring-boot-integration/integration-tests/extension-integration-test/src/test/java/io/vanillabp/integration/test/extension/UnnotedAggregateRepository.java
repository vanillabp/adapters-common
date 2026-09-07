package io.vanillabp.integration.test.extension;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The repository VanillaBP uses for {@link UnnotedAggregate}.
 */
public interface UnnotedAggregateRepository extends JpaRepository<UnnotedAggregate, Long> {
}
