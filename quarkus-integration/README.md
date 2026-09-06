![Header](../readme/vanillabp-headline.png)

# Quarkus integration

In Quarkus one has to use Quarkus extensions to bring new functionality into a
Quarkus project (see https://quarkus.io/extensions/). As this is the best developer
experience also Quarkus platform integration of VanillaBP is done leveraging
Quarkus' extensions mechanism.

There is one main extension `vanillabp` (implemented
by this module) which is primarily responsible for two things:

1. Bringing the [VanillaBP SPI](https://github.com/vanillabp/spi-for-java) in Quarkus to life.
2. Managing of VanillaBP adapters at build and runtime connecting to BPMSs.

Additionally, there are VanillaBP adapter extensions which need be added next to the
`vanillabp` extension to provide runtime connectivity to supported BPMSs. There is one
[dummy adapter](./dummy-adapter) as a template for new adapters also providing the
documentation what needs to be considered on providing a new VanillaBP
adapter extension. For ready-to-use adapter extensions checkout
[https://www.vanillabp.io](https://www.vanillabp.io).

## Modules

To understand subsequent documentation, read the Quarkus guide
"[Writing your own extension](https://quarkus.io/guides/writing-extensions)"
to learn about concepts of Quarkus extensions.

1. **[deployment](./deployment):**<br>
   The deployment module of the extension. It is responsible for code analysis,
   loading configuration and preparing runtime CDI beans.
2. **[runtime](./runtime):**<br>
   The runtime module of the extension. It is responsible for bridging to
   [VanillaBP migration adapter](../migration-adapter) at runtime.
3. **[quarkus-support](./quarkus-support):**<br>
   A tiny collection of useful things in the context of Quarkus to be used as
   a dependency instead of `io.vanillabp:spi-for-java`.
4. **[integration-tests](./integration-tests):**<br>
   Modules which ensure the VanillaBP Quarkus extension works as documented.

## Implementation concepts

In contrast to Spring Boot (runtime reflection), the Quarkus integration does as much
as possible at **build time**, following Quarkus' extension philosophy:

1. **Code analysis via Jandex:** `@WorkflowService` classes and
   `AggregatePersistenceAware` implementations are found in the Jandex index
   (`ProcessServiceBuildStepProcessor`). That is why workflow sub-modules containing
   such classes must be indexed using the `jandex-maven-plugin`. The Jandex index is
   *not* needed for workflow-module detection itself: the `META-INF/workflow-module`
   descriptor is registered as an additional application-archive marker
   (`AdditionalApplicationArchiveMarkerBuildItem`), so JARs containing only the
   descriptor and BPMS resources (or JARs built without the plugin, e.g. by Gradle)
   are detected as well.
   What the index reports is the type the annotation SITS on, which is why an interface
   carrying `@WorkflowService`, and an annotation of the application composing it, end the
   build with a message naming the type and the classes which brought it in: an interface
   would serve the methods declared in it, and Spring Boot would serve those of the
   implementing class.
2. **Bean generation via Gizmo:** For each workflow aggregate a
   `ProcessService_<Aggregate>` CDI bean class extending `ProcessServiceBaseCdiBean<A>`
   is generated as bytecode at build time — the Quarkus counterpart of Spring's
   `BeanDefinition` registration. The runtime base class bridges to the
   [migration adapter](../migration-adapter)'s `MigrationProcessService`.
3. **Workflow module detection:** `WorkflowModuleBuildStepProcessor` scans all
   application archives for `META-INF/workflow-module` marker files (content = workflow
   module ID); the root archive acts as the *global* module fallback.
4. **Workflow-module-specific configuration:** Config sources for
   `<module-id>[-<profile>].properties/.yaml` files are generated as `ConfigBuilder`
   classes with config ordinals BELOW `application.*` (properties: 230, YAML: 235
   against 250 and 255), because a workflow module ships defaults and the application
   always wins. The gap to 250 absorbs the ordinal SmallRye adds per active profile, so
   a module's `-prod` file cannot climb above the application's files. The reasoning and
   the full ordinal table live in the javadoc of `WorkflowModuleBuildStepProcessor`. The
   files are collected once, by their path relative to the archive holding them, and that
   one list is handed to dev-mode hot reload and to the native image alike
   (`watchAndEmbedWorkflowModuleSpecificConfigFiles`). A native image carries only the
   resources it was told about, so a file missing from that list is a workflow module
   silently running on its defaults, and building the list twice is how the two would
   drift apart. Profile-specific variants are part of it, which is what lets
   `-Dquarkus.profile=<name>` on a binary pick among a module's files as it does on the
   JVM (`native-image-tests`, whose `WorkflowModuleConfigurationCheck` asserts every
   location and both formats while the application boots, in its own archive and in a
   dependency JAR). On the JVM the same rule is held by
   `WorkflowModuleConfigurationIsADefaultTest` and
   `WorkflowModuleProfileFileBeatsPlainFileTest`.
   The application's own `application-<profile>.yaml` is a different animal and stays
   Quarkus': that file list is resolved while the image is built, so a profile chosen at
   the binary adds nothing to it. `ProfileSpecificApplicationFilesBuildStepProcessor` says
   so during a native build and names the ways to those values, rather than VanillaBP
   growing a loading mechanism next to Quarkus' own
   (`ProfileSpecificApplicationFilesTest`).
5. **Aggregate persistence:** a CDI bean implementing `AggregatePersistenceAware`
   always wins, the most specific generic type first (`AggregatePersistenceResolver`,
   Jandex-based). For an aggregate having none, VanillaBP asks the aggregate what it
   is (`DefaultAggregatePersistenceResolver`, also at build time): a Panache
   repository for it wins (Hibernate ORM or MongoDB), then the aggregate being a
   Panache active record, then a Spring Data repository for it
   (`quarkus-spring-data-jpa`). The chosen implementation lives in the runtime module
   (package `runtime/persistence`), and one `@Singleton` subclass per aggregate is
   generated with Gizmo, so the runtime lookup finds a normal CDI bean. Two
   repositories for one aggregate fail the build; an aggregate using none of the
   idioms fails it, too, with a message naming what was looked for
   (`DefaultAggregatePersistenceResolverTest#severalRepositoriesFailTheBuild` and
   `#unknownAggregateIsNotServed`). Everything about
   the aggregate's ID (name, type, value) comes from reflection (`AggregateIdTypes`),
   not from the persistence framework: it is needed at build and startup time, where
   no session is guaranteed to be around.
6. **Validation at build time:** `EnsureCollectedClassesAreBeansBuildStepProcessor`
   fails the build if collected classes (workflow services, persistence
   implementations) are not actual CDI beans.
7. **Configuration binding:** the user-facing `vanillabp.*` tree is modeled ONCE in
   the platform-neutral core (`MigrationAdapterProperties`).
   `QuarkusMigrationAdapterProperties` stays a RUN_TIME `@ConfigMapping` (module
   config files and env overrides need the runtime config), and the transformer
   copies it onto the core model via the GENERATED MapStruct mapper
   `QuarkusMigrationAdapterPropertiesMapper` (`unmappedSourcePolicy`/
   `unmappedTargetPolicy = ERROR` pins the mapping at compile time; the SmallRye
   interface's fluent accessors are made visible to MapStruct by the reactor
   artifact `vanillabp-mapstruct-fluent-accessors` on the annotation-processor path
   — build with `install`, not `package`). Defaulting and ALL guiding validation
   run in the core; the transformer keeps only the capability checks.
   Adapter extensions contribute their own keys to the shared tree via an
   adapter-owned RUN_TIME `@ConfigRoot @ConfigMapping(prefix = "vanillabp")`
   overlay (reference: the dummy adapter's `DummyAdapterOverlayProperties`).
   There is deliberately NO blanket `withMappingIgnore("vanillabp.**")`: SmallRye's
   unknown-key validation passes if ANY registered mapping knows a key, so the
   platform mapping and the adapter overlays validate the tree together and a typo
   under `vanillabp.*` fails the startup (Quarkus is stricter than Spring Boot
   here - accepted). `QuarkusMigrationAdapterPropertiesMapperTest` holds the mapping onto
   the core model, `UnknownPropertyKeyConfigurationTest` the typo and
   `EnvironmentVariableMisbindingTest` the environment variable which does not bind.
8. **Runtime deployment pipeline:** `VanillaBpDeploymentRunner` (runtime module)
   observes the `StartupEvent` and drives the core `DeploymentService` for every
   workflow module found in the classpath:
   `readBpmn → prepareBpmn → wireBpmn → deployResources → startWorkflowProcessing`,
   honoring the `deployment-failure` policy and wiring extensions ordered by
   `getOrder()` - the counterpart of Spring Boot's `SpringBootDeploymentService`.
   Details:
   - **BPMN index:** `resources-location` is RUN_TIME configuration and a fast-jar
     cannot pattern-scan `**/*.bpmn` at runtime, so all `.bpmn` resource paths of
     all application archives are indexed at build time
     (`DeploymentPipelineBuildStepProcessor`) and recorded as the synthetic
     `BpmnResourceIndex` bean, filtered at runtime by the configured location. Only
     classpath locations are supported (a `file:` location fails with a guiding
     message). In dev mode the indexed files are hot-reload-watched; adding a NEW
     BPMN file requires a restart (or touching a watched file).
   - **Adapters** announce their deployment services via
     `VanillaBpAdapterDeploymentServiceBuildItem` (mirroring the process-service
     build item); the announced producer yields ONE bean of type
     `List<AdapterDeploymentService<Object, Object>>` with one instance per
     configured adapter id. Both element type parameters are LITERALLY `Object`,
     regardless of the adapter's model/context classes - CDI's parameterized-type
     matching of differing type arguments is not reliable across modes, so the
     platform looks the beans up with the exact type (the pipeline matches models
     via `getModelType()`/`getProcessContextType()`, never via the generics).
     Producer methods must be `@Singleton`: deployment/wiring services have no
     no-arg constructor and are not client-proxyable.
   - **Extensions** contribute plain `ExtensionWiringService` element beans (kept
     from ArC's unused-bean removal by the platform); see the
     [dummy extension](./integration-tests/dummy-extension) as the template.
   - **Ordering vs. the phase-two outbox:** the runner observes the `StartupEvent`
     with `@Priority(VanillaBpDeploymentRunner.STARTUP_PRIORITY)`, the outbox
     dispatchers with `OUTBOX_DISPATCHER_STARTUP_PRIORITY` - a crash-recovered
     phase-two operation is never dispatched before deployment finished and
     workflow processing started (Spring Boot enforces the same invariant via
     `@Order`-ed `ApplicationReadyEvent` listeners).
   - **Lifecycle semantics vs. Spring Boot (documented, accepted):** Spring deploys
     during context refresh and starts workflow processing on
     `ApplicationReadyEvent` (after the web server started serving); on Quarkus
     both happen in the `StartupEvent` observer, i.e. before the application
     serves traffic. On shutdown Spring stops workflow processing before the web
     server stops serving, whereas the Quarkus `ShutdownEvent` fires after the
     HTTP server stopped accepting requests. Both platforms stop extensions and
     adapters in reverse start order and stop all process services afterwards
     (`VanillaBpShutdownObserver` → `VanillaBpDeploymentRunner.stop()`).

## What a message of this module names

An application without a BPMS adapter hears where to get one. No Quarkus extension
publishing a capability `io.vanillabp.adapter.*` means there is no BPMS to run a workflow
module, so the build ends with that sentence, a link to the wiki page listing every adapter
with the name of its Quarkus extension, and the note that adapter versions do not come from
the VanillaBP BOM (`BpmsAdapters`). The adapters are not listed in the code on
purpose: they are released independently, so a list in a JAR ages badly. The Spring Boot
side says the same in its own words, but from its support module: there an adapter is what
brings the integration along, so the integration cannot be the one to report its absence.

Where a message names a bean, it names the class the application wrote, not the runtime
class. The runtime class of a normal-scoped CDI bean is the client proxy of the container,
and a name ending in `_ClientProxy` belongs to no file the developer can open.
`Instance#handles()` yields a handle per bean, and `Handle#getBean().getBeanClass()` is the
declared class, which is what `QuarkusTransactionRunnerResolver` reports. The
suffix is never cut off a runtime class name: that guesses at a naming convention of the
container and breaks the day the container changes it. `NoAdapterExtensionTest` holds the
build failure without an adapter, `QuarkusTransactionRunnerResolverTest` the class an
adapter-free message names.

## Hints

### Logging during tests

Three things, and each one is there for a reason that was measured:

1. `@ExtendWith(SuppressOutputExtension.class)` on every test class buffers what the class
   prints and writes it out when a test fails. That includes what is logged
   through JBoss LogManager, which the swap of `System.out` alone never reached.
2. `<quarkus.log.level>INFO</quarkus.log.level>`, not `ERROR` as before: a record dropped
   by its level reaches no handler, so it never reaches the capture either, and a failing
   class then replays nothing worth reading.
3. `redirectTestOutputToFile`, because the tests of these modules boot their application IN
   the test JVM and Quarkus logs that boot into a log context of its own. It happens in the
   Quarkus extension's `beforeAll`, after ours and before our first `beforeEach`, so nothing
   captures it: 311 lines in a green run of `deployment-integration-tests` without the
   redirection.

The price of the third one is that the replay of a failing class lands in
`target/failsafe-reports/<class>-output.txt` and Surefire prints only its last line. The
workflow therefore uploads these reports as an artifact when a build fails, so a red run is
one download away from the whole log.

The Quarkus test modules of the adapter repositories need no redirection: their tests boot
their application in a FORKED JVM, which writes its own log file, and what they print in
the test JVM is a dozen lines per class from before the first callback.

Which window the third one covers is worth knowing, because it was found the hard way.
These modules run under JBoss LogManager, installed by the Surefire
property `java.util.logging.manager`, and a handler of that log manager holds the
`System.out` which existed when the handler was created. Replacing `System.out`, which
is all the extension used to do, therefore reached nothing logged through it: the
augmentation line of a `QuarkusProdModeTest` and every Testcontainers line of the
Camunda 8 Quarkus tests went straight into the log of a green build.

The extension now hands every stream handler it finds below the root logger, nested
handlers of `QuarkusDelayedHandler` included, a stream which resolves `System.out` at
write time. So the handler follows the capture and the failure replay keeps working,
which switching the console handler off would have cost. What it cannot cover is output
written BEFORE its first callback, since there is no handler to redirect yet: a static
initializer which starts a container is the case in the Camunda 8 adapter, and only
configuration reaches that. `JbossLogManagerCaptureTest` in
`integration-tests/deployment-integration-tests` holds the mechanism; removing the
redirection makes it fail with the marker printed to the console.

## The store of processed task deliveries

`io.vanillabp.integration.runtime.delivery` implements the core's `TaskDeliveryLog`
(the inbound counterpart of the outbox) twice, registered by the build step
`buildTaskDeliveryLog` along the capabilities present (`quarkus-agroal`,
`quarkus-mongodb-client`) and kept by `preserveTaskDeliveryLogBeans` - the beans are
looked up per aggregate at runtime, never injected by application code.
`QuarkusTaskDeliveryLogResolver` picks one per workflow aggregate, and as with the
outbox a mixed-persistence application has to attribute aggregates itself
(`TaskDeliveryLogAware`): Quarkus has no platform-side knowledge of which persistence
manages an aggregate.

- `JdbcTaskDeliveryLog` acquires its Agroal connection within the running JTA
  transaction, so it is enlisted there. The SQL and the portable DDL of table
  `VANILLABP_TASK_DELIVERY` live in the core (`JdbcTaskDeliveryStore`), shared with
  Spring Boot. `recordOfTask` and `markTaskClosed` go to the same store: they are what lets
  a task operation elect its BPMS from the record instead of asking one (decision 30).
- `MongoTaskDeliveryLog` writes into `vanillabp-task-deliveries` of
  `quarkus.mongodb.database`. MongoDB is no JTA resource, so the record is written
  immediately and deleted again from an interposed synchronization when the transaction
  ends in anything but a commit - the same best-effort compensation `MongoPhaseTwoOutbox`
  does for its entries. Next to the index the retention reads it creates one on `taskId`,
  which is what `recordOfTask` reads by. `MongoTaskDeliveryLogTest` holds the record and
  its compensation (`aRecordIsWrittenAndReadBack`, `aRolledBackTransactionLeavesNoRecord`,
  `theRecordOfATaskAnswersTheElection`, `expiredRecordsAreDeleted`).
- Both observe the `StartupEvent` to create their schema (unless
  `vanillabp.outbox.create-schema` is disabled) and to start the core's
  `TaskDeliveryRetentionCleanup`, which calls `cleanUpExpiredRecords` per
  `vanillabp.outbox.retention`.
- The same run refreshes the records of the tasks which are still open: the core
  collects the keys the BPMS redelivered, `cleanUpExpiredRecords` writes them before it
  deletes anything, and the JDBC store batches while the MongoDB one bulk-writes.
- The attribution per aggregate is held by `QuarkusStoreAttributionTest`
  (`eachAggregateGetsTheStoreOfItsPersistence`, `awareBeansWin`,
  `aMismatchingSingleDefaultIsRefused`).
- `JdbcPhaseTwoOutboxDispatcher` creates its table the same way the core's delivery store does,
  including the answer to two instances creating it at the same moment: a refused DDL asks the
  metadata again through a connection of its own and stays quiet where the table is there now.
  See the migration adapter's README for why this is not a question of SQL states.

## What is published where

Both contributions follow the recipe the election cache's meters established: the runtime
module declares an optional dependency (`micrometer-core`, `microprofile-health-api`), the
producer references its types, and a build step registers the producer only when the matching
EXTENSION is on the deployment classpath. The extension's processor class is the signal, not
the presence of the API classes: those can arrive transitively without the extension, and then
nothing produces the `MeterRegistry` the producer needs.

- `VanillaBpMetricsBuildStepProcessor` looks for `io.quarkus.micrometer.deployment.MicrometerProcessor`
  and registers `VanillaBpMetricsProducer`.
- `VanillaBpHealthBuildStepProcessor` looks for
  `io.quarkus.smallrye.health.deployment.SmallRyeHealthProcessor` and registers
  `VanillaBpReadinessCheck`.

Both beans are `setUnremovable()`: Micrometer resp. SmallRye Health collect them at startup,
nothing injects them. `ObservabilityTest` asserts the meters and the readiness answer of a
booted application, `OutboxMetricsTest` the backlog gauge.

The pending-entry gauges of the outbox stores are registered by a `StartupEvent` observer with
`@Priority(APPLICATION + 800)`, which is later than the outbox dispatchers' own observers. They
create their table on startup, and a store asked before its table exists cannot count, so
without the priority the gauge would silently never appear
(`OutboxMetricsTest#outboxReportsItsBacklogAndItsDispatches`).

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
