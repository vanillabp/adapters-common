![Header](../../readme/vanillabp-headline.png)

# VanillaBP Quarkus extension - runtime module

The VanillaBP Quarkus extension's runtime module. It is responsible for bridging to the
[VanillaBP migration adapter](../../migration-adapter) at runtime.

## Running a check right before the commit

A phase-one check of a remote BPMS must not advance the process, but it may ASK - whether the
task still exists, whether the model declares a message - and the answer can go stale between
the question and the phase-two dispatch. The later the check runs, the smaller that window, so
adapters hand their checks to the `PreCommitRegistrar` of the adapter SPI and
`QuarkusPreCommitRegistrar` implements it here.

It resolves the transaction runner of the workflow aggregate first
(`QuarkusTransactionRunnerResolver`), then calls `TransactionRunner#beforeCommit`.
That indirection is the point: since an application may bring its own unit of work, the check
has to be hooked into the unit of work VanillaBP actually uses, not into the platform's JTA
transaction. `QuarkusTransactionRunner` implements the hook with an interposed JTA
`Synchronization` whose `beforeCompletion()` runs the check - throwing there aborts the commit,
which is what a failing phase-one check has to do. A runner of an application which does not
implement the hook runs the check immediately, the behaviour of every adapter before the hook existed.

An earlier, wider mechanism (`EventualConsistencyTransactionSupport`: probes before the commit
plus actions after it, per transaction) was removed with the hook. It existed before the
phase-two outbox took over the after-commit half, had no caller left, and existed on this
platform only.

## Phase-two outbox

Everything an application sends to its BPMS leaves after the caller's transaction
committed, so every application needs a transaction outbox (`PhaseTwoOutbox` SPI of the
[migration adapter](../../migration-adapter)): it schedules phase two within the local
transaction and dispatches it reliably after the commit (also after a crash/restart,
retrying with a backoff).

This module provides an own JDBC/JTA-based default implementation (gruelbox does not
support JTA): `JdbcPhaseTwoOutbox` writes entries into the table
`VANILLABP_PHASE_TWO_OUTBOX` using a connection of the Agroal datasource which is
enlisted in the running JTA transaction. The entry persists all fields of the
`PhaseTwoCall` (operation discriminator, elected adapter ID, serialized aggregate
ID) plus the idempotency key, enforced unique by a constraint of the table —
duplicate schedules are a no-op. `JdbcPhaseTwoOutboxDispatcher` claims due OPEN
entries atomically (optimistic update with attempts/backoff) and dispatches them
through the core-owned `PhaseTwoRouter` — right after the commit and by a
fixed-delay poller (crash recovery and retries). Successful dispatches mark the
entry DONE (deleted asynchronously once `vanillabp.outbox.retention` passed — the
entry stays readable for support, while its `DEDUP_KEY` is replaced by its own ID so
a repetition of the same operation can be planned again); repeatedly failing entries are marked
BLOCKED. The generated process-service beans register themselves with the router
(produced by `PhaseTwoRouterProducer`) at bean creation, including a converter
turning the serialized aggregate ID back into the aggregate's ID type (determined
by reflection over the aggregate class, see `AggregateIdConversion`; if
undeterminable, the String is passed through). The poller uses a plain scheduled
executor started on `StartupEvent`, so the `quarkus-scheduler` extension is not
required. The outbox beans are only registered if the Agroal capability is present
(see `VanillaBpBuildStepProcessor#buildPhaseTwoOutbox`); applications may define
their own `PhaseTwoOutbox` beans in addition.

BOTH defaults (JDBC + MongoDB) may coexist and the outbox is
selected **per workflow aggregate** (`QuarkusPhaseTwoOutboxResolver`): the most
specific `PhaseTwoOutboxAware` bean wins; without one, the single active outbox is
used (deactivated - `vanillabp.outbox.jdbc.enabled`/`vanillabp.outbox.mongo.enabled`
- or unusable defaults are not considered), and with several active outboxes the
platform default matching the technology which manages the aggregate is used, read
off the persistence VanillaBP resolved for it (`QuarkusPersistenceTechnology`). Only
where that cannot be told - the application brought the persistence itself - does the
startup end guiding towards `PhaseTwoOutboxAware`. Resolution happens AT STARTUP
via an inherited `StartupEvent` observer on `ProcessServiceBaseCdiBean` - a missing
outbox fails the boot naming the remedies (`QuarkusStoreAttributionTest` for the
resolution, `OutboxStartupValidationTest` for the boot which ends).

The resolver is a CDI bean of the neutral type
`io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver`
(`PhaseTwoOutboxResolverProducer`, `@Unremovable`), and the process services use that
bean rather than one of their own: an extension writing phase-two entries of its own
into the transaction of a workflow aggregate has to reach the same store, and it
cannot work that out itself - which default serves which technology, and whether it
is usable, is `PlatformDefaultStore`, an interface of this module and of no SPI. The
Spring Boot integration offers `SpringPhaseTwoOutboxResolver` as a bean for the same
reason. An extension injecting it and getting the store of each aggregate of a
two-persistence application is `MixedPersistenceStoreAttributionTest`.

Store names are configurable
(`vanillabp.outbox.jdbc.table`, `vanillabp.outbox.mongo.collection`); every outbox
instance needs its OWN store (two dispatchers polling the same store would compete
and double-dispatch).

For applications using MongoDB (`quarkus-mongodb-client`) instead of a JDBC
datasource, a MongoDB-based default is provided: `MongoPhaseTwoOutbox` writes into
the collection `vanillabp-phase-two-outbox` (same layout as the Spring Boot MongoDB
outbox) of the database configured by `quarkus.mongodb.database`, and
`MongoPhaseTwoOutboxDispatcher` claims/dispatches/blocks and cleans up analogously
(atomic `findOneAndUpdate` claims, cluster-safe without a lock). Since MongoDB is
no JTA resource, the outbox operates **best-effort**: the entry is written before
the commit; on rollback it is deleted best-effort (a crash in between leaves an
orphan which ends up BLOCKED with a monitorable ERROR). Deduplication is
enforced by a unique index over `dedupKey`, created automatically unless
`vanillabp.outbox.create-schema` is disabled. If both Agroal and the MongoDB client
are present, the JDBC outbox wins deterministically (consistent with Spring Boot
where the JPA outbox is ordered first).

|                  |           JDBC outbox (Agroal)           |       MongoDB outbox (`quarkus-mongodb-client`)       |
|------------------|------------------------------------------|-------------------------------------------------------|
| Enlisting        | JTA transaction (entry = part of TX)     | best-effort (write before commit, delete on rollback) |
| Store            | table `VANILLABP_PHASE_TWO_OUTBOX`       | collection `vanillabp-phase-two-outbox`               |
| Dedup            | unique constraint `DEDUP_KEY`            | unique index `dedupKey`                               |
| Claim            | optimistic `UPDATE ... WHERE ATTEMPTS=?` | `findOneAndUpdate`                                    |
| DONE + retention | yes                                      | yes                                                   |
| Selected when    | Agroal capability present                | no Agroal, MongoDB client present                     |

Configuration (`QuarkusMigrationAdapterProperties`): `vanillabp.outbox.poll-interval`,
`vanillabp.outbox.attempt-frequency`, `vanillabp.outbox.block-after-attempts`,
`vanillabp.outbox.retention` and
`vanillabp.outbox.create-schema` (disable the `CREATE TABLE IF NOT EXISTS` DDL /
index creation to manage the schema manually, e.g. by Flyway or Liquibase — then
also create the unique constraint on `DEDUP_KEY` / the unique index on `dedupKey`
yourself — that column respectively field carries the idempotency key while the entry
waits for its dispatch and the entry's own ID afterwards, which is what keeps the
deduplication window to the operations still planned).

## Optional extensions and the native image

`quarkus-mongodb-client` and `quarkus-mongodb-panache` are optional dependencies of this
module, so every class naming one of their types has to stay unreachable in an application
which brought neither. On the JVM that takes care of itself, because a class is loaded when
it is first used. A native image is stricter: GraalVM links the whole reachable graph while
it builds and stops at the first method it cannot resolve. Three such methods were found,
each of them called from a resolver every application runs.

Two rules keep them out of that graph.

- Whatever names a MongoDB type is a bean the extension registers only under
  `Capability.MONGODB_CLIENT`: `MongoPhaseTwoOutbox`, `MongoTaskDeliveryLog` and
  `MongoClientDeploymentProbe`.
- The resolvers ask an interface instead of naming those classes. `PlatformDefaultStore`
  answers which persistence technology a store of the platform serves and whether it is
  usable at all, `MongoDeploymentProbe` answers whether the MongoDB deployment is a replica
  set. Without the extension the injection point is simply unsatisfied, and that is the
  answer the resolver needs anyway.

The persistence detection has followed the rule from the start: `QuarkusPersistenceTechnology`
matches VanillaBP's own persistence implementations by their class NAME.

What keeps all of this honest is the module
[native-image-tests](../integration-tests/native-image-tests): an application with H2 and no
MongoDB anywhere, whose native build is the assertion. Locally:

```shell
./mvnw install -DskipTests -am -pl quarkus-integration/deployment,\
  quarkus-integration/integration-tests/dummy-adapter/deployment,\
  quarkus-integration/integration-tests/native-image-tests
./mvnw package -pl quarkus-integration/integration-tests/native-image-tests -Dnative
./quarkus-integration/integration-tests/native-image-tests/target/*-runner
```

The deployment modules of both extensions are named explicitly, because an extension's
augmentation part is no dependency of the application: `-am` alone would leave them to the
local repository, where a stale copy hides whatever the build step was just changed to do.

Docker pulls the Mandrel builder image, so no GraalVM has to be installed. The last line
is there because a binary which cannot start proves nothing: the application's main boots
it, starts a workflow and reads the aggregate back, and its exit code says whether that
worked. It found the second half of the problem, the BPMN resources missing from the image. In
CI the job `native-build` runs the same three commands.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
