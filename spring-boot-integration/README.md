![Header](../readme/vanillabp-headline.png)

# Spring Boot integration

In Spring Boot one has to use libraries as Maven/Gradle dependencies
to bring new functionality into a project. Also, Spring Boot platform integration
of VanillaBP is done by providing such Maven/Gradle module leveraging Spring Boots
autoconfiguration mechanism to provide the best developer experience.

## Modules

1. **[runtime](./runtime):**<br>
   This is the main module which is primarily responsible for two things:
   1. Bringing the [VanillaBP SPI](https://github.com/vanillabp/spi-for-java) in Spring Boot to life.
   2. Managing of VanillaBP adapters at runtime connecting to BPMSs.
2. **[spring-boot-support](./spring-boot-support):**<br>
   A tiny collection of useful things in the context of Spring Boot to be used as
   a dependency instead of `io.vanillabp:spi-for-java`. It also carries the one
   check which has to work without any of the rest (see below).
3. **[integration-tests](./integration-tests):**<br>
   Modules which ensure the VanillaBP Spring Boot extension works as documented.

## An application without a BPMS adapter

On Spring Boot a BPMS adapter brings `vanillabp-spring-boot-integration` along, so an
application which forgot the adapter dependency has no VanillaBP runtime at all - the
autoconfiguration below `runtime` is not on the classpath and cannot report anything. What
such an application does have is `vanillabp-spring-boot-support`, because every workflow
module compiles against it. That is why the check reporting a missing adapter
(`NoBpmsAdapterCheck`) lives in the support module and not here: it is the only
VanillaBP code present in the case it was written for. Without it the bean container
answers instead ("No qualifying bean of type `ProcessService<...>`"), a message mentioning
neither an adapter nor VanillaBP.

It is a `BeanFactoryPostProcessor`, so it runs before the first bean of the application is
created. It only speaks up when a workflow module is on the classpath, because an
application without one has nothing to run and boots as it always did. The neighbouring
case, an integration which is loaded but got no adapter with it, stays here in
`SpringBootMigrationAdapterAutoConfiguration`, which knows the adapters actually loaded.
Both messages say where to look up an adapter (`BpmsAdapters` of the support module points at
the wiki page listing them) and that the VanillaBP BOM does not manage adapter versions, since
adapters are released on their own schedule. The adapters themselves are deliberately not
listed in compiled code: a list in a JAR is out of date the day a new adapter appears.

`NoBpmsAdapterCheckTest` holds the check itself (`aWorkflowModuleWithoutAnAdapterEndsTheBoot`,
`theCheckRunsBeforeTheFirstBean`, `theNeighbouringCasesAreLeftAlone`), and
`NoAdapterBootTest#bootWithoutAnyAdapterYieldsGuidingMessage` holds the neighbouring case
from the auto-configuration.

## Which class is the workflow service

`WorkflowServiceDiscovery` walks the bean definitions and keeps every bean whose class carries
`@WorkflowService`, resolved with `AnnotationUtils.findAnnotation`, which walks the superclass
chain. The class registered is the class of the BEAN, so a bean of a class extending an annotated
one is a workflow service of its own: the annotation's attributes reach it through `@Inherited`, its
own simple name is the BPMN process id where the annotation names none, and `getMethods()` returns
the handler methods it declares next to the ones it inherited. Quarkus resolves the same reading
while it builds an application, out of the Jandex index (see decision 32 in the repository's
DECISIONS.md).

`findAnnotation` reaches further than `@Inherited` does, into implemented interfaces and into
annotations of the application's own, and those two are refused rather than registered, with the
wording the core holds in `WorkflowServiceBelongsOnAClass`. A class annotated without being a bean
is passed over without a word, for the reason decision 21 gives.

## Configuration binding

The user-facing `vanillabp.*` configuration tree is modeled ONCE, in the
platform-neutral core (`MigrationAdapterProperties` of the migration adapter).
Spring Boot binds the core POJOs directly: the thin subclass
`VanillaBpConfigurationProperties` only carries
`@ConfigurationProperties("vanillabp")`, so relaxed names, profiles and
environment-variable overrides work out of the box and every property added to
the core model is picked up without platform code. Defaulting (`normalize()`)
and ALL validation live in the core - the auto-configuration only feeds in the
classpath facts (adapter types found, workflow-module ids) and the raw property
names (used to detect `VANILLABP_*` environment variables not taken over by the
binding).

The runtime module runs the Spring Boot configuration processor and ships
`META-INF/spring-configuration-metadata.json` (types plus hand-written
descriptions of the stable top-level keys in
`additional-spring-configuration-metadata.json`) for IDE completion. Map-typed
dynamic keys (`vanillabp.adapters.<id>.*`, `vanillabp.workflow-modules.<id>.*`)
are declared once with their value type - IDEs drill into the value types on
the classpath.

BPMS adapters contribute their own keys to the same tree (e.g.
`vanillabp.adapters.<id>.rest-address`) by binding an adapter-owned second
`@ConfigurationProperties("vanillabp")` overlay class: same-prefix classes
coexist, and keys unknown to the core view are ignored by the JavaBean binding.
The adapter-id set is always derived from the core properties
(`adapterTypes()`), never from an overlay map.

`VanillaBpConfigurationBindingTest` holds the binding onto the core model, the overlay
coexistence and the environment-variable rules; the guiding validation itself lives in the
core and is held by `MigrationAdapterPropertiesTest`.

## The store of processed task deliveries

`io.vanillabp.integration.delivery` implements the core's `TaskDeliveryLog` (the
inbound counterpart of the outbox) twice, and `SpringTaskDeliveryLogResolver` picks
one per workflow aggregate - the same resolution the phase-two outbox uses, since a
record has to ride the aggregate's own transaction. The persistence technology behind an
aggregate is detected once in `SpringPersistenceTechnology`, shared by both resolvers.

- `JdbcTaskDeliveryLog` writes through `DataSourceUtils.getConnection(dataSource)`, so
  the connection belongs to the Spring-managed transaction. The SQL and the portable DDL
  of table `VANILLABP_TASK_DELIVERY` live in the core (`JdbcTaskDeliveryStore`), shared
  with Quarkus. Deliberately NOT gruelbox: gruelbox stores calls to be dispatched, a
  delivery record is a fact to be read back. `recordOfTask` and `markTaskClosed` go to the
  same store: they are what lets a task operation elect its BPMS from the record instead of
  asking one (decision 30), and the connection they use is the caller's, so the read sees
  what the caller's own transaction wrote.
- `MongoTaskDeliveryLog` writes `TaskDeliveryDocument`s into `vanillabp-task-deliveries`
  through the `MongoTemplate`, keyed by the delivery key (the document ID gives
  uniqueness). A duplicate is detected by a pre-check read: inside a MongoDB transaction
  a duplicate-key error would abort the whole transaction, the aggregate changes
  included. Its auto-configuration creates a second index, on `taskId`, which is what
  `recordOfTask` reads by.
- Both come with an auto-configuration of their own
  (`vanillabp.outbox.jdbc.enabled` / `.mongo.enabled`), create their schema unless
  `vanillabp.outbox.create-schema` is disabled and delete expired records per
  `vanillabp.outbox.retention` (`cleanUpExpiredRecords`, scheduled by the core's
  `TaskDeliveryRetentionCleanup`). Table and index are created from a
  `SmartInitializingSingleton`, not while the bean is built - the DDL must not
  materialize the data source before the application's configuration is complete.
- The same run refreshes the records of the tasks which are still open: the core
  collects the keys the BPMS redelivered, `cleanUpExpiredRecords` writes them before it
  deletes anything, and the JDBC store batches while the MongoDB one bulk-writes.

## What is published where

Both contributions are optional dependencies of this module, so an application which brings
neither boots unchanged.

Metrics ride on `micrometer-core`. `MicrometerVanillaBpMetrics` is a bean of the nested
`WorkflowAdapterCacheMetricsConfiguration`, conditional on the `MeterRegistry` class BY NAME:
the annotation of a nested configuration class is read reflectively, so a class literal of an
absent optional dependency would blow up before the condition is evaluated. The Actuator's
metrics auto-configuration binds it like any other `MeterBinder`. The pending-entry gauges of
the outbox stores are registered by a `SmartInitializingSingleton`, not while the beans are
built: resolving the stores earlier would materialize persistence infrastructure before the
application asked for it.

Health rides on `spring-boot-health` (Spring Boot 4 moved `HealthIndicator` out of the
Actuator artifact into that one). The bean is named `vanillabpHealthIndicator`, because the
bean name minus the suffix is what names the health component, and it has to stay `vanillabp`.

The process services get the metrics from `ProcessServiceBeanRegistrar` right after they are
built, primary and secondary alike; `PhaseTwoRouter` gets them from the auto-configuration.
A setter rather than another constructor parameter: the metrics exist once per application
while process services exist per BPMN process, and that constructor is long enough.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
