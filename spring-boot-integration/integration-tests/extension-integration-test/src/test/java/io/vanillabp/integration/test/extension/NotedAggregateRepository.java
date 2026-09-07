package io.vanillabp.integration.test.extension;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The repository VanillaBP uses for {@link NotedAggregate} - and which the tests read
 * through to see what a handler invocation saved.
 */
public interface NotedAggregateRepository extends JpaRepository<NotedAggregate, Long> {
}
