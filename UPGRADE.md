# Upgrade notes

Documents changes that were necessary when upgrading major dependency versions,
so the reasoning can be looked up later (e.g. when upgrading BPMS adapters or
applications built on VanillaBP). What an application on Camunda 7 has to configure on top of
this is in
[that adapter's own file](https://github.com/vanillabp/camunda7-adapter/blob/main/UPGRADE.md).

## An inherited @WorkflowService means the same on both platforms (2026-09-06)

`@WorkflowService` is `@Inherited`, so a class extending an annotated class is a workflow service,
and the class VanillaBP works with is the SUBCLASS: its archive decides the workflow module, its
simple name is the BPMN process id where the annotation names none, and its handler methods are the
ones it offers, the ones it inherited among them. Version 1 read it that way on Spring Boot, and
version 2 reads it that way on both platforms.

**A Spring Boot application upgrading from version 1 notices nothing about this.** The discovery
registered the class of the bean in version 1 and still does, and the BPMN process such a class
serves is the one it always served.

What it does notice is a **report about handler methods VanillaBP cannot see**, written once per
workflow service class while the application starts. Two shapes end up there: a `@WorkflowTask`,
`@WorkflowStartedByBpms` or `@WorkflowEnded` method which is not public, and a method overriding an
annotated one without repeating the annotation, which Java never inherits. Neither was ever wired,
in version 1 no more than now, and both used to surface as the wiring validation asking for a method
the developer can point at in their own source. The report names the method and the class it is
declared in, and says what to do about it. VanillaBP does not serve such a method: reflection could lift the
visibility of a handler, and which methods a class offers is the decision of whoever wrote it.

**For a Quarkus application built against a 2.0 snapshot**, and only for that reader, since version
1 has no Quarkus support at all: the build used to register the class the annotation SITS on, so a
workflow service inheriting its annotation changes the BPMN process id it serves where it relied on
the class-name convention, and changes its workflow module where the base class sits in another JAR.
Both are what the same source already produced on Spring Boot. An application with two subclasses of
one base gets two workflow services where it used to get an ambiguous lookup at the first task
delivery; where the base names no `bpmnProcess`, the two of them name two processes for one workflow
aggregate and the build says so. A class inheriting the annotation has to be a CDI bean of its own
now, because a bean of a subclass is a different workflow service.

## @WorkflowService on an interface ends the start with a message (2026-09-06)

`@WorkflowService` belongs on the class holding the `@WorkflowTask` methods of one workflow
aggregate, and until now the two platforms disagreed about what happens when it sits on an
interface instead. Spring Boot's `AnnotationUtils.findAnnotation` searches implemented interfaces,
which `@Inherited` does not, so the implementing bean passed the discovery and the registrar, which
reads the annotation off the class itself, ran into a `NullPointerException`. What reached the
developer was `Could not register ProcessService beans`, naming neither the class nor the
interface. Quarkus took the other wrong turn: Jandex reports the interface as the annotated type,
so the interface BECAME the workflow service and the `@WorkflowTask` methods declared in it served
the tasks, invoked on an instance of the implementing class, while the handler methods of that
class carried nothing at all, because Java does not inherit a method annotation from an interface.
One source file, two behaviours.

**Both platforms refuse it now**, Spring Boot while the application starts and Quarkus while it is
built, with a message naming the interface, the classes implementing it and where the annotation
belongs. An annotation of the application composing `@WorkflowService` is the same defect and gets
the same refusal: Spring Boot resolves such a meta-annotation and reads the class using it, Quarkus
reports the annotation type itself as the annotated one, so this shape disagrees the same way.

**A Quarkus application which really put its handlers into an annotated interface stops booting**
and moves the annotation, together with the handler methods, onto the class. Nothing else changes:
a class carrying the annotation itself and a subclass inheriting it from an annotated superclass
are what they always were, and a declaration meant for several classes belongs on a common
superclass, which is what `@Inherited` is for.

Independently of the refusal, **the registrar's own failure names something**. The loop building
the `ProcessService` beans always knew which class and which workflow aggregate it was on, and
`Could not register ProcessService beans` said none of it - which is the message every future
defect in that loop would have produced. It now names the class whose annotation could not be
read, respectively the workflow aggregate and the classes declaring it.

## A BPMN process nobody serves no longer stops the boot (2026-09-04)

A BPMN file goes to the BPMS as a whole, so a file which declares two executable processes
deploys both. The wiring validation then asked BOTH of them for their `@WorkflowTask`
methods, and the one no `@WorkflowService` class claims had none, so the application did
not start. The message asked for a workflow service responsible for that BPMN process,
which is the right sentence for a process the application means to serve and the wrong one
for a process it does not: a called process a modeller drew next to the calling one, or a
process which moved out of the application while its model stayed in the file.

**Such an application boots now**, and the deployment reports the processes of the
workflow module which no `@WorkflowService` class claims in one WARN, naming each process,
the file it came from, what it costs (a workflow of it can be started and gets no further
than its first task, because no `@WorkflowTask` method serves it) and the two ways out:
write the workflow service, or take the process out of the file.

**Where a workflow service DOES claim the process, nothing changed.** A task of it without
a matching method ends the boot with the message it always had, and a `@WorkflowTask`
method matching no task of any process of its module still ends it too. An unclaimed
process is never compared against methods at all, so it can neither excuse nor hide one.

**The case to know about is a Spring Boot application** whose `@WorkflowService` class is
on the classpath but is no bean, because its profile is not active or nothing scanned its
package. Its BPMN process is deployed with nothing to serve it, and until now the wiring
validation ended the boot over it, which was the loudest symptom such a class had. That
symptom is a WARN from now on: it names the process and the file, and the application runs.
An application which relied on the failing boot to catch a scanning mistake has to read the
report instead. Quarkus is not affected, it decides its bean set while it builds and refuses
the build of an application whose workflow service is no bean, with a message of its own.

An adapter needs no change. `WorkflowTaskWiring` gained one method with a default,
`bpmnProcessesWithoutWorkflowService(module)`, which the core asks itself once a module
finished deploying; an adapter neither implements nor calls it. What an adapter should keep
doing is calling `validateTaskWiring` for every executable process of a file, the unclaimed
ones included - that call is what makes the report complete.

## A renamed BPMN process is checked like every other one (2026-09-04)

A workflow module may rename a BPMN process and keep serving the workflows which still run under
the old name. The declaration for it has existed since version 1 and it worked at runtime, since
the handler registry follows the ids a `@WorkflowService` DECLARES rather than the ids a BPMN file
carries:

```java
@WorkflowService(
        workflowAggregateClass = OrderApproval.class,
        bpmnProcess = @BpmnProcess(bpmnProcessId = "OrderApproval"),
        secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = "order_approval", version = "1-3"))
```

What did not happen was the startup check for old process versions. It asks the BPMS about the
versions of a process the application just deployed, and nothing is deployed under the old id, so
the versions the running workflows are on were the ones nobody looked at. That is the gap this
closes: the core asks each adapter of the workflow module what its BPMS holds for a declared id
nothing arrived under, and every verdict of the check applies to it.

**The first boot after this upgrade says more than before** where an application already renamed a
process. A task of an old version which no `@WorkflowTask` method serves is a WARN, and an ERROR
where workflows still run on that version - the same reports the check writes about the older
versions of any process. A count of the workflows still running under the old id arrives as an
INFO which says that they keep being served and when the declaration can go. And where the BPMS
holds no version at all under the declared id, one WARN says so and names the ids the module
deploys: after a rename that is what the old id looks like once its last workflow has ended, and
it is also what a typo looks like.

**Mind the numbering when you fade those versions out.** Every BPMN process id is counted from 1
by the BPMS, so version 2 of the old id and version 2 of the new one are different models.
`outfaded-versions` for the old versions belongs at the workflow level, under the OLD id
(`vanillabp.workflow-modules.<module>.workflows.<old id>.adapters.<adapter>.outfaded-versions`).
Written at adapter level the same specification covers the version the application just deployed
under the new id, and the boot ends with the message about fading out the deployed version.
Configuring properties for a BPMN process no resource of the module carries is still only a WARN,
and it now names the declared id as one of the two cases where that is intended.

**The warning about a method which never runs is one per workflow module now**, where it used to
be one per BPMN process and adapter. A method is registered once per process its class declares,
each with the version range of that declaration, so a method kept for the old id serves none of
the new id's versions - and the old wording called exactly that method dead and asked for it to be
removed. An application whose log carried such a warning for a method serving several processes
will not find it any more; a method which serves no version of any of its processes is reported as
before.

For an adapter nothing is mandatory: `AdapterDeploymentService#processVersionCatalogOf` answers
`null` by default, and the check stays as silent about a renamed id as it was before for a BPMS
whose adapter does not implement it.

**Whether the workflows under the old id are SERVED is the adapter's business**, and it is not the
same everywhere: a Camunda 8 worker asks for a task definition and serves those workflows as long as
that name does not carry the process id, while the Camunda 7 adapter wires the tasks of the models it
deploys and does not reach the old id yet. Both say which of the two you are in while the application
starts. The way which asks nothing of a BPMS is to keep deploying the old model under its old id until
its workflows have ended, and the wiki page
[Renaming a BPMN process](https://github.com/vanillabp/adapter-platform-integration/wiki/Renaming-a-BPMN-process)
walks through both.

## The election cache's own numbers moved to a prefix of their own (2026-09-04)

Five meters were renamed, and if you built a dashboard on a 2.0 snapshot this is the one
change in it:

|                       before                        |                         now                          |
|-----------------------------------------------------|------------------------------------------------------|
| `vanillabp.workflow.adapter.cache.size`             | `vanillabp.inmemory.election.cache.size`             |
| `vanillabp.workflow.adapter.cache.size.ended`       | `vanillabp.inmemory.election.cache.size.ended`       |
| `vanillabp.workflow.adapter.cache.evictions`        | `vanillabp.inmemory.election.cache.evictions`        |
| `vanillabp.workflow.adapter.cache.evictions.unused` | `vanillabp.inmemory.election.cache.evictions.unused` |
| `vanillabp.workflow.adapter.cache.lost.hints`       | `vanillabp.inmemory.election.cache.lost.hints`       |

`vanillabp.workflow.adapter.cache.hits`, `.misses` and `.ended.marks` stay where they
are. The line between the two is who can produce the number: the three which stay are
counted by VanillaBP around whatever cache is in use, the five which moved can only come
from the in-memory cache itself.

Which is why they moved. With a shared cache in use the two sizes reported NaN and the
three counters reported a zero which could never become anything else, and a dashboard
built on them showed a cache which looked broken while it worked. Now every
implementation publishes what it knows under a name which says which implementation it
is, so `vanillabp.*.election.cache.*` finds them all and none of them lies.

An application which reads the statistics from Java sees the same split:
`WorkflowAdapterCacheStatistics` has lost `getSize`, `getEndedSize`, `getEvictions`,
`getEvictionsBeforeFirstUse` and `getLostHints`, and
`InMemoryWorkflowAdapterCache#getStatistics()` is where those live now, without the
`OptionalInt` which only existed because a foreign cache could not answer.

## Decision tables of a workflow module are deployed with it (2026-09-03)

A workflow module may put `.dmn` files next to its BPMN files, and the boot deploys them to the
same BPMS, in the same deployment, with the same tenant. Before, a decision table had to be
deployed by hand or by a second mechanism.

**An application which already keeps DMN files at its `resources-location`** and deploys them by
other means now deploys them twice. Both Camunda engines answer a redeployment of an unchanged
file by keeping what they have, so this is usually invisible - but an application which deploys a
DIFFERENT version of a decision by its own mechanism has two writers now and has to pick one.
Nothing else changes: an application without DMN files there notices nothing.

**The files are looked for** at the module's resources location of that adapter, exactly where its
BPMN files are looked for (`vanillabp.workflow-modules.<module>.adapters.<id>.resources-location`
and its fallbacks). A module with a decision table but without an executable process is skipped
as it was before, and the warning about it now says that its decision tables are not deployed
either.

**Where a workflow module is scoped by prefixes** (`name-clash-avoidance: use-prefix`), the
decision ids of those files are rewritten like the process ids, and the reference of a business
rule task is rewritten with them. A business rule task pointing at a decision which this module
does NOT deploy - one deployed by hand, or owned by another application - therefore breaks under
that mode: the reference is rewritten and the decision in the BPMS is not. Deploy the decision
with the module, or scope the module by tenant (`by-adapter`, the default), where nothing is
rewritten at all.

**For an adapter written before this:** `AdapterDeploymentService#readDmn` is a `default` which
takes no file and warns once per file naming the adapter, the file and the workflow module. An
adapter compiles and runs unchanged; an application bringing a decision table to that BPMS is
told rather than left with a business rule task nobody deployed anything for.

## The outbox survives an outage longer than five minutes (2026-09-03)

Two changes to how a failed dispatch is retried, one of them a changed default, both of them
behaviour an application notices without configuring anything.

**The retry distance grows instead of staying fixed**, and the attempt budget grew with it:

|                 Property                 |                      Before                      |                                         Now                                         |
|------------------------------------------|--------------------------------------------------|-------------------------------------------------------------------------------------|
| `vanillabp.outbox.attempt-frequency`     | the distance between every two attempts, `PT30S` | the distance to the FIRST retry, unchanged at `PT30S`, doubled per attempt after it |
| `vanillabp.outbox.max-attempt-frequency` | -                                                | new, `PT5M`, the cap the doubling stops at                                          |
| `vanillabp.outbox.block-after-attempts`  | `10`                                             | `50`                                                                                |

With the old values a BPMS which was away for six minutes - a cluster upgrade - turned every
operation pending in that window into a blocked row somebody had to repair by hand. The new ones
try for about four hours, and the cap means a BPMS which comes back is noticed within five
minutes however long it was gone. Nothing is renamed and nothing is removed, so an application
which configured the two old properties keeps a valid configuration; what it gets is the growing
distance between its own attempts.

**An application which wants the old behaviour** sets `max-attempt-frequency` to the same value
as `attempt-frequency` - the doubling is then capped where it starts - and `block-after-attempts`
back to `10`.

**Gruelbox on Spring Boot keeps its fixed distance.** That store brings a retry policy of its own
which knows one interval, so an application on the JPA default retries every `attempt-frequency`
as before; with the new attempt budget it tries for twenty-five minutes rather than five. The
per-store table of the platform wiki pages names this difference together with the other two it
already carries.

**A blocked entry no longer blocks its own repetition.** Blocking now releases the deduplication
key on the stores VanillaBP owns (`DEDUP_KEY` respectively `dedupKey` is set to the entry's id,
exactly as a dispatched entry sets it), so the application can plan that operation again. Before,
the blocked row held the key and the store refused the identical operation for as long as the row
sat there - an answer indistinguishable from a correct deduplication, which is why one failed
operation silenced its own repetition until an operator deleted the row.

Two consequences for operations. A repair of a blocked entry has to expect a second entry for the
same operation, written after the block, and reopening the old one then carries the operation to
the BPMS twice - which the idempotency key never protected against anyway (it is about scheduling
once, not about arriving once). And the counter `vanillabp.outbox.discarded` stops rising for this
case, because there is nothing left to discard; where it still rises, the cause is the one it
always had.

## Three SPIs, three artifacts (2026-09-01)

The SPI a BPMS adapter implements and the one an extension of the deployment pipeline
implements used to share a module, and the module carrying the interfaces business code
implements was called after the reader rather than after its artifact. Both are sorted out,
and the coordinates change with it:

|                               Before                                |                        Now                        |
|---------------------------------------------------------------------|---------------------------------------------------|
| `io.vanillabp:vanillabp-integration-spi` (directory `business-spi`) | unchanged coordinate, directory `integration-spi` |
| `io.vanillabp.adapter:migration-adapter-spi`                        | `io.vanillabp:vanillabp-adapter-spi`              |
| -                                                                   | `io.vanillabp:vanillabp-extension-spi` (new)      |

**No package and no signature changed.** `io.vanillabp.integration.adapter.spi.*` and
`io.vanillabp.integration.extension.spi.ExtensionWiringService` are where they were, so an
adapter or an extension changes its dependency and nothing else. Both new artifacts are
managed by `io.vanillabp:vanillabp-bom`, so a build importing the BOM drops the
`<version>` as well.

**What an adapter has to do:** replace the dependency on
`io.vanillabp.adapter:migration-adapter-spi` with `io.vanillabp:vanillabp-adapter-spi`.
`vanillabp-extension-spi` arrives with it transitively, because `AdapterDeploymentService`
extends `ExtensionWiringService`.

**What an extension has to do:** an extension which only implements
`ExtensionWiringService` now depends on `io.vanillabp:vanillabp-extension-spi`, an artifact
with no dependency at all, instead of pulling the whole adapter SPI it never implements.

**Why the middle row is a rename and not a deprecation:** the adapter SPI has no released
version yet, so nothing outside these repositories is compiled against the old coordinate.

## The delivery record answers which BPMS holds a task (2026-08-30)

Completing or cancelling a task used to ask every configured BPMS which of them holds it, once per
call. VanillaBP reads its own delivery record of that task instead now: it names the adapter which
delivered the task and says whether the application has closed it since. On Camunda 8 that saves
one round trip per task operation, the one the adapter's phase one repeats a moment later anyway.
Where no record answers, the walk over the adapters runs exactly as before - so Camunda 7 loses
nothing, and neither does an application which switched `deduplicate-deliveries` off.

**The table VANILLABP_TASK_DELIVERY gets two columns**, `TASK_ID VARCHAR(255)` and
`TASK_CLOSED_AT` (the timestamp type of the existing `RECORDED_AT`), plus the index
`VANILLABP_TASK_DELIVERY_TASK` on `TASK_ID` - the record is read by the task once per operation,
and without the index that read costs more than the round trip it saves. Both columns are nullable:
a record written before them is simply not part of an answer.

**An application which lets VanillaBP create its schema** (`vanillabp.outbox.create-schema`, the
default) gets them on a table it creates from now on. A table which exists already is NOT altered -
VanillaBP creates tables, it does not migrate them - so the startup names the two columns, the
statements which add them and the artifact which does it, and stops there rather than writing into
a table it does not understand.

**An application which manages its own schema** applies the new changeset of
`io.vanillabp:vanillabp-schema` (`vanillabp-task-delivery-task-2.0.0` in
`vanillabp/schema/changelog.xml`, respectively the regenerated Flyway file). It is a changeset of
its own rather than a change to the one which creates the table, because that one was applied
already.

**A store the application wrote itself** keeps working unchanged: `TaskDeliveryLog` gained
`recordOfTask` and `markTaskClosed`, both `default`, answering "nothing" and doing nothing. An
application which implements them saves the round trip; one which does not pays what it always
paid.

## The end of a workflow marks its election hint instead of refreshing it (2026-08-30)

`WorkflowAdapterCache` has a fourth method, `putEnded(workflowModuleId, bpmnProcessId,
workflowAggregateId, adapterId)`, and it is a `default` falling back to `put`. **An
application which brought its own cache bean does not have to change anything**: without
the override the hint of an ended workflow lives as long as any other one, which is what
happened before.

**What changed for everybody.** The notification of a workflow's end used to be an inbound
delivery like any other and wrote the hint anew, so the entry of a workflow which had just
become uninteresting got a fresh hour. It is marked now, and so is an entry whose probe
answers `COMPLETED`. A marked entry is still read, so an operation which crossed the end
stays the warned no-op it was; it simply leaves the cache after
`vanillabp.workflow-adapter-cache.ended-time-to-live` (five minutes, validated to be
shorter than `.time-to-live`).

**What is new to configure.** `vanillabp.workflow-adapter-cache.release-on-workflow-end`
(default `false`) has the BPMS report the end of a workflow for the cache's sake. It is
the third consumer of that signal next to a `@WorkflowEnded` method and
`vanillabp.delivery.release-on-workflow-end`, and switching it on attaches a listener
respectively a worker to every deployed process. Where one of the other two asked for the
end already, the cache is served for free. A cluster sharing its cache is who this is
meant for.

**Two new meters**, next to the ones the cache already publishes:
`vanillabp.workflow.adapter.cache.ended.marks` (hints marked) and
`vanillabp.workflow.adapter.cache.size.ended` (marks currently held). An
application-provided cache reports the first one and, like every other size, not the
second.

## The pair of methods per operation is gone (2026-08-29)

The entry below kept the eighteen phase methods of `MigratableProcessService` as `default` methods
behind a compatibility bridge, so the three adapters could move to handlers one pull request at a
time. They have moved, so the bridge has no user left and both are gone: `LegacyPhaseOperations`,
`legacyPhaseOperations()`, and `startWorkflowPhaseOne`/`…PhaseTwo` with their eight siblings.

**`phaseOperations()` is abstract now.** An adapter says what it serves or it does not compile,
which is where the compiler's insistence went that the pair of methods used to carry. The boot
check stays as the second line: an adapter whose map is missing an operation every adapter has to
serve is refused, naming the adapter and what is missing.

**`serves(PhaseOperation)` is gone as well.** It had become `phaseOperations().containsKey(...)`,
which is what the one caller asks now.

**What an adapter has to do.** Nothing, if it already answers `phaseOperations()` - which the
three adapters VanillaBP ships do. An adapter still written against the pair of methods moves its
bodies into handlers; the entry below shows the shape.

## An operation is defined once, and an adapter contributes a handler for it (2026-08-29)

Adding one two-phase operation used to touch five places in the core and three adapters: a pair of
methods on `MigratableProcessService`, a constant carrying its name and key rule, a typed
`schedule*` default on `PhaseTwoOutbox`, a registration in the `PhaseTwoRouter`, and an `execute*`
body in `MigrationProcessService` which was almost the one next to it. Four of those places knew
nothing an operation needed to decide - they only repeated it.

**An operation is now one `PhaseOperation`.** The record carries its persisted name, its
idempotency-key rule, the `Election` saying which BPMS serves it, whether every adapter has to be
able to serve it, whether the activation the call was planned in travels with it, and the words it
names itself with in a log line or an exception. `MigrationProcessService` runs every operation
through one `execute` and one `executePhaseTwo`, the router dispatches by name, and the outbox
stores name, args and key without knowing any of them. Adding an operation is a constant plus a
handler per adapter, which `AddingAnOperationTest` demonstrates by adding one.

**An adapter contributes handlers.** `MigratableProcessService#phaseOperations()` answers a
`PhaseOperationHandler` per operation - `phaseOne(PhaseOneRequest)` and
`phaseTwo(PhaseTwoRequest)`, the request carrying the workflow and the operation's arguments
behind named accessors (`taskId()`, `messageName()`, `correlationId()`, `activationId()`, …).

**No adapter has to change yet.** The default of `phaseOperations()` is a handler per core
operation calling the methods that operation used to be, so an adapter which changes nothing
behaves exactly as before. An adapter moving one operation at a time merges its own handlers into
`legacyPhaseOperations()`:

```java
@Override
public Map<PhaseOperation, PhaseOperationHandler<A>> phaseOperations() {
  final var operations = new HashMap<>(legacyPhaseOperations());
  operations.put(PhaseOperation.COMPLETE_TASK, completeTask);
  return operations;
}
```

The bridge and the eighteen methods it calls go together, once the three adapters VanillaBP ships
have moved.

**What an adapter author has to know.** The pair of methods per operation is `default` now instead
of abstract, so the compiler no longer insists on them, and an adapter written against handlers is
not made to implement methods it does not use. The map is what states which operations an adapter
serves, and the boot refuses an adapter missing one every adapter has to serve, naming the adapter
and what is missing. An operation which is not required of every adapter - `SEND_SIGNAL` and
`AGGREGATE_CHANGED`, because a BPMS may have nothing like them - is simply absent from the map, and
the application asking for one gets a `PhaseOperationNotSupported` naming the adapter and what to
do instead. An adapter still on the bridge serves every core operation, and a method it never
implemented says so when it is called, exactly as before.

**Renamed:** `PhaseTwoOperation` is `PhaseOperation`, `PhaseTwoOperationRegistry` is
`PhaseOperationRegistry` and `PhaseTwoOperationDispatch` is `PhaseOperationDispatch` - the type
describes both phases now, not only the second. The persisted names of the operations are
unchanged, so outbox entries written by an earlier version dispatch and deduplicate as they did.
An extension builds its operation with a builder now
(`PhaseOperation.extensionOperation(name).idempotencyKey(…).describedAs(…).build()`) instead of the
two-argument factory.

**Gone:** the nine typed `schedule*` defaults of `PhaseTwoOutbox`. A store implements
`schedule(PhaseTwoCall)` and always did; the defaults were the core's own convenience and the core
builds its calls from the operation now. An application which called one of them - there was no
reason to - builds the call with `PhaseTwoCall.of(operation, …)`.

**An application sees no difference.** `ProcessService` is untouched, and so is everything an
application writes.

## An adapter is registered completely, or it does not come into existence (2026-08-28)

The collaborators the platform hands an adapter used to arrive one setter call at a time, after the
constructor had returned. A registrar which forgot one produced an adapter that deployed its BPMN
files, ran its tasks and never reported a workflow end - with nothing failing anywhere, because the
object was valid and its field was simply null.

**They arrive in one object now.** `AdapterCollaborators` is built by the platform and handed to
the adapter's constructor. Five collaborators are mandatory and a set without one of them throws,
naming the adapter id and what is missing: `WorkflowTaskWiring`, `WorkflowTaskInvoker`,
`NameClashAvoidanceSupport`, `WorkflowAggregateSync` and `PreCommitRegistrar`. Two are handed over
as `Optional` because an adapter has to work without them - `WorkflowEndedInvoker` and
`BpmsInitiatedStartInvoker` - and an adapter built without one gets a WARN naming it, since both
platforms do provide them for every application.

**What an adapter has to change.** Its deployment service and its process service take
`AdapterCollaborators` instead of the individual collaborators, and their `set...` calls for those
go away. The platform builds the object:
`AdapterBeanRegistrarSupport.collaborators(supplierContext, adapterId)` on Spring Boot,
`AdapterCollaboratorsSupport.collaborators(...)` on Quarkus. What an adapter resolves from its own
configuration - a job timeout, a retry backoff, the variables a worker fetches - and what its own
extension contributes stay its own constructor arguments; the object carries nothing of that.

**An application sees no difference.** Nothing in its configuration or its code changes; this is
about how an adapter is wired to the platform. Decision 28 has the reasoning.

## The task-invoker SPI is split into wiring and runtime (2026-08-28)

`WorkflowTaskInvoker` carried thirty methods: wiring-time duties an adapter calls while it deploys,
and runtime duties its worker threads call. Which of them were mandatory, and when, was written in
the javadoc and nowhere else - and Camunda 7 forgot one of them for a year, so a typo in a
`@WorkflowTask(taskDefinition = ...)` stayed silent until a workflow reached the task.

**Two interfaces now.** `WorkflowTaskWiring` holds what an adapter calls while deploying
(`validateTaskWiring`, `taskParameterNames`, `workflowTaskCompletesAsynchronously`,
`workflowsShareTheWorkflowAggregate`, `reportConcurrentTokenElements`,
`unsharedWorkflowAggregateProperties`, `registerProcessVersions`, `registerDeployedVersion`, and
`resolveWorkflowAggregateIdName`, which answers the same at both times and is therefore on both).
`WorkflowTaskInvoker` keeps the runtime ones (`invokeWorkflowTask`,
`syncedWorkflowAggregateValues` in both flavours, `workflowTaskHandlerExists`,
`resolveWorkflowAggregateIdName` and the two deprecated fallbacks). The core's
`WorkflowTaskRegistry` implements both, so the platform hands out the same object and an adapter
changes the TYPE of its fields, not where they come from: deployment code takes
`WorkflowTaskWiring`, worker threads take `WorkflowTaskInvoker`.

**Two calls became the core's duty.** `validateNoUnwiredWorkflowTaskMethods(module)` and
`resolveProcessVersions(module)` are module-level and need nothing an adapter knows, so the core
runs them itself once the last adapter of a workflow module finished deploying. **Adapters must
remove their own calls** - calling them twice is harmless but pointless, and the point of the story
is that nobody has to remember them. What stays with the adapter is `registerDeployedVersion`: only
it knows which version its BPMS ended up with.

**An application sees one difference.** A `@WorkflowTask` method matching no task of any BPMN
process of its module now ends the boot on EVERY adapter, Camunda 7 included, with the message which
names the method and the fix. An application which lived with such a method (a leftover after a
model change) has to remove it or widen its annotation.

## An adapter says whether it can locate workflows, and the start instant is named honestly (2026-08-28)

Two small corrections of the adapter SPI, made before another team implements against it.

**`canLocateWorkflows()` is new, with a `default` answering `true`.** An adapter which cannot ask
its BPMS whether it holds a workflow - a Camunda 8 cluster without secondary storage, the
Process-Engine-API, which has no query API at all - answers the election optimistically. That is
right while it is the only BPMS configured, and a guess as soon as it is not: the walk stops at the
first `ACTIVE`, so the guessing adapter takes the operations of every adapter behind it in the
list. Such an adapter answers `false` here, and the core refuses to boot a workflow module which
prioritizes it next to another adapter. An adapter which asks its BPMS needs to change nothing.

The answer is fetched after the adapters deployed and before workflow processing starts, because
an adapter may only learn what its BPMS can do while deploying.

**The refusal can be accepted.** `vanillabp.election.guessing-adapters: ACCEPTED` (or
`vanillabp.workflow-modules.<id>.election.guessing-adapters`) turns the boot failure into a WARN.
The default is to refuse.

**`BpmsInitiatedStartContext.getTriggerTime()` is now `getStartInstant()`.** The old name promised
what only some BPMS deliver: the time the engine SCHEDULED the start for. Camunda 7 does not hand
that to a listener, so its adapter reported the moment of the notification - an adapter
contradicting the method it implements. The javadoc now names the scheduled time as the ideal and
the notification's moment as the documented fallback. Adapters rename the method they implement;
nothing else changes, and `BpmsStartTrigger.time()`, which the application sees, was never called
a trigger time.

## The two-phase switch of the adapter SPI is gone (2026-08-28)

`MigratableProcessService#needsTwoPhaseCommitForStartingWorkflows()` no longer exists.
Phase one asks and phase two acts, for every adapter and every operation, and there is no
longer a way to say otherwise (decision 26 of this repository's `DECISIONS.md`).

**Adapters delete one method.** Every adapter answered `true`, so nothing about their
behaviour changes; the `@Override` simply has to go, and with it any prose describing what
the adapter would do in an embedded variant.

**Every application needs an outbox and a transaction, and hears about it at startup.**
`validatePhaseTwoOutboxAtStartup` and `validateTransactionRunnerAtStartup` used to skip an
application whose first-priority adapter answered `false`. They no longer skip anything: an
application without a resolvable `PhaseTwoOutbox`, or with nothing VanillaBP can run a
transaction in, ends its boot with the guiding message which names the remedies. Applications
running any of the shipped adapters are unaffected - they needed both before, because all
three adapters answered `true`.

**The message about a missing outbox reads differently.** It used to start with "Adapter 'x'
requires a two-phase commit for starting workflows"; it now says that everything the process
sends to its BPMS is dispatched through a `PhaseTwoOutbox`. A test asserting on the old
wording has to be adjusted.

**Test doubles of a BPMS may need a store now.** A double which pretended to be embedded made
an application boot without an outbox and without a transaction manager, and let it start a
workflow outside any transaction. All three are demanded now, which usually means one more
bean in the test application plus a `@Transactional` on the method calling `startWorkflow` -
the integration tests of this repository carry `TestPhaseTwoOutboxConfiguration` and
`TestTransactionRunnerConfiguration` for the first part.

## The activation of a correlation travels to phase two (2026-08-27)

The activation which planned a message correlation is carried with the outbox entry and
handed to the adapter when the entry is dispatched. Story 141 put it into VanillaBP's own
idempotency key; this is what a BPMS needs which deduplicates messages in a net of its
own.

**Adapters gain an optional method.** `MigratableProcessService#correlateMessagePhaseTwo`
has a seven-argument overload with a `default` forwarding to the six-argument one, which
stays the method an adapter implements. An adapter written before this keeps compiling and
simply ignores the value. An adapter whose BPMS derives a message id of its own - Camunda 8
does - should put the activation into that derivation, or three elements of a multi-instance
call activity reach the outbox as three operations and that BPMS as one message.

**The entries carry one more argument.** `activationId` joins the args of a
`CORRELATE_MESSAGE` entry, absent where the correlation was planned outside any activation.
Args are stored form-encoded in a column which already holds them, so nothing about the
schema changes and no entry has to be migrated.

**Where the key reads it from changed, not what it is.** The derivation used to read the
running activation off the thread; it reads the argument now. The key is byte-identical
either way - `PhaseOperationContractTest` pins both shapes - and the derivation is a pure
function of what a store persists again. An application-owned store building calls through
`PhaseTwoCall.of` itself rather than through `PhaseTwoOutbox#scheduleCorrelateMessage` now
has to put the argument in itself if it wants the activation in the key; the shipped stores
all go through the default method.

## The records of delivered tasks have a retention of their own (2026-08-27)

`vanillabp.delivery.retention` decides how long the record of a processed task delivery is kept. It
used to be `vanillabp.outbox.retention`, and one number governed two windows whose requirements
point in different directions.

**Nothing changes unless you say so.** Where the new property is not set it follows
`vanillabp.outbox.retention`, so an installation which never touched either number keeps seven days
on both halves and an installation which lowered the old one keeps the behaviour it had. Where
exactly one of the two is moved away from the default, the startup says which window applies to
what.

**Who should look.** An installation which lowered `vanillabp.outbox.retention` to keep its outbox
table small. That was shortening a correctness window with the same hand: on the OUTBOX side the
deduplication window ends with the dispatch (see the entry of 2026-08-26 below), so the retention
there only decides how long a dispatched entry stays readable during support, while on the DELIVERY
side it decides whether a redelivery arriving later than that runs your `@WorkflowTask` method a
second time. Those installations can now keep the small table and give the records the period they
need.

**How long is long enough.** Long enough to cover the largest gap between a handler running and the
last redelivery of that work. The part nobody can compute for you is how long the application is
stopped: a stopped application refreshes no record, and the first cleanup run after it starts
deletes what expired meanwhile. No adapter can bound it either - what an adapter knows is how often
it hands unacknowledged work out again, which is minutes to hours and says nothing about the
horizon, so there is deliberately no startup check comparing the two.

**Read globally, unlike its neighbours.** `vanillabp.delivery.release-on-workflow-end` and
`vanillabp.delivery.max-task-age` are overridable per workflow module; the retention is not, because
one cleanup per store deletes by age across the whole table respectively collection.

**The Camunda 8 startup check moved with it.** `async-task-lock-renewal` has to sit clearly below
the retention which keeps the record answering the redelivery that renews the lock, and that is the
delivery retention from now on. An application which lowered `vanillabp.outbox.retention` below its
renewal window and was refused the boot will now boot.

## The key of a message correlation names the activation which planned it (2026-08-27)

Multi-instance siblings of one workflow aggregate stop sharing an idempotency key. A called process
is a secondary workflow of the SAME aggregate, so three elements of a multi-instance call activity
agreed in workflow module, BPMN process and aggregate id, and where the correlation id came from
business data the three correlations were one key: the first one was planned and the other two were
discarded. The key of a `CORRELATE_MESSAGE` now carries what the BPMS calls the running element
instance, so the three are three operations and all three reach the BPMS.

**The key format changed, and only this one.** Where the correlation was planned inside something
the BPMS activated, the key is
`CORRELATE_MESSAGE|<module>|<process>|<aggregate>|<message>|<correlationId>|<activation>`. Planned
anywhere else - a REST endpoint, a scheduler, a thread the handler started itself - it is exactly
the key it was before. No other operation's key changed: a workflow is started at most once per
aggregate whichever activation asks, a task is completed at most once and its task id already names
one activation, and a broadcast signal still carries no key.

**What a pending entry does across the upgrade.** Nothing has to be migrated. An entry written
before the upgrade keeps its key and dispatches under it; an entry written after it carries the
longer key. The only effect of the change is on the entries still waiting at the moment the
application restarts: a correlation of an old shape and one of the new shape no longer deduplicate
against each other, so a correlation which was planned before the restart and repeated inside an
activation afterwards can reach the BPMS twice. The window is the seconds between the restart and
the outbox dispatching what it held.

**Adapters have to answer one more question.** `TaskInvocationContext#getActivationId()` reports the
element instance the BPMS is running - the Camunda 8 element instance key, the Camunda 7 activity
instance id, the task id of the Process-Engine-API. It defaults to `null`, so an adapter written
before this keeps compiling and working, and its multi-instance siblings keep colliding. It is NOT
the delivery id: that one has to stay equal while the BPMS repeats itself, this one has to differ
between two activations of one element. An adapter which reports no delivery id can still report
this, and Camunda 7 is exactly that case.

**A handler which correlates from a thread of its own gets the old key.** The running activation is
bound to the thread the core invoked the handler on, never inherited by threads that handler
creates. Absent rather than failing, deliberately: an application which works today keeps working.

**The warning about a discarded schedule says which case it is.** Inside an activation it names it
and says that this cannot be a sibling; outside any it asks for a varying correlation id as before.

## An idempotency key deduplicates what is planned, not what happened (2026-08-26)

The phase-two outbox used to refuse a scheduled operation whose idempotency key matched ANY entry
it still held, and it holds a dispatched entry until `vanillabp.outbox.retention` passes, seven days
by default. A second, entirely legitimate operation of the same key inside that window was dropped
without a word. From now on the key deduplicates the entries which have not been dispatched yet: a
repetition after the dispatch is a new operation and reaches the BPMS.

**Who has to look.** An application which correlates the same message name with the same correlation
id for one aggregate more than once: a loop asking the same partner again in the next round, or a
multi-instance activity whose correlation id comes from business data. Such a correlation used to
vanish and the workflow waited forever; now it is carried out. That is the good direction, and it is
still a behaviour change: a BPMN model which relied on the second correlation being swallowed sees
it arrive. The same applies to a second `startWorkflow` for one aggregate after the first start was
dispatched.

**What is logged now.** Where a schedule IS discarded, VanillaBP writes a warning naming what was
not planned and both causes it cannot tell apart: a repeated dispatch of an operation which happened
already, or a second legitimate operation lost against one which is still waiting. Two operations
planned in the same unit of work still collide, and multi-instance siblings of one aggregate were
the case a caller could not avoid on its own - the entry of 2026-08-27 above is what fixed that
half, and outside an activation the warning is still the signal to vary the correlation id per round
or element.

**A long aggregate ID no longer fails the schedule.** The derived key is bounded to 250 characters
and hashed beyond that, the same way the task-delivery key has always been bounded (both share
`StoredKey` now). Before this, a composite business key or a URN as aggregate ID produced a key
gruelbox refused outright, and a persistence error surfaced where the application expected a
workflow. An aggregate ID longer than the 1024 characters of the `AGGREGATE_ID` column is still
refused, but now by a message naming the column, the length and the beginning of the ID instead of
a driver-level truncation error.

**The key names its operation now.** `COMPLETE_TASK` and `CANCEL_TASK` of one task ID used to derive
the same key, as did their user-task counterparts. They are distinct by construction from now on.
`START_WORKFLOW_BY_MESSAGE` keeps deriving the plain start's key on purpose: a workflow is started
at most once per aggregate, whichever of the two started it.

**Store schemas changed** (entries of the previous format are not migrated — 2.0.0 is not released):
the outbox table gained a `DEDUP_KEY` column, `NOT NULL` and unique, and `IDEMPOTENCY_KEY` lost its
unique constraint and keeps the derived key for support to read. The column holds the key while the
entry waits and the entry's own ID once it was dispatched, which is what narrows the window; it is
never null because not every database treats two nulls as different values. The MongoDB stores gained
the same field `dedupKey` with a unique index, and the sparse index over `idempotencyKey` is dropped
where it is still there. Applications creating the schema themselves have to apply the changed
changeset of `io.vanillabp:vanillabp-schema`; a database of a SNAPSHOT build has to be recreated.
gruelbox' table is untouched, since its schema belongs to gruelbox: there the store deletes a
dispatched entry when a new operation of the same key arrives, which costs that entry's trail.

**On Camunda 8 the cluster deduplicates too, and longer.** The adapter passes a message id derived
from the same values, so the cluster refuses a second publication for as long as the message
time-to-live lasts, one hour unless the application sets `message-time-to-live`. That net is the
cluster's, no VanillaBP setting shortens it, and the adapter's log message says so instead of
calling every refusal a redelivery.

## Spring Boot: a workflow service is found because it is a bean (2026-08-26)

VanillaBP no longer scans the classpath for classes annotated by `@WorkflowService`. It walks the
bean definitions of the application instead and keeps the ones whose class carries the annotation.
An application starts noticeably faster for it: in the demo this was measured on, the scan read
42 816 class resources to find one workflow service and cost 15.9 seconds of a 24.4 second start
under `mvn spring-boot:run`, 4.3 of 9.0 seconds from the packaged JAR.

**Who has to look.** An application whose workflow service is no Spring bean. It used to get a
`ProcessService` registered and now does not, and since the class could never have served a task
anyway (the handler object is resolved through the bean factory), what such an application meets
is the wiring validation ending the boot:

```
Task wiring of BPMN process 'loan_approval' of workflow module 'loan-approval' is incomplete!
BPMN tasks having no matching @WorkflowTask method:
  - task 'Activity_RetrieveCreditRating' (task definition 'retrieveCreditRating'): ...
```

The fix is the one the message asks for: make the class a bean, with `@Service` or with a `@Bean`
method. Everything else stays as it was, `@Service` on a workflow service being what applications
write anyway.

Two details worth knowing. A `@Bean` method has to declare the workflow service class as its
return type, because the discovery reads the type of the definition and never the instance behind
it. And a workflow service which only ONE profile registers is found only while that profile is
active, which is the point: the class list is now the one of this run.

## `@BpmnProcess(version = ...)` has an effect now (2026-08-26)

The `version` attribute of `@BpmnProcess` exists since VanillaBP 1 and nothing ever read it, in
version 1 nor in version 2 up to here. From now on it is the fallback of `@WorkflowTask`,
`@WorkflowStartedByBpms` and `@WorkflowEnded`: a method naming no `version` of its own serves the
range of the `@BpmnProcess` its process was declared with, and a method naming one keeps it word by
word, without the class range narrowing it.

```java
@WorkflowService(
        workflowAggregateClass = LoanApproval.class,
        bpmnProcess = @BpmnProcess(bpmnProcessId = "loan_approval", version = "<10"))
public class LoanApprovalUpToTen {

  @WorkflowTask                   // serves versions below 10
  public void retrieveCreditRating(final LoanApproval loanApproval) { ... }

}
```

**Who has to look.** An application which wrote the attribute in version 1, believing it worked. It
had no effect, so its methods served every version; now they serve what the class says, and the ones
outside that range stop being called. That is a silent behaviour change for exactly the applications
which meant it, which is why it gets an entry of its own. Check every `@BpmnProcess` your
application declares: where the range does not describe what the class should serve today, remove it
or correct it. An application which never wrote the attribute is unaffected - the default `*` leaves
every method serving every version, as before.

**A method which inherits a range is restricted.** So a delivery whose version the BPMS did not
report (the Process-Engine-API, and any BPMS which cannot tell) reaches it no more than it reaches a
method naming a range itself. Serving such a delivery needs a method whose CLASS names no version
either. The failure message says where the range came from, because the method it complains about
carries no attribute at all.

**Two classes for one process** are what the feature is for: one per generation of the model. They
boot as long as their ranges are disjoint, and overlapping ranges end the start naming both classes
and both declarations. What has not changed is that exactly one `ProcessService` exists per workflow
aggregate: several classes may declare the same `bpmnProcess`, different primary processes for one
aggregate are still ambiguous.

## The open tasks an upgrade leaves behind are counted at startup (2026-08-25)

Two SPI questions with defaults, one startup message. No configuration and no schema changes.

**Why.** VanillaBP answers a repeated delivery from the record it wrote when the handler ran, which
is what the wiki promises. A task which was already open before this application first ran has no
such record, so its next delivery runs the `@WorkflowTask` method a second time - which is what
version 1 did for every delivery, and what an upgrade therefore leaves behind for exactly the tasks
most likely to be in flight. Nothing reported it, because from the core's point of view a delivery
it has never seen is simply a new one.

**Nothing can be repaired, so nothing is.** A record would have to claim that the handler ran and
left the task open. An activated job which is still there may equally be a handler which crashed
halfway, no BPMS can tell the two apart, and the wrong guess skips business code which never ran.
So the count is the whole feature, together with the sentence that the guards in the handlers carry
the case until it reaches zero.

**What changed:**

- `TaskDeliveryLog#hasOpenRecords(workflowModuleId, bpmnProcessId)`, `default null`. Unlike
  `adapterIdsOfOpenTasks` it distinguishes "none" from "cannot say", because here the empty case is
  the interesting one. The shipped JDBC and MongoDB stores answer; the gruelbox-based outbox is
  unaffected, this is the delivery log;
- `MigratableProcessService#openTaskCount(workflowModuleId, bpmnProcessId)`, `default null` - how
  many tasks the BPMS holds open for one process, asked once at startup and never at runtime.
  Camunda 8 answers it with one job search where the cluster has secondary storage. Camunda 7
  answers `null` and correctly so: it delivers inside the engine's transaction, reports no delivery
  identity, and therefore keeps no records which could be missing.

**When it says something.** Only when both sides answer and both answers are interesting: the store
holds no open record AND the BPMS holds open tasks. A normal restart is quiet (the log holds
records), a fresh installation is quiet (nothing is open), a store or BPMS which cannot say is
quiet.

**What to do.** Nothing, other than keeping the version 1 guards in the handlers until the number
is zero.

## The age of a task open since before the upgrade is a lower bound (2026-08-25)

This changes the wording of an existing message and adds one adapter question. No configuration
changes and no schema changes.

**Why.** The age of a task left open by a `@TaskId` handler is measured from the delivery record,
which is written when the handler runs. A task which was already open when the application was
stopped for an upgrade has no record: the first redelivery afterwards writes one, so from then on
the task reported an age counted from the upgrade. The tasks with the largest real age were
precisely the ones whose age was under-reported, and the report of
`vanillabp.delivery.max-task-age` stayed silent for exactly them.

**What changed.** `TaskInvocationContext` gains `predatesDeployedVersion()`, default `false`. An
adapter answers it by comparing the version a delivery's workflow runs on with the version it
deployed itself, which costs nothing - both values are already at hand, and no BPMS is asked
anything. Where the answer is `true`, the message says "at least" and explains that the workflow
was already running before the version deployed now, so the real age is larger. The threshold is
unaffected in the useful direction: a lower bound past the maximum is still a reason to report.

**Who is affected.** Camunda 8 only, and only where the upgrade produced a new process version.
Camunda 7 reports no delivery identity - it delivers inside the engine's own transaction, so a
redelivery proves nothing was committed - which means it keeps no delivery records and has no
open-task age to report in the first place. The Process-Engine-API had no version 1 release.

**What to do.** Nothing. An adapter which does not answer changes nothing, and an application which
never upgraded sees the message it has always seen.

## What an application upgrading from version 1 does not bring with it (2026-08-25)

No code changed. This entry records what was CHECKED while answering the question "what does
VanillaBP owe the workflows an upgraded application brings with it, which were started under
version 1", so that none of it is checked twice. The findings which need work became their own
stories; what is written here is what turned out to be fine.

**The two Camunda adapters are not two cases of one problem.** Camunda 7 attaches everything
version 2 added while the engine PARSES a process definition
(`Camunda7AsyncBpmnParseListener`), so it reaches every version the engine holds, the ones
version 1 deployed included: the transaction boundaries, the user-task lifecycle listeners, the
task cancellation listener, the `@WorkflowEnded` end listener and the listeners on start events.
Camunda 8 writes its listeners INTO the model it deploys, and a running instance stays on the
version it was started on, so a workflow which was already running never gets them. Whenever a
feature of version 2 rests on something injected, that is the line along which it holds or does
not.

**The startup check for old process versions already covers the upgrade.**
`DeployedProcessVersionsCheck` (see below, 2026-08-15) reads the models of the versions the BPMS
still holds, asks whether this application serves their task definitions, and counts the
workflows running on them. Where an upgrade produces a new process version at all, the workflows
brought along sit on the one before it, which is exactly what the check reads. Nothing had to be
added for `@WorkflowTask(version = ...)`.

Whether it produces one is the Camunda 8 adapter's decision 5, and it is worth reading before
reasoning about any of this: that adapter deliberately deploys a version 1 model BYTE-IDENTICALLY
wherever it can, so no new version appears and the workflows brought along run on the very
version this boot deployed. A new version appears exactly where the adapter has to rewrite the
model - multi-instance input mappings, an `end` execution listener because the application now
has a `@WorkflowEnded` method, a listener on a start event the cluster fires itself, a message
subscription which modelled no correlation key, or the identifiers of `use-prefix`. Those are the
same rewrites the older instances then lack, so the two questions have one answer: where nothing
was rewritten, nothing is missing.

**The check for a renamed adapter id is silent after an upgrade, and correctly so.** It asks the
outbox and the delivery log which adapter ids they still name, and right after an upgrade both
stores are empty, which reads like a clean result. It is one: the check asks about a PERSISTED
adapter id, and version 1 persisted none - it had one adapter and its id was its type.

**The schema is created for you.** `vanillabp.outbox.create-schema` defaults to `true`, so an
upgrading application which configures nothing gets `VANILLABP_PHASE_TWO_OUTBOX` and
`VANILLABP_TASK_DELIVERY` on its first boot. Where the application applies its own schema, both
missing-table messages name the artifact, the changelog and the property.

**The outbox needs no adoption.** Entries are written per operation, so a workflow started under
version 1 needs none, and the absence of a store is reported at startup rather than at the first
workflow.

**The election cache is empty after an upgrade**, as it is after any restart. Its entries are
hints by contract, so the first operation on each workflow pays one probing walk and nothing
else.

**Two things an upgrading application should know, which are its own code rather than ours.** On
Camunda 8, version 1 handed the whole workflow aggregate to every command, so instances started
back then carry a variable for everything its JSON serialization could read. An
`@JsonProperty` renaming the aggregate's ID attribute breaks every probe for such an instance,
because version 1 wrote the JSON name and version 2 reads the name the persistence layer
reports. And an attribute version 1 serialized as a field keeps its last version 1 value for
good, since version 2 reads getters only. Both are in the migration guide now.

Full reasoning, per item and with the version 1 sources it was measured against, is in the
report which came out of story 126.

## A renamed adapter id is noticed at startup (2026-08-23)

This adds a column to the task-delivery table, a property, and a startup message.

**Why.** VanillaBP persists the adapter id twice: an outbox entry of a START operation names
the adapter elected in phase one, and the delivery key of every record is built from the
delivering adapter. An adapter id which disappears from the configuration therefore has two
readings, and both cost the application something - a workflow which was persisted and never
started, or a `@WorkflowTask` method which runs a second time. Until now only the dispatch
respectively the next redelivery said so, hours later.

**What changed:**

- `TaskDelivery` carries the delivering `adapterId` as a field of its own. It is part of the
  delivery key as well, but only as text and hashed once the key grows too long, so no store
  could answer a question about it. **The task-delivery table gains a column
  `ADAPTER_ID VARCHAR(255)`, nullable** - a record written before it existed has none and is
  simply not part of any answer. Applications letting VanillaBP create the schema get it
  automatically; applications managing the schema themselves apply the current changelog of
  `io.vanillabp:vanillabp-schema` or add the column, and the startup names it if they forget.
  A MongoDB store writes the field without any migration;
- two `default` methods on the store SPIs, `PhaseTwoOutbox#adapterIdsOfPendingCalls` and
  `TaskDeliveryLog#adapterIdsOfOpenTasks`, both asked once per BPMN process at startup and
  never at runtime. The default answers an empty set, which means "this store cannot say" and
  keeps the check silent - which is what the gruelbox-based outbox of the Spring Boot
  integration answers, because it keeps its entries as a serialized invocation with no adapter
  id to query. The shipped JDBC and MongoDB stores answer;
- **`vanillabp.retired-adapters`** names the adapter ids an application USED to have. The last
  step of a migration is removing an id, and where something is still left in the stores the
  startup would name it: this property says that the leftovers are known and turns the WARN
  into a DEBUG line. It stays in the configuration, so the decision stays visible.

**What to do.** Nothing, unless you manage the schema yourself (apply the changelog) or you
finished a migration and want the startup quiet (name the id in `retired-adapters`). An
application which renamed an adapter id in the past hears about it now, which is the point.

## Spring Boot: an adapter id named in `prioritized-adapters` gets its beans (2026-08-23)

A defect rather than a change: the documented convention was not held, so an
application may have worked around it and can now drop the workaround.

`vanillabp.adapters.<id>.type` is only needed for a CUSTOM adapter id; an id which IS an
adapter type takes its name as its type, and an id named in `prioritized-adapters` needs no
section at all. On Spring Boot the second half did not hold in one shape: the adapters'
bean registrars bind `vanillabp.*` themselves, before the core normalizes it, and they
derived such an id only where NOTHING at all had bound onto the CORE model. An adapter
section consisting entirely of the adapter's OWN keys (`rest-address`, `webapps`,
`database-schema-update`) is invisible to that model, so whether an adapter got its beans
depended on whether some OTHER section happened to carry a core key such as
`name-clash-avoidance` or `type`.

The migration setup is where it hurts, and the wiki's own example had that shape:

```yaml
vanillabp:
  prioritized-adapters:
    - camunda8
    - camunda7
  adapters:
    camunda8:
      rest-address: http://localhost:8080
      name-clash-avoidance: use-prefix
    camunda7:
      webapps:
        admin-user: { id: demo, password: demo }
```

Before this fix the Camunda 7 adapter registered no instance here, and the election found
nothing serving the id it was told to ask. Naming `type: camunda7` was the workaround.

Nothing to do for an application which names its types. One which added a `type` only to
get past this can remove it again, and one which relied on the accident of an all-empty
core model keeps working. Quarkus was never affected: its producers read the core
properties after normalization. Held by `AdapterBeanRegistrarSupportTest`, seven cases,
two of which fail against the old code.

## An aggregate without a persistence ends the startup (2026-08-22)

This concerns applications AND adapter repositories.

`AggregatePersistenceAware#getAggregateIdType()` used to catch every exception and answer
`null`, which by contract means "a custom persistence layer owns the serialized form". A
workflow aggregate whose Spring Data repository is simply missing looked exactly like that
deliberate answer: the application booted and failed at its first task. It now ends the
startup, naming the aggregate, the workflow module and three ways out.

What that means in practice:

- **an application whose aggregates have their repository:** nothing changes;
- **an application which owns the serialized form on purpose:** contribute an
  `AggregatePersistenceAware` for those aggregates (or override `getAggregateIdType()` to
  return `null` explicitly). Being asked out loud is the point;
- **a test application without any persistence** - which every adapter repository has -
  has to say so as well, with an `AggregatePersistenceAware<Object>` double whose methods
  throw. The Camunda 8 adapter's smoke application has one; a `SpringDataUtil`
  stub whose `getRepository` throws is no longer enough, because that throw is exactly what
  the check now reports.

Quarkus applications were unaffected: there the same defect already failed the BUILD, and
the message names the workflow module now.

## name-clash-avoidance is `by-adapter` again, on every adapter (2026-08-22)

This concerns every application which never configured
`vanillabp.adapters.<id>.name-clash-avoidance`.

`by-adapter` is the default because that is what VanillaBP 1 deployed: a tenant
named after the workflow module, on Camunda 7 as well as on Camunda 8 (version 1's
`use-tenants` was on and the tenant defaulted to the workflow module id). On 2026-08-11 both
Camunda adapters overrode that default with `none` - Camunda 8 because a cluster from the
stock image has multi-tenancy switched off and rejected the boot of an application which
configured nothing, Camunda 7 for symmetry. Neither change was recorded here, and both broke
what an upgraded version-1 application finds: its workflows live in their tenants, and a
deployment without a tenant does not address them.

The default is `by-adapter` again, and overriding it is not an adapter's decision to make.
What an adapter does instead is say what its BPMS needs:

- **Camunda 7** needs nothing. A tenant id is an attribute of the deployment, so the engine
  accepts any name and creates nothing.
- **Camunda 8** needs multi-tenancy enabled and the tenant present. Where the cluster refuses,
  the boot ends with a message naming both ways out, `use-prefix` and `none` (the second one
  is version 1's `use-tenants: false`). The message used to name prefixing only.
- **Process-Engine-API** has no isolation of its own and refuses `by-adapter` while deploying,
  which is unchanged.

What to do, per application:

- **upgraded from version 1, cluster with multi-tenancy or Camunda 7:** nothing. The tenants
  are back.
- **Camunda 8 on a cluster without multi-tenancy:** add
  `vanillabp.adapters.<id>.name-clash-avoidance: none` (or `use-prefix`, which keeps modules
  apart without a tenant). Without it the boot ends, naming exactly these two.
- **built against a VanillaBP 2 snapshot between 2026-08-11 and 2026-08-22:** if you relied on
  the `none` default, say so explicitly with the same property. Switching the mode of a running
  application is a BPMS migration, not a property change - see the wiki.

## Platform beans: ProcessServiceBase has no unsupported stubs left (2026-08-22)

This concerns whoever writes a PLATFORM integration. Applications and BPMS
adapters are not affected.

`ProcessServiceBase` carried four overrides (`completeTask`, `cancelTask`,
`completeUserTask`, `cancelUserTask`) which threw "not yet supported by VanillaBP 2! It
will be implemented later". Both platform beans have implemented all four
for a while, so the stubs were unreachable and the message was wrong. They are gone: the
operations are abstract in the base as they are in `ProcessService`, and a platform bean
which forgets one no longer compiles.

Nothing to do for an existing platform integration which implements the operations. One
which relied on inheriting the stub gets a compile error naming the method, which is the
point.

## Adapters: the awareness probes are told which workflow is meant (2026-08-21)

This concerns whoever WRITES an adapter. Applications are not affected;
`spi-for-java` is untouched.

The four awareness probes of `MigratableProcessService` take a `WorkflowScope` as their
first parameter now:

```java
WorkflowAwareness awarenessOfTask(WorkflowScope scope, Object workflowAggregateId, String taskId);
WorkflowAwareness awarenessOfUserTask(WorkflowScope scope, Object workflowAggregateId, String taskId);
WorkflowAwareness awarenessOfWorkflow(WorkflowScope scope, AggregatePersistenceAware<A> persistence, Object workflowAggregateId);
WorkflowAwareness awarenessOfWorkflowForRedispatch(WorkflowScope scope, AggregatePersistenceAware<A> persistence, Object workflowAggregateId);
```

The scope names the workflow module and the BPMN processes the calling process service
serves, the primary one first. An adapter answers for THAT scope and for nothing else:
anything belonging to another module or another process is `UNKNOWN_TO_BPMS`, which is
what lets the election reach the adapter really holding the workflow.

Why it was necessary: a probe used to be told the workflow-aggregate id alone. Aggregate
ids are unique per aggregate type and not across an application, so two workflow modules
whose aggregates count from one both hold an id `1`, and an adapter serving both answered
`ACTIVE` for either. That costs nothing while one BPMS is configured and wins the election
against the right BPMS as soon as a migration runs. Camunda 7 and Camunda 8 both had it
on a shared cluster respectively a shared database, which is how the SPI got the contract
and the parameter here.

For an adapter of your own: translate the scope into what your BPMS knows the processes by
(a tenant, prefixed identifiers, a table prefix) and filter your query with it. The
Camunda 7 adapter does it with `processDefinitionKeyIn` plus `tenantIdIn`, the Camunda 8
adapter compares the tenant and the scoped process definition id of every search hit.

## A workflow module's configuration is a default again (2026-08-21)

A regression against Version 1 rather than a new rule.

A file named after a workflow module (`loan-approval.yaml`, `loan-approval-prod.properties`) carries
defaults. Everything the application configures wins over it, whichever file the application uses.
From strongest to weakest: system properties, environment variables, the application's configuration
wherever it lives, `{module}-{profile}`, `{module}`. Both platforms answer it that way now; until
today they answered it differently, and on Spring Boot neither answer was the one Version 1 gave.

Spring Boot inserted the module's property source directly below the system environment, so only an
environment variable or a `-D` could override a value a module shipped. `application-prod.yaml`
lost, and so did every other file of the application. Quarkus gave the module ordinals 256 and 251
against 255 and 250 for the application's classpath files, so a module beat `application.yaml` but
lost against a file next to the runner. Either way an application could not reconfigure a module it
consumed in the place where you would look for it.

Version 1 never put these files into the environment at all. It collected them into a
`YamlPropertiesFactoryBean` and handed the result to a `PropertySourcesPlaceholderConfigurer`
through `setProperties(...)`, whose `localOverride` stays `false`: the configurer resolves against
the environment first and against those local properties second. A module file was a fallback, and
`application-prod.yaml` won over it. So what broke here is a rule which was never written down but
was always true.

On Spring Boot the module's property sources are now appended at the END of the environment instead
of being inserted after the system environment. No source name is matched to find the position any
more, which is what makes it hold for an application bringing config data sources VanillaBP cannot
know about (`spring.config.import`, an external location, a source another `EnvironmentPostProcessor`
added). On Quarkus the two ordinals moved from 256/251 to **235/230**, below the classpath
`application.yaml` (255) and `application.properties` (250). The 15 to the application's weakest
file is deliberate: SmallRye loads a profile-specific file a tick above its base file, once per
active profile, and the gap keeps a module's `-prod` variant below the application however many
profiles are active.

Inside a module nothing changed: `loan-approval-prod.yaml` still beats `loan-approval.yaml`, and
YAML still beats `.properties` for the same base name. Nothing Version 2 added is taken back either.
These values remain proper configuration properties, visible to `Environment#getProperty` and
`@ConfigurationProperties` respectively `@ConfigProperty`, not only to `${...}` and `@Value`, which
is what Version 1 could not do.

If you moved a value out of your `application.yaml` because a module file kept beating it, move it
back. If you deliberately relied on a module file overriding the application, that no longer works:
put the value where the application configures it, or use a system property or an environment
variable, which still win over everything.

Adapter properties answer a different question and are unchanged.
`vanillabp.workflow-modules.<mod>.adapters.<id>.<key>` still beats `vanillabp.adapters.<id>.<key>`
because it is the more specific key, whichever file either of them stands in. Precedence decides who
wins for the same key, specificity decides between different keys, and the level resolution in
`MigrationAdapterProperties` runs on the configuration after it was merged. A module writing its own
`vanillabp.workflow-modules.<its id>.*` therefore still beats an adapter-wide value of the
application, and the application takes that back by writing the module-level key itself.

## Metrics, health and a logging context around every delivery (2026-08-20)

Additive and without a single new property: nothing changes for an application
which does nothing.

Where Micrometer is on the classpath, VanillaBP now publishes what it does with your work
under `vanillabp.*`: task deliveries by outcome, how long each took, redeliveries answered
from the delivery record, and the phase-two outbox with its backlog, its retries and its
failures. Without Micrometer nothing is registered and nothing fails, exactly as for the
election cache's meters. The names, the tags and the question each one answers are in the
wiki page [Observability](https://github.com/vanillabp/adapter-platform-integration/wiki/Observability).

Where the platform has a health endpoint, the BPMS adapters contribute what they know about
their BPMS under the name `vanillabp` (Spring Boot: a health component of
`/actuator/health`, needs `spring-boot-health` on the classpath; Quarkus: a readiness check
of `/q/health/ready`, needs the SmallRye Health extension). An adapter which is not
configured yet reports UNKNOWN and does not make the application unhealthy.

Every task delivery and every phase-two dispatch now runs inside six MDC keys naming the
adapter, the workflow module, the BPMN process, the workflow aggregate, the task and the
BPMS' own delivery id. VanillaBP restores the previous values afterwards and touches no
other key, so an existing log configuration keeps working; a pattern to paste is in the
wiki page.

One property comes with this, and it exists to keep a promise rather than to offer a
choice: `vanillabp.metrics.gauge-cache` (default `PT10S`) is how long the measurement of a
gauge which has to ask somebody is reused. Counting the waiting outbox entries is a
database query, a gauge is read on every collection, and every instance of your application
answers every collector separately - so without holding, a dashboard would turn watching
the outbox into load on it. Ten seconds is one collection interval, a little under
Prometheus' default scrape. `PT0S` measures on every collection, which is what a test wants.

**Only relevant for adapters and for stores of your own:**
`AdapterDeploymentService.checkHealth()` and `PhaseTwoOutbox.pendingCalls()` are new
`default` methods contributing nothing, so existing implementations stay valid. An adapter
which contributes no health is absent from the endpoint rather than reported as healthy,
and a store which cannot count its waiting entries publishes no gauge. An adapter
registering a gauge whose value costs a query wraps it in
`io.vanillabp.integration.adapter.spi.observability.CachedGaugeValue`, which is the class
behind the property above; a gauge reading something already in memory needs none of it.

## A workflow which ended can release its delivery records (2026-08-19)

Additive and switched off, so nothing changes for an application which does not
configure it.

The records of processed task deliveries were cleaned up by age alone until now
(`vanillabp.outbox.retention`, seven days). Where
`vanillabp.delivery.release-on-workflow-end` is set to `true`, globally or per workflow
module, the records of a workflow are deleted the moment it ends instead: nothing of an
ended instance can be redelivered, so the clock does not have to decide it. The deletion
runs in the transaction of the end notification and is bounded by workflow module, BPMN
process, workflow aggregate and by the moment of that notification, which keeps the
records of a second workflow on the same aggregate.

Switching it on has a price in the model: VanillaBP asks a BPMS to report the end of a
workflow only where that end is used, so from then on every deployed process of the module
carries the listener respectively the worker doing it. That is why it is off by default,
and the retention stays the backstop for workflows still running, for aggregates whose
workflow never ends and for stores which cannot delete.

**Only relevant for stores of your own:** `TaskDeliveryLog.releaseRecordsOf(...)` is a new
`default` method deleting nothing, so an existing store stays valid and keeps its records
until the retention passed. Where the release is switched on and the store cannot serve it,
the startup names the store and the property.

## A redelivered task no longer runs the handler twice (2026-08-14)

A behaviour change of the inbound direction, and it is on by default.

A remote BPMS delivers a task at least once: it hands the task out again whenever it did
not learn the result, for instance after a crash between the commit of the local
transaction and the report to the BPMS. Until now that ran the `@WorkflowTask` method a
second time, and the wiki's rule "write handlers idempotently, keyed on aggregate state"
was all there was to it. VanillaBP now records every processed delivery in the
transaction of the handler and answers a repeated delivery with the recorded outcome
instead of invoking the method again.

**A new store, without new configuration.** The records live in the database the workflow
aggregates live in: a table `VANILLABP_TASK_DELIVERY` respectively a collection
`vanillabp-task-deliveries`, created at startup unless
`vanillabp.outbox.create-schema` is disabled, cleaned up per `vanillabp.outbox.retention`.
An application managing its schema manually creates the table itself; the DDL is in
`JdbcTaskDeliveryStore` of the migration adapter, and the only structural requirement is
a unique `DELIVERY_KEY`.

**A new property.** `vanillabp.adapters.<id>.deduplicate-deliveries` (default `true`)
switches it off, per workflow module, workflow and task as well. Turning it off restores
the previous behaviour exactly.

**A new warning.** An application whose aggregates live in a persistence VanillaBP brings
no store for logs one WARN per BPMN process naming the `TaskDeliveryLog` bean to provide
and the property to set. It boots and behaves as before.

**Only relevant for adapters:** `TaskInvocationContext.getDeliveryId()` and
`MigratableProcessService.deliversTasksAtLeastOnce()` are new `default` methods, so an
adapter compiled against the previous version keeps working - and keeps invoking handlers
per delivery, since VanillaBP cannot tell two deliveries apart without an identity.
Camunda 8 reports the job key, the Process-Engine-API adapter the task id, Camunda 7
nothing at all: it delivers inside the engine's transaction, where a redelivery proves
that nothing was committed.

## The election cache can be sized and watched (2026-08-14)

Everything here is additive - an application configuring nothing behaves
exactly as before.

**New properties.** The bounds of the default election cache were fixed constants and
became properties of the platform:

|                    Property                     | Default |
|-------------------------------------------------|---------|
| `vanillabp.workflow-adapter-cache.max-entries`  | `10000` |
| `vanillabp.workflow-adapter-cache.time-to-live` | `PT1H`  |

Both are validated at startup: a cache holding no entry, or an entry expiring
immediately, fails the boot with a message naming the property. Raise `max-entries` if
the application keeps more workflows in flight than the cache holds - a record costs
roughly 300 bytes, so 100.000 of them are about 30 MB. The bound stays hard; why it is
not a soft reference is written down in `migration-adapter/README.md`.

**New metrics, only with Micrometer.** If the application brings Micrometer (Spring
Boot: the Actuator's metrics; Quarkus: the `quarkus-micrometer` extension), VanillaBP
publishes `vanillabp.workflow.adapter.cache.size`, `.hits`, `.misses`, `.evictions`,
`.evictions.unused` and `.lost.hints`. Micrometer is an optional dependency: without
it the application boots unchanged and publishes nothing. Hits and misses are counted
for an application-provided `WorkflowAdapterCache` bean as well, size and evictions
are not - only the implementation itself knows them.

**A new warning.** Once ten cached elections were dropped for lack of space AND looked
up again within an hour, one WARN per hour names the number, the observation period
and the property to raise. A cache which is merely full is not warned about.

**Only relevant for code compiling against the platform:** the constants
`InMemoryWorkflowAdapterCache.MAX_ENTRIES` and `.TIME_TO_LIVE` are gone (the defaults
live in `WorkflowAdapterCacheProperties` now), and the cache's preferred constructor
takes the properties plus the application's `WorkflowAdapterCacheStatistics`. The
no-arg constructor still exists and still means "the defaults, uncounted".

## An operation right after a start waits for the BPMS to catch up (2026-08-13)

Two changes, one of them a defect that made Camunda 8 unusable on any cluster
with secondary storage.

**`awarenessOfWorkflow` takes the aggregate persistence now.** The adapter SPI methods
`MigratableProcessService.awarenessOfWorkflow` and `awarenessOfWorkflowForRedispatch`
carry `AggregatePersistenceAware<A>` as their first parameter. A BPMS without a business
key finds a workflow by the process variable holding the aggregate's ID, and that variable
is named after the aggregate's ID attribute - a name the probe has to know, since the
election runs before every other SPI method of an operation. The Camunda 8 adapter used to
remember the name from whichever call carried a persistence before, and started out with a
placeholder: the probe searched for `id` while the cluster stored `loanRequestId`, found
nothing and reported EVERY workflow as unknown. Anything electing its BPMS by that probe
(completing or canceling a task, user-task operations, message correlation,
`aggregateChanged`, the viewer, the re-dispatch mitigation) failed with
`WorkflowNotFoundException` on a cluster with secondary storage. Adapters outside this
repository have to add the parameter; there is no compatible overload on purpose, so
nobody keeps implementing the broken shape.

**Waiting for a workflow to become visible.** A BPMS answering from an eventually
consistent read model reports a workflow it created moments ago as unknown, which used to
end in `WorkflowNotFoundException` naming causes that all did not apply - in the most
ordinary sequence there is, "start a workflow, then correlate the message which lets it
continue". Now:

- adapters may report a window (`MigratableProcessService.workflowVisibilityDelay()`, a
  `default` returning none, so nothing breaks). Camunda 8 reports
  `vanillabp.adapters.<id>.workflow-visibility-timeout`, 10 seconds by default, zero
  switches it off; Camunda 7 and the Process-Engine-API report none;
- the core waits that window out while probing an adapter its `WorkflowAdapterCache` names
  for the workflow, and nowhere else. A workflow nobody ever started still fails
  immediately;
- the cache is filled where VanillaBP knows the answer without probing: after phase two of
  a start, and on every inbound delivery. The inbound contexts
  (`TaskInvocationContext`, `WorkflowEndedContext`, `BpmsInitiatedStartContext`) carry
  `getAdapterId()` for that, a `default` returning `null` - an adapter which does not
  implement it keeps working, its deliveries just record nothing;
- the guiding message of `WorkflowNotFoundException` names "not searchable yet" as a cause
  where a configured BPMS is eventually consistent.

An application on several nodes should provide a shared `WorkflowAdapterCache` bean: a
node which neither started the workflow nor received a delivery for it has nothing to wait
on and fails as before.

## `version` decides which method serves a task (2026-08-13)

The `version` attribute of `@WorkflowTask`, `@WorkflowStartedByBpms` and
`@WorkflowEnded` is evaluated now. It exists since VanillaBP 1 and was never implemented
there, so every method served every version; VanillaBP 2 parsed the ranges but no adapter
reported a version, which came to the same. Applications not using the attribute are
unaffected.

The version meant is the version of the deployed process DEFINITION as the BPMS counts it
(Camunda 7 and Camunda 8 count integers upwards per BPMN process id), not a version an
application invents. A boundary may also name a version TAG of the model
(`camunda:versionTag`, `zeebe:versionTag`).

Four things flip for an application which already carries the attribute:

- Disjoint ranges really are disjoint now. A version served by NO method fails the
  delivery with a message naming that version, where before the first method ran.
- A BPMS which reports no version at all (the Process-Engine-API, and any adapter whose
  BPMS cannot tell) reaches methods without the attribute only. A method naming versions
  is not called there, and a task whose every method names versions fails the delivery
  saying so, where before the first method ran. Whether a version nobody reported lies
  within `1-3` cannot be answered, and answering it by the order the methods happen to be
  reflected in was not stable between two starts of the same application.
- Two methods wired to one BPMN element with OVERLAPPING ranges fail the boot, naming both
  methods. The duplicate check compared `matchesVersion(null)`, which is `true` for every
  specification, so it never fired; and the workflow-task registry compared newly scanned
  handlers only against the already registered ones, so two methods of the SAME class were
  never compared at all.
- A non-numeric specification is a version TAG now instead of a boot failure. `version =
  "latest"` used to fail with "unsupported version specification" and is a tag named
  `latest` today. `>=3` and `<=3` are supported as documented in version 1's README (they
  were never implemented either).

New in the adapter SPI (`io.vanillabp.integration.adapter.spi.version`):
`ProcessVersionCatalog` with `DeployedProcessVersion` and the ready-made
`CachingProcessVersionCatalog`, plus two default methods on `WorkflowTaskInvoker`:
`registerProcessVersions(...)` during `wireBpmn` and `resolveProcessVersions(module)` at
the end of `deployResources`. Both are optional: an adapter which registers nothing keeps
working, and only specifications naming a version tag need a catalog at all. Adapters
outside this repository do not have to change.

What it costs: nothing for specifications made of numbers - they are compared to the
version the adapter reports with the task. A specification naming a tag makes VanillaBP ask
the BPMS once per process while the application starts (after the deployment, so a tag
deployed by this very start is included) and again only for a version it has never seen,
which is what a rolling deployment produces while another node is ahead. Camunda 7 answers
from its definition query, Camunda 8 needs its query API (secondary storage) for tags, and
the Process-Engine-API can only report the tag of the current task (GAPS 19).

## Camunda 8: workflows were never found on clusters with secondary storage (2026-08-13)

A defect fix. Nothing to change in your code or configuration.

The adapter searched process instances by the aggregate-ID variable and passed the ID as
a plain string. Camunda 8 compares a variable against its stored JSON, so a String value
has to carry its quotes: the filter matched nothing, `awarenessOfWorkflow` answered
`UNKNOWN_TO_BPMS` for every workflow, and everything electing its BPMS by probing failed
with a guiding `WorkflowNotFoundException` - `completeTask`, `cancelTask`, the user-task
operations, `correlateMessage`, `aggregateChanged`, the viewer and the START re-dispatch
mitigation. On a cluster without secondary storage none of this showed, because the search
throws there and the adapter answers optimistically `ACTIVE`.

Which is also why it survived: the adapter's Docker tests all ran on such a cluster, so
what they exercised was the fallback and never the query. `Camunda8SecondaryStorageIT`
brings its own Elasticsearch now and correlates a message with a workflow located by
probing; it fails with the old filter, carrying the exception the field reported.

The encoding lives in one place (`Camunda8VariableFilters`), used by the process service
and the viewer. It quotes unconditionally because VanillaBP writes the ID as a string
whatever type the aggregate's ID attribute has - a unit test pins the two halves together.

## Telling the BPMS that the aggregate changed (2026-08-13)

`ProcessService.aggregateChanged` exists now, in two overloads. Additive;
nothing existing changes behavior.

- **New in `spi-for-java` 1.2.0:** `aggregateChanged(aggregate)` writes the values
  shared with the BPMS at the workflow's global scope, `aggregateChanged(aggregate,
  taskId)` writes them in the scope that task RUNS in - the process, an embedded
  subprocess, or the one iteration of a multi-instance embedded subprocess. NOT the
  task's own scope: engines create one where the model asks for it (a boundary event,
  an instance of a multi-instance activity), values written there serve that activity
  alone and vanish with it. The task-scoped overload does not additionally write the
  global scope either: an inner value shadows the global one there anyway, and writing
  both would change what the other iterations see.
- **What travels is the sync model** (`@SyncWithBPMS`), the same values a
  task completion pushes. The method names no variables.
- **New adapter SPI methods** `MigratableProcessService#aggregateChangedPhaseOne` /
  `#aggregateChangedPhaseTwo`, both `default` and both throwing a guiding
  `UnsupportedOperationException`.
- **New core operation `AGGREGATE_CHANGED`** in the operation registry,
  WITHOUT an idempotency key: the values are read from the aggregate when the entry is
  dispatched, so a redelivered entry writes the then-current state. Deduplicating
  could only drop a push, never save one. The adapter is elected at dispatch time by
  probing, like message correlation.
- **Camunda 7** writes inside the caller's transaction: `setVariables` at the process
  instance, `setVariablesLocal` at the execution of the scope a task runs in (found by
  walking the execution tree, skipping an activity's own scope and the multi-instance
  body). This is what makes **conditional events** usable at all there - the engine
  looks at their conditions when a variable of their scope or of a parent scope
  changes, so an event subprocess with a conditional start event reacts. Where an application shares nothing (this
  adapter's opt-in default), a technical variable `vanillabpAggregateChanged` is
  written so the change happens.
- **Camunda 8** sends `SetVariables` after the commit, for the process instance
  respectively the element instance of the scope the task runs in (the API reports
  children of a scope but no parents, so the adapter walks down from the process
  instance to find it). It **needs secondary storage**: the
  cluster has no business key, so only the query API translates an aggregate ID into
  the keys the command takes. Without it the adapter fails with a guiding message.
  Conditional events do not exist in Camunda 8 - that stays true.
- **Process-Engine-API:** not supported (gap 18). The API modifies the payload of a
  TASK, never that of a running instance, so both phases throw with a guiding message.
- **Also fixed:** the Camunda 7 EL resolver returned the task's activity behavior for
  ANY name evaluated while an execution sat at a wired task - including the condition of
  a conditional event reading the workflow aggregate, which failed with "condition
  expression returns non-Boolean". A name which IS a task definition still means the
  task; otherwise an attribute of the workflow aggregate wins. The new adapter-SPI
  method `WorkflowTaskInvoker#workflowAggregateHasProperty` answers that from the
  aggregate CLASS, without loading an aggregate. Adapters implementing the SPI have to
  implement it.
- **Fixed on the way:** the Camunda 8 adapter searched process instances by the
  aggregate-ID variable WITHOUT quoting the value. Variable values are stored as JSON,
  so a String value never matched and every probe of a cluster WITH secondary storage
  answered "unknown workflow". Affects `awarenessOfWorkflow`,
  `awarenessOfWorkflowForRedispatch` and the new push.

## Telling the application that a workflow ended (2026-08-13)

`@WorkflowEnded` exists now. Additive; nothing existing changes behavior,
and a model without such a method is deployed exactly as before.

- **New in `spi-for-java` 1.2.0:** the annotation `@WorkflowEnded` and the value
  record `WorkflowEnd` (kind, time, end event id). The method takes the workflow
  aggregate and optionally a `WorkflowEnd`, returns void, and VanillaBP saves the
  aggregate afterwards.
- **New adapter SPI** `io.vanillabp.integration.adapter.spi.workflowend`:
  `WorkflowEndedInvoker` (implemented by the same core bean as the other two
  invokers) with `workflowEndedHandlerExists` - adapters ask it while wiring, so a
  listener is attached ONLY where a method exists - and `workflowEnded`.
- **Camunda 7:** an END execution listener at the process scope, inside the engine's
  transaction. It distinguishes `COMPLETED` from `TERMINATED` by the execution's
  delete reason and reports the end event reached.
- **Camunda 8:** an `end` execution listener on the process element plus a worker for
  its job. The cluster runs end listeners of completed instances only, so this
  adapter reports `COMPLETED` and never `TERMINATED`, and it does not report which
  end event was reached. A model of a process with such a method is redeployed with
  the listener and therefore produces a new process version.
- **Process-Engine-API:** not supported (gap 17). A method for a process running
  there yields a WARN naming it - deliberately not a boot failure, since the workflow
  itself runs normally and only the notification is missing.
- The notification is at-least-once, and an aggregate deleted meanwhile is logged and
  skipped instead of failing the BPMS' transaction.

## Broadcasting BPMN signals (2026-08-12)

`ProcessService.sendSignal(String)` exists now. Additive; nothing existing
changes behavior.

- **New in `spi-for-java` 1.2.0:** `sendSignal(String signalName)` as a default method
  throwing until an adapter implements it. Broadcast only - there is deliberately no
  overload limiting a signal to one workflow, because Camunda 8 cannot do that and an
  API which quietly means something else per BPMS is worse than none.
- **New adapter SPI methods** `MigratableProcessService#sendSignalPhaseOne` /
  `#sendSignalPhaseTwo`, both `default` and both throwing a guiding
  `UnsupportedOperationException` - an adapter whose BPMS has no signals says so
  instead of swallowing a broadcast.
- **New core operation `SEND_SIGNAL`** in the operation registry, WITHOUT
  an idempotency key: a signal has nothing to deduplicate by, so a redelivered outbox
  entry broadcasts again. The broadcasting adapter IS persisted with the entry,
  because a broadcast goes to every BPMS the workflow module was deployed to and each
  of them gets its own entry.
- **`PhaseTwoCall.workflowAggregateId()` may be `null`** from now on: an operation
  which is not about one workflow carries no aggregate ID. Custom outbox stores have
  to tolerate that (the shipped ones do - the JDBC store's `AGGREGATE_ID` column was
  nullable already).
- **Camunda 7** broadcasts inside the caller's transaction, **Camunda 8** after the
  commit, and the **Process-Engine-API** uses its `SignalApi` after the commit (that
  API exists, so this needed no gap entry).
- A broadcast reaches the DEPLOYMENT UNION of the workflow module, not only the
  first-priority adapter of the calling process service. Its SCOPE is that workflow
  module: every adapter broadcasts through its own client with its own tenant, and
  the signal name carries the module's prefix where the module prefixes identifiers.
  A signal meant for several workflow modules is sent through the `ProcessService` of
  each of them - with the mode `none` nothing separates the modules in the BPMS
  anyway, which is the price of that mode.

## Workflows the BPMS starts itself (2026-08-12)

A timer, signal or conditional start event produces a workflow without a
workflow aggregate, which VanillaBP now builds. Additive: nothing existing changes
behavior.

- **New in `spi-for-java` 1.2.0:** the annotation `@WorkflowStartedByBpms` and the
  value record `BpmsStartTrigger`. Both are OPTIONAL - without them VanillaBP builds
  the aggregate on its own (see the wiki page "Starting workflows").
- **New adapter SPI** `io.vanillabp.integration.adapter.spi.workflowstart`:
  `BpmsInitiatedStartInvoker` (implemented by the core, offered to adapters like
  `WorkflowTaskInvoker` - the same bean implements both), plus
  `BpmsInitiatedStartSpec`, `BpmsInitiatedStartContext` and
  `BpmsInitiatedStartResult`. An adapter which does not implement it keeps working;
  processes with such start events simply cannot run on it.
- **Camunda 7:** an execution listener on the start event builds the aggregate inside
  the engine's transaction and sets the instance's business key from it.
- **Camunda 8:** an execution listener is added to the start event while deploying
  (event type `end` - the cluster rejects `start` listeners there), and its job
  completion writes the aggregate-ID variable plus the shared aggregate values. A
  model deployed by an earlier VanillaBP 2 build is redeployed with that listener, so
  it produces a new process version.
- **Process-Engine-API:** deploying a process with such a start event now fails with a
  guiding message (gap 16) - the API never reports a start the application did not
  ask for.
- The workflow-aggregate class needs a constructor without arguments to be built by
  VanillaBP. Where it has none, a `@WorkflowStartedByBpms` method returning the
  aggregate does the job; the failure message says both.

## Phase-two operations become a registry (2026-08-12)

The closed enum `PhaseOperation` becomes an open registry, so
extensions can use the outbox for crash-safe after-commit work of their own and new
core operations no longer edit a shared enum plus a `switch`.

- **`PhaseOperation` is no longer an enum** but a record of a persisted NAME and
  its idempotency-key derivation. The seven core operations stay constants of that
  class under UNCHANGED names and UNCHANGED key rules (`START_WORKFLOW`,
  `COMPLETE_TASK`, `CANCEL_TASK`, `COMPLETE_USER_TASK`, `CANCEL_USER_TASK`,
  `CORRELATE_MESSAGE`, `START_WORKFLOW_BY_MESSAGE`), pinned by a test now that the
  compiler no longer guarantees them. Entries written by an earlier build keep
  dispatching and deduplicating.
- **`PhaseTwoCall` carries the operation by name** (`String operation()`) and the
  derived idempotency key as a component. Build calls with
  `PhaseTwoCall.of(operation, ...)` when scheduling and with
  `PhaseTwoCall.forDispatch(name, ...)` when a store rebuilds one from a persisted
  entry - the canonical constructor changed, so custom stores or extensions
  constructing calls directly have to switch to the factories.
- **Breaking for custom `PhaseTwoOutbox` stores** in exactly two places: persist
  `call.operation()` instead of `call.operation().name()`, and stop resolving the
  persisted name (no more `PhaseOperation.valueOf`) - hand it to the router as a
  String. Nothing else about the store contract changed; stores still never
  interpret operations.
- **Unknown operations are the router's business now.** An entry whose operation is
  not registered fails with a guiding message naming the operation and listing the
  registered ones; the entry stays in the store and its retry/blocking behavior is
  unchanged. This covers the new case of an extension having been removed from the
  application.
- **New: extensions register their own operations.** `PhaseOperation`
  `.extensionOperation(name, key)` enforces a namespace (`my-extension:NOTIFY`), and
  `PhaseOperationRegistry.register(operation, dispatch)` adds it. The registry is
  a bean on both platforms (Spring Boot `vanillaBpPhaseOperationRegistry`,
  Quarkus a `@Singleton` producer). An extension operation is dispatched to its own
  handler, without the aggregate-to-adapter election of the core operations.
- **No schema change.** The persisted encoding is still the operation's name; the
  namespaced names of extensions are longer, but the column widths of the shipped
  stores already accommodate them (the Quarkus JDBC store's `OPERATION` column is
  `VARCHAR(255)`, MongoDB is schemaless, gruelbox serializes the name into its
  invocation). Applications running a SNAPSHOT need no migration.

## Name-clash avoidance: tenants, prefixes - and a Camunda 8 fix (2026-08-07)

How a workflow module's identifiers are kept apart from those of other
modules becomes ONE explicit setting, and Camunda 8 regains the behavior it had in
VanillaBP 1.

- **New adapter-scoped property `name-clash-avoidance`** =
  `by-adapter` (**default**) | `use-prefix` | `none`, resolvable most-specific-wins
  across workflow > workflow module > adapter
  (`vanillabp.adapters.<id>.name-clash-avoidance`, respectively the same key below
  `vanillabp.workflow-modules.<m>.adapters.<id>` and `...workflows.<w>.adapters.<id>`).
  `by-adapter` uses the BPMS' own isolation - Camunda 7 and Camunda 8 deploy a module
  under a TENANT named after it (overridable by the adapter's `tenant-id`, which is
  NEW for Camunda 7). `use-prefix` prefixes the identifiers with the module id
  instead and uses NO tenant (BPMS are licensed per tenant). `none` scopes nothing.
- **BREAKING for early VanillaBP 2 adopters of Camunda 8:** that adapter deployed
  everything into the default tenant and documented module isolation as relying on
  unique BPMN process ids. With the default `by-adapter` a module is deployed into
  its own tenant again - VanillaBP 1's behavior, and the reason this is a fix rather
  than a feature. Set `name-clash-avoidance: none` to keep the early-V2 behavior. A
  cluster WITHOUT multi-tenancy rejects tenant ids: use `use-prefix` or `none` there.
- **BREAKING for the Process-Engine-API adapter:** that BPMS has no isolation of its
  own, so the DEFAULT mode cannot be served - the boot fails with a guiding message
  naming the workflow module and the alternatives (`use-prefix`, `none`). Existing
  applications have to choose actively; nothing is silently deployed into one scope.
- **What prefixing rewrites** (transparent - business code, `ProcessService` calls,
  BPMN files and configuration keep the PLAIN identifiers, separator `__`): BPMN
  process ids, call-activity references, message and signal names, escalation and
  error codes, plus task definitions - the latter additionally scoped by their BPMN
  process (`prefix-task-definitions-per-process: false` switches that part off).
  Camunda 7 deliberately does NOT prefix task definitions: they are process-local
  there (an expression evaluated by VanillaBP's EL resolver), whereas Camunda 8 job
  types are subscribed to cluster-wide.
- **Switching the mode is a BPMS MIGRATION, not a property change** - the identifiers
  the BPMS knows change, so workflows started earlier would not be found. Configure a
  SECOND adapter id differing only in this setting and put it first in
  `prioritized-adapters` (documented as a recipe in the wiki). Consequently a
  differing mode now makes two Camunda 8 adapter ids DISTINCT
  (`validateDistinctAdapterInstances`), which previously failed the boot as "the same
  cluster twice". Camunda 7 additionally needs its own `table-prefix`/datasource for
  the second id - two embedded engines must not share engine tables, mode or not.
- **Two new startup validations:** a workflow module id must not contain the
  separator `__` (checked when prefixing is configured anywhere), and two different
  processes must not produce the same prefixed identifier - both fail naming what to
  rename.
- **Adapter authors:** the adapter SPI gained `NameClashAvoidance` and
  `NameClashAvoidanceSupport` (provided as a platform bean, implemented once by the
  core). Apply it in `prepareBpmn` to your model and at every runtime boundary; never
  build the prefixed strings yourself. An adapter whose BPMS has no native isolation
  calls `validateNativeIsolationSupported(adapterId, workflowModuleId, description)`
  while deploying - note that the core's `validateDistinctAdapterInstances` hook is
  only invoked for MORE THAN ONE id of a type and is therefore the wrong place for it.

## Aggregate sync: derived class mode and the completion push (2026-08-07)

This completes the aggregate sync of the entry below. Two behavior changes - read them if an
application already uses `@SyncWithBPMS` / `@NoSyncWithBPMS`.

- **The class mode is DERIVED when only ATTRIBUTES are annotated.** Before, the
  ADAPTER's default decided for a class carrying no annotation of its own, even when
  its attributes were annotated. Now the first annotation anywhere hands control to
  the application: attributes marked `@SyncWithBPMS` imply `@NoSyncWithBPMS` on the
  class (opt-in), attributes marked `@NoSyncWithBPMS` imply `@SyncWithBPMS` on the
  class (opt-out). **What flips for an aggregate of the previous release without a class
  annotation:**
  - only `@SyncWithBPMS` attributes, remote BPMS (default `FULL`): used to share
    everything, now shares ONLY the annotated attributes;
  - only `@NoSyncWithBPMS` attributes, embedded BPMS (default `NONE`): used to
    share nothing, now shares everything EXCEPT the annotated attributes.
    Annotate the class explicitly to keep the old behavior. The rule applies per
    TYPE, so a nested DTO derives its own mode the same way (a DTO with neither its
    own nor derivable annotations still inherits from the attribute holding it).
- **Mixing both annotations on the attributes of a class that states no mode itself
  FAILS THE BOOT** with a message naming the class, the conflicting attributes and
  the fix. The check runs at STARTUP for every registered workflow aggregate and
  every type reachable from its attributes (up to the model's nesting limit); a type
  reachable only at runtime fails with the same message when it is first shared.
- **A `@WorkflowTask` is a sync point now (remote BPMS).** Camunda 8 and the
  Process-Engine-API push the shared values when they report a task as completed
  (and when a `TaskException` becomes a BPMN error) - a gateway right behind a
  service task used to evaluate the values of the last `ProcessService`-driven sync
  point, i.e. stale data. The values are read AFTER the local transaction committed,
  in an own transaction; a failing read never prevents the completion (the task is
  then completed with the technical aggregate-ID variable only). USER-TASK lifecycle
  listener jobs deliberately push nothing - see the Camunda 8 wiki.
- **Adapter authors:** the adapter SPI `WorkflowTaskInvoker` gained
  `syncedWorkflowAggregateValues(module, process, serializedAggregateId,
  adapterDefault)` for exactly that situation - a task worker holding no aggregate;
  it never throws and returns an empty map on any failure. `WorkflowAggregateSync`
  gained `validateSyncModel(Class)`, called by the PLATFORM at startup (adapters do
  not call it).

## Aggregate sync with the BPMS (2026-08-06)

`@SyncWithBPMS` / `@NoSyncWithBPMS` are implemented. Both annotations
are NEW in spi-for-java (they were documented in the wiki but never shipped), so
nothing existing changes its meaning.

- **Applications:** which attributes of a workflow aggregate the BPMS gets to see
  is now controlled by the two annotations. They may be placed on the aggregate
  class, on attributes and on (intention-revealing) getters. **Inheritance:** every
  attribute inherits the behavior of its owner until it says otherwise - a nested
  object's attributes inherit from the attribute holding them, a collection's
  elements from the collection, and a DTO carrying no annotation of its own
  behaves exactly like the attribute it is used for (annotating the DTO class
  narrows it wherever it is used).
- **The outermost default belongs to the ADAPTER**, because the mechanics do: an
  embedded engine reading the aggregate LIVE shares nothing by default (Camunda 7)
  and writes whatever IS shared as pure operator context; a remote engine shares
  everything by default (Camunda 8, Process-Engine-API), because a BPMN expression
  can only see what VanillaBP pushed as a process variable.
- **The technical aggregate-ID variable is untouched by the model:** a BPMS
  without a business key (Camunda 8, Process-Engine-API) ALWAYS receives the
  aggregate's ID under the name of its ID attribute
  (`AggregatePersistenceAware#getAggregateIdName()`) - that is how VanillaBP finds
  the workflow again. Excluding it is not possible.
- **Sync points** (remote BPMS): starting a workflow (also by message), completing
  or canceling an asynchronous task, completing or canceling a user task, and
  correlating a message. ALL shared values travel at EVERY sync point - a
  changed-values-only optimization is deliberately not done (it would make the
  BPMS' copy a second source of truth). Message CONTENT still never travels
  (payload doctrine); what travels is the aggregate state.
- **Nothing is read back:** process variables never update the aggregate. The only
  variables VanillaBP reads are those a `@TaskParam` explicitly asks for.
- **Adapter authors:** the adapter SPI gained `AggregateSyncMode` (the adapter's
  default) and `WorkflowAggregateSync` (the core-owned model, provided as a
  platform bean); call `syncedValues(aggregate, YOUR_DEFAULT)` at your sync points
  and add the technical ID variable yourself.

## Convention over configuration (2026-08-06)

An application configures only what DEVIATES from the convention.
Everything documented before keeps working unchanged: explicit sections are never
overruled, the validation rules are the same for written and derived entries.

- **One adapter dependency + one workflow module = ZERO `vanillabp.*` properties.**
  The single adapter type found in the classpath becomes the one configured adapter
  (its id IS the type) and, being the only one, the prioritized adapter; every
  workflow module found in the classpath needs no section any more.
- **Several BPMS: `vanillabp.prioritized-adapters` alone is enough** as long as the
  ids ARE adapter types - the adapter sections are derived from that list. Several
  adapter types with NO order configured fail the boot with a message naming the
  types found (it used to ask for adapter sections).
- **A CUSTOM adapter id can never be derived** (it carries no information about its
  type): it still needs `vanillabp.adapters.<id>.type`.
- **`resources-location` is optional now.** BPMN is read from
  `classpath*:<workflow-module-id>/processes/<adapter-id>` - respectively
  `classpath*:processes/<adapter-id>` if the application's own artifact declares the
  single workflow module. An explicit
  `vanillabp.workflow-modules.<module>.adapters.<id>.resources-location` still wins,
  followed by the global `vanillabp.resources-location`. Applications which
  configured a location keep working; those which did not stop failing the boot.
- **Two adapter ids of one type have to be DISTINGUISHABLE** - what that means is
  decided by the adapter through the new SPI hook
  `AdapterDeploymentService#validateDistinctAdapterInstances(List<String>)`
  (default: no check). Camunda 7: different datasources OR different
  `table-prefix` (a NEW key letting two engines share one database); Camunda 8:
  different addresses, respectively the combination of `cluster-id` and
  `client-id`; Process-Engine-API: more than one id of that type fails (its engine
  comes from the application and cannot be told apart).
- **Several datasources demand an explicit one:** an adapter needing a datasource
  (Camunda 7) fails the boot when the application provides more than one and the id
  has no `data-source-name` - not even a `@Primary` bean decides it. Exactly one
  datasource keeps working without configuration.
- **Adapter authors (Spring Boot):**
  `AdapterBeanRegistrarSupport.forEachConfiguredAdapterId` now also yields the
  DERIVED id (= the adapter type) when no adapter section is configured, so
  per-adapter-id beans exist in a zero-configuration application. Adapters reading
  the core `MigrationAdapterProperties` (Quarkus) see the derived entries anyway.

## Viewer/history API (2026-08-06)

`ProcessService#getProcessDefinitions`, `#getBpmnXml` and
`#getWorkflowHistory` are implemented (they were stubs raising
`UnsupportedOperationException` before).

- **Applications:** the three methods are READ-ONLY - no transaction is required,
  nothing is persisted and the workflow is not advanced. Semantics are the V1 ones
  documented in the SPI's README (definitions incl. call activities, BPMN XML,
  element history, `secondaryWorkflowHistoryContext` to dig into call activities).
  An ENDED workflow stays viewable as long as its BPMS holds the data.
- **Process definition ids are opaque and NAMESPACED:**
  `<adapter id>#<BPMS specific id>` (also inside
  `WorkflowHistory#processDefinitionId()`). `getBpmnXml` addresses a process
  DEFINITION, not a workflow - there is no aggregate to elect the BPMS by, so the
  id itself has to name the adapter which can resolve it (essential in migration
  setups where several BPMS serve the same BPMN process). Never parse or compose
  such ids; pass them back unchanged. A malformed id, an id of an unconfigured
  adapter or an unknown definition raises the guiding
  `ProcessDefinitionNotFoundException`.
- **Election:** reads are routed to the BPMS holding the workflow by the same
  probing/caching election as the other operations - with COMPLETED workflows
  being a REGULAR result here (viewers show ended workflows). A workflow no BPMS
  knows raises `WorkflowNotFoundException`.
- **Adapter authors:** `MigratableProcessService` gained three DEFAULT methods -
  `getProcessDefinitions(module, process, persistence, aggregateId, historyContext)`,
  `getBpmnXml(module, process, processDefinitionId)` and
  `getWorkflowHistory(module, process, persistence, aggregateId, historyContext)`.
  Their ids are ADAPTER-NATIVE (the core namespaces them). The defaults throw an
  `UnsupportedOperationException` naming the adapter - implement them, and answer
  "I do not know this workflow" with an empty list / `null` (the core turns that
  into the SPI exceptions). A BPMS without an element history reports
  `WorkflowHistory#elementsHistory()` as `null`, and an eventually consistent BPMS
  reports what is visible - never an error for a lag.

## BPMS election: cache + START re-dispatch mitigation (2026-08-04)

The unified election for operations on existing workflows, an optional
election cache and the at-least-once mitigation for re-dispatched starts.

- **Applications: nothing to change.** Elections now remember which adapter holds a
  workflow (in-memory, bounded to 10 000 entries / 1 h TTL) - repeated operations on
  the same workflow skip the probing walk. Entries are hints only: a stale entry
  costs one extra probe, never correctness.
- **Cluster setups (optional):** define ONE bean implementing the new integration SPI
  `io.vanillabp.integration.spi.WorkflowAdapterCache` (`get`/`put`/`invalidate`) to
  replace the in-memory default with your own shared cache infrastructure -
  instances of a cluster then share elections. VanillaBP deliberately ships no
  distributed implementation.
- **Adapter authors:** `MigratableProcessService` gained the DEFAULT method
  `awarenessOfWorkflowForRedispatch(aggregateId)` (delegates to
  `awarenessOfWorkflow`). It is probed ONLY before re-dispatching a recovered or
  retried two-phase START outbox entry; a workflow already known consumes the entry
  without a second start. STRICTER contract than the election probe: never answer
  optimistically - "unsure" is `UNKNOWN_TO_BPMS` (the idempotent start proceeds; a
  duplicate is the accepted residual, a skipped start would LOSE a workflow).
  Override it if your `awarenessOfWorkflow` is optimistic (Camunda 8 without
  secondary storage, PEA).
- **Custom `PhaseTwoOutbox` stores (optional but recommended):** dispatch via the
  new overload `PhaseTwoRouter.dispatch(call, previouslyAttempted)` and pass
  `true` for entries dispatched before (e.g. an attempts counter claimed BEFORE
  dispatching) - enabling the START mitigation for your store. The old
  single-argument `dispatch(call)` keeps working (mitigation off).
- The residual at-least-once window is MINIMIZED, not closed - never build on
  exactly-once starts (see the adapters' READMEs).

## Message correlation + start-by-message (2026-08-04)

`ProcessService#correlateMessage(aggregate, messageName[, correlationId])`
and `#startWorkflowByMessage(aggregate, messageName)` are implemented.
Application-facing:

- **Payload doctrine (codified):** message CONTENT never travels to the BPMS - the
  aggregate is the single source of truth. Correlation transports the message name
  and the optional correlation id, nothing else; start-by-message additionally sets
  only the technical aggregate-ID variable.
- **Correlation targets the BPMS the instance runs in** (probing
  `awarenessOfWorkflow` through the `WorkflowLocator`); a workflow no adapter knows
  raises the guiding `WorkflowNotFoundException` (hinting at
  `startWorkflowByMessage`); a COMPLETED workflow makes correlating a warned no-op.
  Start-by-message uses START semantics: first-priority adapter, its ID persisted
  with the outbox entry, at most one workflow per aggregate.
- **Idempotency (persisted contract):** WITH a correlation id the outbox entry's
  key is `module|process|aggregateId|messageName|correlationId` (duplicate
  schedules are no-ops; Camunda 8 additionally deduplicates redeliveries
  engine-side via the message id); WITHOUT one there is NO key - the same message
  may legitimately be correlated multiple times, and an at-least-once redelivery
  may double-correlate (documented per adapter).
- **Correlation-id semantics per adapter:** the technical correlation key is the
  AGGREGATE ID by default. Camunda 7 disambiguates BETWEEN waiting occurrences via
  the V1 local-variable convention `<primary bpmnProcessId>-<messageName>`;
  Camunda 8 uses the correlation id AS the correlation key (the model's
  subscription must then reference the matching variable).
- **Camunda 8 subscription wiring:** `wireBpmn` INJECTS the
  `zeebe:subscription correlationKey` expression `=<aggregate-ID variable>` into
  message subscriptions lacking one - message catch events correlate via the
  aggregate ID without manual model tweaks; existing expressions stay untouched
  (V1 models deploy byte-identically).
- **Adapter authors:** `MigratableProcessService` gained
  `correlateMessagePhaseOne/Two` and `startWorkflowByMessagePhaseOne/Two`;
  `awarenessOfWorkflow` is now called by the core (correlation probing).

## User tasks (2026-08-04)

`ProcessService#completeUserTask`/`#cancelUserTask` are implemented and
`@WorkflowTask` methods may be wired to BPMN USER tasks as OPTIONAL notification
handlers. Application-facing:

- **User-task task definitions by convention:** Camunda 7 - the task's
  `camunda:formKey`; Camunda 8 and the Process-Engine-API - the
  `zeebe:formDefinition` EXTERNAL form reference (the V1 conventions). The handler
  receives `CREATED` when the task shows up (with the task's ID as `@TaskId` - the
  handle for `completeUserTask`/`cancelUserTask`) and - where the BPMS can deliver
  it - `CANCELED` when the task's activity is canceled (C7: yes; C8: yes via the
  V1-compatible `canceling` task listener; PEA: no - see the adapter READMEs).
- **User-task handlers are OPTIONAL:** a user task WITHOUT a matching
  `@WorkflowTask` method does not fail the wiring validation (it is simply
  processed through forms/task lists); a matching method still counts as wired.
  Notification handlers must NOT throw `TaskException` (there is nothing to
  complete by BPMN error - a guiding error explains this).
- **`completeUserTask`/`cancelUserTask`** follow the async-task flow: active
  transaction required, adapter elected by probing (`awarenessOfUserTask`),
  embedded BPMS execute in phase one, remote BPMS after the commit via the outbox
  (operations `COMPLETE_USER_TASK`/`CANCEL_USER_TASK` - stores transporting
  `PhaseTwoCall.args()` need NO changes). LIMITATION: `cancelUserTask` is
  UNSUPPORTED on Camunda 8.8 (the engine has no command to cancel a
  Camunda-managed user task by BPMN error; a guiding error explains it - expected
  to arrive with the 8.10 listener support).
- **V1 BPMN compatibility (Camunda 8):** the adapter adds the SAME
  `zeebe:taskListener`s as V1 (`creating`/`canceling`, type
  `io.vanillabp.userTask:<external form reference>`, `retries="0"`, same insertion
  order) - upgrading a V1 application produces a byte-identical BPMN, so no new
  process version is deployed.
- **Adapter authors:** `MigratableProcessService` gained `awarenessOfUserTask` and
  the four user-task phase methods; `BpmnTaskSpec` gained the `optional` component
  (`BpmnTaskSpec.userTask(...)` factory); `WorkflowTaskInvoker` gained
  `workflowTaskHandlerExists(...)` for optional notifications.

## Asynchronous task completion + spi-for-java 1.2.0 (2026-08-04)

`ProcessService#completeTask`/`#cancelTask` are implemented; the platform
now builds against **spi-for-java 1.2.0-SNAPSHOT** (the "Harden SPI" release: the
message-OBJECT overloads of `startWorkflowByMessage`/`correlateMessage` were removed
and the query methods became `default`). Application-facing:

- **`completeTask(aggregate, taskId)` / `cancelTask(aggregate, taskId, errorCode)`
  work now** for asynchronous tasks (`@TaskId` handlers). Both REQUIRE an active
  transaction (same rule as `startWorkflow`). The BPMS holding the task is located
  by probing the prioritized adapters (`awarenessOfTask`) - the first real use of
  the awareness SPI. A task no adapter knows raises the new guiding
  `io.vanillabp.spi.process.TaskNotFoundException`; a task reported COMPLETED makes
  the operation a warned no-op (idempotent completion). While a probed BPMS is
  UNAVAILABLE the walk retries shortly (2 x 500ms, inside the caller's transaction)
  and then fails naming the adapter - it NEVER falls back to another adapter.
- **`@TaskEvent` lifecycle delivery:** methods with a `@TaskEvent` parameter
  subscribing to `CANCELED` (or `ALL`) are invoked when their open task's activity
  is canceled (Camunda 7: boundary events/instance termination; Camunda 8 and the
  Process-Engine-API cannot deliver cancellations - see the adapters' READMEs).
  Methods WITHOUT a `@TaskEvent` parameter only ever receive CREATED deliveries -
  a CANCELED delivery is skipped entirely (no transaction, no side effects).
- **Custom outbox stores:** two new `PhaseOperation`s (`COMPLETE_TASK`,
  `CANCEL_TASK`) flow through the UNCHANGED `schedule(PhaseTwoCall)` contract -
  stores need no code changes IF they persist `PhaseTwoCall.args()` (the task ID
  and error code travel there; helpers
  `PhaseTwoCall.serializeArgs`/`deserializeArgs` define the persisted encoding).
  The platform's own stores were extended accordingly; the QUARKUS JDBC outbox
  gained an `ARGS VARCHAR(2048)` column - **existing tables need
  `ALTER TABLE VANILLABP_PHASE_TWO_OUTBOX ADD ARGS VARCHAR(2048)`** (or drop the
  table and let `create-schema` recreate it). Unlike workflow starts these
  operations persist NO adapter ID: the executing adapter is elected at dispatch
  time by probing.
- **Adapter authors:** `MigratableProcessService` gained four methods
  (`completeTaskPhaseOne/Two`, `cancelTaskPhaseOne/Two`). Phase one must never
  advance the process: embedded BPMS complete entirely in phase one (shared
  transaction), remote BPMS only run a non-advancing existence check - ideally
  registered as a PRE-COMMIT synchronization (Camunda 8's
  `Camunda8PreCommitRegistrar` is the reference). Phase two is at-least-once: a
  gone task is logged and tolerated, never an error.
- **Removed SPI overloads:** code calling `startWorkflowByMessage(aggregate,
  messageObject)` or `correlateMessage(aggregate, messageObject[, correlationId])`
  has to switch to the String-`messageName` variants (spi-for-java 1.2.0).

## Task processing: core-managed transactions + adapter SPI additions (2026-08-01)

`@WorkflowTask` methods are executed by the core (load aggregate -
invoke - save, within one transaction). Application-facing:

- **Do not annotate `@WorkflowTask` methods (or their services) with your own
  `@Transactional`.** VanillaBP manages the transaction, including the V1 contract
  that a `TaskException` COMMITS the aggregate changes while completing the task
  with a BPMN error - the V1 pattern
  `@Transactional(noRollbackFor = TaskException.class)` is built in now and an
  application-declared transaction boundary around the handler may change the
  rollback semantics.
- **`@WorkflowService.secondaryBpmnProcesses` is honored now** (it was ignored by
  the platform integrations before): every declared BPMN process ID of a class is
  wired for task processing and phase-two routing. Secondary entries have to name
  an explicit `bpmnProcessId`.
- **Several `@WorkflowService` classes declaring the SAME BPMN process for
  DIFFERENT aggregates:** the class found first wins (V1 semantics), later classes
  are skipped for that process with a startup warning.
- **Adapter SPI additions** (BPMS-adapter authors only): new package
  `io.vanillabp.integration.adapter.spi.workflowtask` (`WorkflowTaskInvoker`,
  `TaskInvocationContext`, `WorkflowTaskOutcome`, `BpmnTaskSpec`,
  `MultiInstanceValue`) - adapters validate task wiring during `wireBpmn` and
  dispatch delivered tasks through the invoker; the three outcomes are mapped by
  the adapter to its BPMS (complete / complete-with-BPMN-error / leave open plus
  exception propagation for BPMS-side retries). The invoker also provides
  `validateNoUnwiredWorkflowTaskMethods(module)` (per-module reverse wiring check, called
  at the end of `deployResources` - methods may serve any of their class' declared BPMN
  processes) and
  `resolveWorkflowAggregateProperty(...)` (embedded BPMS evaluating BPMN
  expressions against the workflow aggregate). It also provides
  `resolveWorkflowAggregateIdName(module, process)` - remote BPMS store the
  aggregate ID as a variable named after the aggregate's ID property
  (`AggregatePersistenceAware.getAggregateIdName()`), and the adapter needs that
  name to read the ID back from a delivered task's payload.

## Per-aggregate outbox selection + aggregate-ID type in the persistence SPI (2026-07-31)

Two related consolidations, breaking for applications using their own
`PhaseTwoOutbox` bean and behavior-changing for mixed-persistence classpaths:

- **Outbox SPI relocated to the integration SPI** (breaking for applications
  implementing a custom outbox): `PhaseTwoOutbox`, `PhaseTwoCall`,
  `PhaseOperation` and the new `PhaseTwoOutboxAware` moved from the adapter SPI
  (`io.vanillabp.integration.adapter.spi`, module `vanillabp-adapter-spi`) to the
  integration SPI (`io.vanillabp.integration.spi`, module `vanillabp-integration-spi`,
  next to `AggregatePersistenceAware`). The split is deliberate: the adapter SPI is
  for BPMS-adapter implementations, the integration SPI for business-process
  applications - and custom outboxes are contributed by APPLICATIONS. No
  adapter-facing signature ever referenced these types; only the import changes.
- **The phase-two outbox is selected PER AGGREGATE, not per JVM.** New integration SPI
  `io.vanillabp.integration.spi.PhaseTwoOutboxAware<A>` (most-specific
  aggregate class wins - same selection as `AggregatePersistenceAware`, now shared
  via the core's `AwareSelection`). Both platform defaults (JDBC and MongoDB) may
  COEXIST: the `@ConditionalOnMissingBean(PhaseTwoOutbox.class)` single-bean gate is
  gone; each aggregate is served by the outbox matching its persistence (Spring:
  detected from the aggregate's Spring Data repository type; Quarkus: no platform
  detection - mixed setups attribute aggregates via `PhaseTwoOutboxAware` beans).
  This fixes the mixed-persistence atomicity bug (a Mongo aggregate's outbox entry
  used to ride the JPA transaction) and enables a DEDICATED outbox for a high-load
  process. **Behavior changes:** (a) applications having both JPA and MongoDB on the
  classpath now get BOTH default outboxes (stores + background dispatchers) - opt
  out via `vanillabp.outbox.jdbc.enabled` / `vanillabp.outbox.mongo.enabled`;
  (b) an application-defined `PhaseTwoOutbox` bean no longer suppresses the
  defaults - disable them via those flags or attribute aggregates via
  `PhaseTwoOutboxAware`; (c) a single default outbox clearly not matching an
  aggregate's detectable persistence now fails the startup (it silently broke
  atomicity before).
- **Outbox resolved AT STARTUP** (removes 26c's deliberately-left lazy check): per
  process service, if the first-priority adapter needs a two-phase commit the
  outbox is resolved once the context is ready (Spring:
  `SmartInitializingSingleton`; Quarkus: an inherited `StartupEvent` observer on
  the generated process-service beans - which also makes ALL process-service
  validations fire at boot on Quarkus, e.g. the unserved-prioritized-adapter-id
  fail-fast). Missing outbox → boot fails naming all remedies. If no two-phase
  commit is needed, nothing materializes.
- **Store names configurable** (`vanillabp.outbox.jdbc.table`,
  `vanillabp.outbox.mongo.collection`): every outbox instance needs its own store -
  two dispatchers polling the same store would double-dispatch. Note: the gruelbox
  schema migration always targets `TXNO_OUTBOX`, so a custom table on Spring must
  be created manually. The Spring gruelbox default beans now reference each other
  BY NAME (`vanillaBpTransactionOutbox`), so additional gruelbox instances do not
  suppress the default; with several transaction managers (mixed persistence) the
  JDBC one must be named `transactionManager` (Boot's convention).
- **`AggregatePersistenceAware.getAggregateIdType()`** (default: reflection-based
  detection via `AggregateIdTypes`, `null` = custom persistence owns the serialized
  form; Spring Data implementations answer authoritatively). The aggregate-ID
  round-trip validation and the String→ID conversion moved into the core
  (`AggregateIdRoundTrip`, one explicit allow-list) - the platform pair
  (`ProcessServiceSpringBean.validateAggregateIdRoundTrip`/`buildAggregateIdConverter`,
  Quarkus `AggregateIdConversion`) is deleted, `ProcessServiceSpringBean` lost its
  `springDataUtilProvider` parameter and `PhaseTwoRouter.register` its converter
  parameter (`MigrationProcessService.convertAggregateId` replaces it).

## Startup configuration validation (2026-07-31)

Configuration defects surface at startup, never first at runtime:

- **New core helper `MigrationAdapterProperties.isFirstPriorityAnywhere(adapterId)`**:
  true if the adapter id is FIRST in the prioritized-adapters list globally, of any
  workflow module or of any workflow. Rule for adapters: an adapter that is first
  anywhere always fails the boot on an inconsistent connection configuration; only
  an adapter that is nowhere first may honor `deployment-failure: warn` and boot
  degraded (migration scenario: the old BPMS must not block the boot).
- **Three states of an adapter's config section**: absent → the application boots
  and a guiding WARN names the exact property keys to add; complete → silent;
  inconsistent → boot fails naming the missing keys (unless the degrade rule above
  applies). Messages name property KEYS, never VALUES - credentials are never
  echoed (asserted by boot tests).
- **Aggregate-ID round-trip check at ProcessService creation** (both platforms):
  the aggregate ID crosses the phase-two outbox serialized as a String; an ID type
  that does not convert from/to String losslessly now fails the startup with a
  guiding message (Spring: `DefaultConversionService` both directions; Quarkus:
  supported-type set in `AggregateIdConversion`). Custom
  `AggregatePersistenceAware` implementations (no determinable ID type) are
  exempt - they own the serialized form.

Deliberately still lazy (documented, not defects): phase-two outbox resolution
(optional dependency, guiding message on first use), the PEA mock's VOLATILE
warning (fires when the engine producer runs).

## Adapter config model: per-id beans, canonical location, level resolution (2026-07-30)

Three related changes, breaking for adapters and early Camunda 8 users:

- **One process service AND one deployment service per configured adapter id**
  (multiple ids of one BPMS type = the migration scenario). Spring: adapters
  register element beans programmatically via a `BeanRegistrar` using the new
  platform helper `AdapterBeanRegistrarSupport.forEachConfiguredAdapterId`; the
  adapter id is a constructor parameter. Quarkus: adapters produce ONE bean of
  type `List<MigratableProcessService<Object>>` /
  `List<AdapterDeploymentService<Model, Context>>` per adapter (a CDI producer
  cannot yield N element beans for N runtime-config ids); the platform flattens
  List beans alongside element beans and keeps them from ArC's unused-bean
  removal (`keepPerAdapterIdListBeans`). HARD RULE: the List element type is the
  SPI interface literally (CDI does not match subtypes in type arguments).
- **Camunda 8 configuration relocated** (BREAKING for early users): the
  provisional flat namespace `camunda8-adapter.<id>.*` is GONE; the connection
  keys (`mode`, `rest-address`, `grpc-address`, `prefer-rest-over-grpc`,
  `tenant-id`, `cluster-id`, `region`, `client-id`, `client-secret`) now live at
  the canonical per-adapter location `vanillabp.adapters.<id>.*`, contributed via
  the overlay pattern on both platforms. The last
  `getPropertyNames()`-based key parsing was deleted with it.
- **Level resolution in core:** `MigrationAdapterProperties.resolveForAdapter(
  module, process, task, adapterId, extractor)` resolves adapter-scoped
  properties most-specific-wins across task > workflow > workflow-module >
  adapter. The property model gained the workflow-level `adapters` map and the
  task-level slot (`workflows.<w>.tasks.<t>.adapters.<id>.*`) - structural
  preparation for what came later (workflow-level config is still rejected at
  startup).

## `vanillabp.outbox.*` consolidated onto the core model (2026-07-30)

Follow-up of the config-binding consolidation of the same day: the outbox
configuration was modeled per platform (Spring
`io.vanillabp.integration.outbox.PhaseTwoOutboxProperties`, Quarkus nested
`@ConfigMapping` interface) with duplicated defaults and javadoc. Now the core
class `io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties`
is the single source of truth (attached to `MigrationAdapterProperties.outbox`,
bound as part of the `vanillabp.*` tree). **Keys and defaults unchanged**
(PT10S / PT30S / 10 / true / P7D - pinned by a core unit test).

- Spring: the standalone properties class is DELETED; the outbox
  auto-configurations consume `MigrationAdapterProperties.getOutbox()`. The
  outbox key descriptions moved into
  `additional-spring-configuration-metadata.json`.
- Quarkus: the nested `@ConfigMapping` interface stays (SmallRye needs
  `@WithDefault`), the dispatchers consume the CORE object via the generated
  mapper; the necessarily duplicated defaults (interface vs. core) are pinned
  equal by `QuarkusMigrationAdapterPropertiesMapperTest`.

## Configuration binding consolidated onto the core model (2026-07-30)

The `vanillabp.*` tree was modeled three times (core POJOs,
`SpringBootMigrationAdapterProperties`, `QuarkusMigrationAdapterProperties`)
with two hand-written copy transformers. Now the tree is modeled ONCE in the
core and bound natively per platform. **Zero user-visible config-key changes.**

- **Core model reshape:** `MigrationAdapterProperties.adapters` is now
  `Map<String, AdapterConfigProperties>` (fields `type`, `deploymentFailure`),
  matching the user-facing keys `vanillabp.adapters.<id>.{type,deployment-failure}`
  1:1 (prerequisite for direct binding). The
  separate `deploymentFailures` map is gone; `getDeploymentFailureFor(id)` stays.
  The id→type view is `adapterTypes()` (deliberately NOT a JavaBean getter - the
  binder and the metadata processor must not see it as a property).
- **`deployment-failure` is bound as the core enum** on both platforms
  (case-insensitively; Spring via a `@ConfigurationPropertiesBinding` converter,
  Quarkus via SmallRye's enum converter). An invalid value fails naming the
  offending key and the allowed values - the former aggregate message
  ("These values are invalid: ...") is replaced by the platform's bind failure
  carrying "must be one of 'fail' or 'warn'" (Spring) / SmallRye's
  allowed-values message (Quarkus).
- **Core `normalize()` + validation absorb the last duplicated logic:** type
  defaulting (`type` absent → id is the type), single-adapter
  `prioritized-adapters` defaulting, the "No adapters configured!" message
  (Quarkus' `xxxx` placeholder aligned to `xxx`) and the workflow-level
  rejection (the Quarkus message changed from listing raw keys to the core
  wording `...Remove these properties: vanillabp.workflow-modules.<id>.workflows`).
  The check "deployment-failure configured for unknown adapter id" is gone -
  structurally impossible now (the policy lives inside the adapter's section).
- **Spring binds the core POJOs directly:** `SpringBootMigrationAdapterProperties`
  and the transformer are DELETED; the thin subclass
  `VanillaBpConfigurationProperties` carries `@ConfigurationProperties("vanillabp")`.
  The module now ships IDE metadata (`spring-configuration-metadata.json` incl.
  hand-written descriptions of the stable top-level keys).
- **Quarkus keeps `@ConfigMapping`; the transformer shrank to capability checks
  plus a GENERATED MapStruct `toCore()`** (`unmappedSourcePolicy`/
  `unmappedTargetPolicy = ERROR`: adding a property to only one side fails the
  build). The SmallRye interface's fluent accessors are made visible to MapStruct
  by the new build-time-only artifact `vanillabp-mapstruct-fluent-accessors`
  (annotation-processor path only - build the reactor with `install`, not
  `package`).
- **Blanket `withMappingIgnore("vanillabp.**")` dropped:** adapter extensions
  now register an OVERLAY `@ConfigMapping(prefix = "vanillabp")` for their own
  keys (reference: `DummyAdapterOverlayProperties` of the BPMS double's Quarkus module;
  Spring counterpart: a second `@ConfigurationProperties("vanillabp")` class).
  Consequence: a typo under `vanillabp.*` FAILS the Quarkus startup again
  (Spring's JavaBean binding stays lenient - accepted asymmetry).
- **Environment-variable misbinding validation:** env vars cannot introduce NEW
  dashed adapter/module ids (they can only override entries declared in config
  files). `VANILLABP_*` variables whose id segment matches no configured id now
  fail the startup with a guiding message on both platforms
  (`MigrationAdapterProperties.validateEnvironmentVariableUsage`).

## Aggregate-ID storage is the adapter's decision (2026-07-30)

Review feedback on the hardening story: the shared SPI constant
`MigratableProcessService.AGGREGATE_ID_VARIABLE` (`"aggregateId"`) was removed
again. How the workflow aggregate's ID is stored in the BPMS is the **adapter's
decision**, not a cross-adapter contract: Camunda 7 uses its dedicated business
key, whereas Camunda 8 stores the aggregate as process variables and therefore
names the variable carrying the ID after the aggregate's ID property (the
Process-Engine-API adapter follows the Camunda 8 model).

- **`AggregatePersistenceAware.getAggregateIdName()` added** (integration SPI,
  `default` method with a guiding message like the other methods). The
  Spring-Data-based support implements it via `SpringDataUtil.getIdName`.
- **`startWorkflowPhaseTwo` signature changed** (adapter SPI, breaking for BPMS
  adapters): `startWorkflowPhaseTwo(module, process, aggregateId)` →
  `startWorkflowPhaseTwo(module, process, aggregatePersistence, aggregateId)` -
  phase two is dispatched from the outbox (possibly after a restart), so the
  adapter has no other way to obtain the aggregate's ID-property name there.
  Phase one already carried the persistence support.

## Validation parity, ProcessService stubs, SPI alignments (2026-07-28)

Hardening changes relevant for adapters and early adopters:

- **One validation, in core:** `MigrationAdapterProperties.validateProperties` is
  now called on BOTH platforms (Quarkus previously re-implemented the checks
  inline and diverged). The transformers only map platform bindings and check
  what only the platform can know (adapters present in classpath, workflow-level
  rejection, deployment-failure value parsing, Quarkus extension/capability
  consistency). Behavior changes: configuration for a workflow module NOT in the
  classpath only WARNS (previously Quarkus failed the build); new checks reject
  unused `vanillabp.workflow-modules.<m>.adapters.<id>` entries and duplicates in
  `prioritized-adapters` lists.
- **`vanillabp.resilience.*` removed entirely** ("optimize late" - it was mapped
  and validated but never consumed). Retry/timeout design returns per adapter
  with the first consumer (complete/cancel-task story).
- **`ProcessService` operations not yet implemented throw
  `UnsupportedOperationException`** ("not yet supported by VanillaBP 2") from the
  new platform-neutral base `ProcessServiceBase` - replacing Spring's silent
  no-ops and Quarkus' raw `AbstractMethodError`.
- **`WorkflowAwareness` constants renamed:** `TASK_ACTIVE` → `ACTIVE`,
  `TASK_COMPLETED` → `COMPLETED` (the enum answers for workflows AND tasks; both
  constants were unused so far, no adapter is affected).
- **`AggregatePersistenceAware.loadById(Object)` added** (integration SPI; needed by
  the task-processing and sync stories). The platform-provided supports implement
  it (Spring Data: `findById`). Additionally ALL methods of the interface are now
  `default` methods throwing an `UnsupportedOperationException` with a guiding
  message - future method additions stay source-compatible for hand-written
  implementations; the platform supports override everything.
- **Naming unified:** `MigrationProcessService.needsTransactionForStartingWorkflows`
  → `needsTwoPhaseCommitForStartingWorkflows` (the SPI term).
- **Deployment pipeline hygiene:** BPMN input streams are owned and closed by the
  pipeline (adapters must not close them); `prepareBpmn` must return non-null;
  modules without any executable BPMN process are skipped with a warning instead
  of calling adapters with a null processing context; extension matching uses
  declared-type assignability everywhere (wiring previously matched on actual
  instances while start/stop matched on declared types).

## Phase-two chain collapsed: `PhaseTwoCall` + `PhaseTwoRouter` (2026-07-28)

Breaking changes of the outbox part of the adapter SPI and the platform beans
(adapters are not affected — `MigratableProcessService` is unchanged):

- **Removed:** `PhaseTwoDispatch` and `ProcessServicePhaseTwo` (SPI) and the
  platform dispatch beans (`PhaseTwoDispatchSpringBean`,
  `QuarkusPhaseTwoDispatch`). The chain
  Outbox → `PhaseTwoDispatch` → `ProcessServicePhaseTwo` →
  `MigrationProcessService` had two layers too many; it is now
  Outbox → `PhaseTwoRouter` (core-owned, `migration-adapter` runtime) →
  `MigrationProcessService` → adapter. Platform process-service beans register
  with the router at bean creation (including a `Function<String,Object>`
  converting the serialized aggregate ID back to the aggregate's ID type —
  conversion happens exactly once, in the router).
- **`PhaseTwoOutbox` reworked (hybrid):** one abstract method
  `boolean schedule(PhaseTwoCall call)`; typed default methods
  (`scheduleStartWorkflow(module, process, aggregateId, adapterId)`) build the
  new immutable `PhaseTwoCall` record and delegate. The signature gained
  `adapterId`: the adapter elected in phase one IS persisted for start operations
  and used at dispatch time (no re-election; stale entries after configuration
  changes yield a guiding error). Contract additions: unique idempotency key
  (store-level unique constraint, duplicate schedule = no-op returning `false`),
  DONE instead of delete (async cleanup after `vanillabp.outbox.retention`,
  default 7 days), documented at-least-once residual window. Key derivation rules
  live on `PhaseOperation` and are a persisted contract.
- **Store schemas changed** (entries of the previous format are not migrated —
  never released): Quarkus JDBC table `VANILLABP_PHASE_TWO_OUTBOX` gained
  `ADAPTER_ID`, `IDEMPOTENCY_KEY` (unique), `STATUS`, `DONE_AT` and dropped
  `AGGREGATE_ID_TYPE`; the Mongo collection analogously (sparse unique index on
  `idempotencyKey`). Gruelbox maps the contract natively
  (`uniqueRequestId` + retention threshold).
- **`vanillaBpOutboxTaskScheduler` beans deleted (Spring):** the outbox
  dispatchers run on private single-thread executors; an application's
  `@EnableScheduling`/`TaskScheduler` setup is no longer affected.
- **`@Transactional` removed** from the former
  `ProcessServiceSpringBean.startWorkflowPhaseTwo` (the method itself is gone;
  phase two needs no local transaction — dual-TM applications broke on it).

## Adapter start phases carry module + process id (2026-07-09)

Breaking change of `MigratableProcessService`, relevant for BPMS adapters:

- `startWorkflowPhaseOne(aggregatePersistence, aggregate)` →
  `startWorkflowPhaseOne(String workflowModuleId, String bpmnProcessId,
  aggregatePersistence, aggregate)`.
- `startWorkflowPhaseTwo(aggregateId)` →
  `startWorkflowPhaseTwo(String workflowModuleId, String bpmnProcessId, aggregateId)`.

Reason: a `MigratableProcessService` is one bean per adapter id, shared across all
processes; without the module and process id an adapter cannot tell which process to
start (embedded engines need the BPMN process id to select the process and the module
id as the BPMS tenant; remote engines need the process id for the create-instance
command). The methods' own documented idempotency key
(`workflowModuleId + bpmnProcessId + workflowAggregateId`) was previously
unconstructible. Both values are forwarded from `MigrationProcessService`, which holds
them as fields. `awarenessOfTask`/`awarenessOfWorkflow` still take only the aggregate
id — adding module/process parameters was evaluated while the three real adapters were
implemented, and the signatures STAY: Camunda 7 locates a task by the
globally unique execution ID (verified against the business key - no tenant scoping
needed), Camunda 8 by the globally unique job key, and the Process-Engine-API by the
task ID alone. No real implementation needed the additional parameters.

## Phase-two outbox restructured (2026-07-09)

Breaking changes of the outbox part of the adapter SPI, relevant for custom
`PhaseTwoOutbox` implementations (adapters are not affected —
`MigratableProcessService` is unchanged):

- `PhaseTwoOutbox.schedule(module, process, adapterId, aggregateId)` →
  `scheduleStartWorkflow(module, process, aggregateId)`. The adapter is no longer
  part of the scheduled call: it is determined at dispatch time by
  `MigrationProcessService` (starting a workflow always uses the highest-priority
  adapter; upcoming `ProcessService` operations will probe the prioritized adapters
  instead). Every future two-phase operation gets its own `schedule*` method.
- `MigratableProcessServicePhaseTwo` (4-arg method incl. `adapterId`) was replaced
  by `PhaseTwoDispatch` (3-arg, no `adapterId`) — the platform-provided bean outbox
  implementations dispatch to.
- New interface `ProcessServicePhaseTwo`: implemented by the platform integrations'
  process-service beans; `PhaseTwoDispatch` implementations use it to route a
  dispatched call to the bean of the workflow module/BPMN process it belongs to.
- Store-based default implementations (Spring MongoDB, Quarkus JDBC) now persist an
  `operation` discriminator instead of the adapter ID (JDBC column `ADAPTER_ID` →
  `OPERATION`; entries of the previous format are not migrated — the table/collection
  was never part of a release).

## Adapter SPI consolidation (2026-07-05)

Breaking changes of the adapter SPI, relevant for the upcoming adapter repositories
(there are no adapters built against the previous signatures yet):

### New module `io.vanillabp:vanillabp-integration-spi` (integration SPI)

The SPI was split into an *integration SPI* (interfaces business code may implement) and
the *adapter SPI* (`io.vanillabp:vanillabp-adapter-spi`, implemented by BPMS
adapters and platform integrations):

- `AggregatePersistenceAware` now exists exactly once:
  `io.vanillabp.integration.spi.AggregatePersistenceAware` in
  `vanillabp-integration-spi`. The three byte-identical copies
  (`io.vanillabp.integration.adapter.spi.*` in the adapter SPI,
  `io.vanillabp.integration.spi.aggregate.*` in `vanillabp-spring-boot-support`,
  `io.vanillabp.integration.spi.*` in `vanillabp-quarkus-support`) and both
  `AggregatePersistenceAwareWrapper` classes were removed. The support modules
  provide the interface transitively, so business code keeps depending on the
  support module only (Spring Boot users have to adjust the import from
  `io.vanillabp.integration.spi.aggregate` to `io.vanillabp.integration.spi`).

### `AdapterDeploymentService<BPMN, DMN, PC>` → `AdapterDeploymentService<BPMN, PC> extends ExtensionWiringService<BPMN, PC>`

- The unused `DMN` type parameter was removed (DMN support will be added once
  designed).
- The adapter interface no longer declares `getModelType()`,
  `getProcessContextType()`, `wireBpmn(...)`, `startWorkflowProcessing(...)` and
  `stopWorkflowProcessing(...)` itself — they are inherited from
  `ExtensionWiringService` (an adapter is "the wiring service with deployment").
- `ExtensionWiringService.getOrder()` got a `default 0`, so adapters need not
  implement it.
- `ExtensionWiringService.stopWorkflowProcessing(...)` (default no-op) is called on
  graceful shutdown in reverse start order (extensions first, then adapters) —
  wired by Spring Boot's `SmartLifecycle.stop()` and a Quarkus `ShutdownEvent`
  observer.

### `MigratableProcessService`: awareness instead of `isTaskActive`

`Boolean isTaskActive(String taskId)` was replaced by:

```java
WorkflowAwareness awarenessOfTask(Object workflowAggregateId, String taskId);
WorkflowAwareness awarenessOfWorkflow(Object workflowAggregateId);
```

with `enum WorkflowAwareness { TASK_ACTIVE, TASK_COMPLETED, UNKNOWN_TO_BPMS,
BPMS_UNAVAILABLE }`. Contract: `BPMS_UNAVAILABLE` means "do not fall back to the next
adapter — retry later"; only `UNKNOWN_TO_BPMS` permits falling back. The
instance-level method exists because message correlation has no task ID and task IDs
are not unique across BPMSs. `startWorkflowPhaseOne` now uses
`io.vanillabp.integration.spi.AggregatePersistenceAware` (import change only).

### New configuration

- `vanillabp.adapters.<id>.deployment-failure` = `fail` (default) | `warn`:
  with `warn` a deployment failure of a NON-first-priority adapter is logged and the
  application still starts; a failure of the first-priority adapter always fails the
  boot.
- `vanillabp.resilience.{max-retries,initial-interval,multiplier,timeout}`:
  retry/backoff settings for eventually-consistent BPMS calls, overridable per
  workflow module and (once supported) per workflow — the most specific block wins
  as a whole.

## Quarkus 3.26.4 → 3.37.1 (2026-07-05)

Version bump in `quarkus-integration/pom.xml` (`quarkus.version`). One real change was
required:

### Config root phase changed to RUN_TIME

`QuarkusMigrationAdapterProperties` was declared as
`@ConfigRoot(phase = BUILD_AND_RUN_TIME_FIXED)`. Values of such config roots are read
at **build time** and frozen. The workflow-module-specific config files
(`<module-id>.properties/.yaml`) are added by generated config builders to the
static-init/runtime config only — they are never part of the build-time
configuration.

Up to Quarkus 3.26 this worked **by accident**: the `@ConfigMapping` instance was
re-populated at static init against the full config (including the module config
sources). Since the Quarkus config optimization that introduced the generated
`SharedConfig` class (mapping instances are created once from build-time values and
reused in static-init and runtime config via `withMappingInstance`), the mapping's
`workflowModules()` map stayed empty at runtime, and all `QuarkusProdModeTest`s that
actually launch the application failed with:

```
IllegalStateException: No workflow-modules configured! Add properties sections
'vanillabp.workflow-modules.<id>' ...
```

Fix (also semantically the right choice, because VanillaBP configuration such as
adapter endpoints must be overridable per environment, e.g. via environment
variables):

- `QuarkusMigrationAdapterProperties`: `ConfigPhase.BUILD_AND_RUN_TIME_FIXED` →
  `ConfigPhase.RUN_TIME`
- `ConfigBuildStepProcessor.buildMigrationAdapterProperties`:
  `@Record(ExecutionTime.STATIC_INIT)` → `@Record(ExecutionTime.RUNTIME_INIT)`
  (the synthetic `MigrationAdapterProperties` bean was already `setRuntimeInit()`)

Diagnosis hint for similar problems: decompile
`io/quarkus/runtime/generated/StaticInitConfig*.class` and `SharedConfig.class` from
the `generated-bytecode.jar` of a prod-mode build — they show which config builders,
sources and mapping instances are actually wired.

## Spring Boot 3.5.x → 4.1.0 (2026-07-05)

Spring Boot 4 modularized the formerly monolithic `spring-boot-autoconfigure` and
`spring-boot-test-autoconfigure` artifacts: technology-specific auto-configurations and
test slices now live in their own modules with new package names. The starters
(`spring-boot-starter-data-jpa`, `spring-boot-starter-data-mongodb`,
`spring-boot-starter-test`) kept their names and pull the new modules in transitively —
**except test slices**, which now require an explicit dependency.

### Version bumps

- `spring-boot-integration/pom.xml`: `spring-boot-dependencies` BOM 3.5.5 → 4.1.0.
- `test-utils/pom.xml` (plain-Java module with optional Spring deps, no BOM):
  `spring-beans`/`spring-context` 6.2.14 → 7.0.8 (Spring Framework 7),
  `spring-boot`/`spring-boot-test` 3.5.5 → 4.1.0.

### Moved classes (import changes)

|                 Class                  |                Old package (Boot 3.x)                 |                 New package (Boot 4.x)                 |         New module          |
|----------------------------------------|-------------------------------------------------------|--------------------------------------------------------|-----------------------------|
| `@EntityScan`                          | `org.springframework.boot.autoconfigure.domain`       | `org.springframework.boot.persistence.autoconfigure`   | `spring-boot-persistence`   |
| `HibernateJpaAutoConfiguration`        | `org.springframework.boot.autoconfigure.orm.jpa`      | `org.springframework.boot.hibernate.autoconfigure`     | `spring-boot-hibernate`     |
| `MongoClientSettingsBuilderCustomizer` | `org.springframework.boot.autoconfigure.mongo`        | `org.springframework.boot.mongodb.autoconfigure`       | `spring-boot-mongodb`       |
| `@DataJpaTest`                         | `org.springframework.boot.test.autoconfigure.orm.jpa` | `org.springframework.boot.data.jpa.test.autoconfigure` | `spring-boot-data-jpa-test` |

Affected files (test code only — main code was not affected):

- `spring-boot-integration/runtime/src/test/.../JpaSpringDataUtilTest.java`
- `spring-boot-integration/runtime/src/test/.../MongoDbSpringDataUtilTest.java`
- `spring-boot-integration/integration-tests/main-integration-test/src/test/.../AdapterConfigurationTest.java`

### New dependency required

Test slices are no longer part of `spring-boot-starter-test`. For `@DataJpaTest` the
artifact `org.springframework.boot:spring-boot-data-jpa-test` (scope `test`) was added
to `spring-boot-integration/runtime/pom.xml`. (`@DataMongoTest` would analogously
require `spring-boot-data-mongodb-test` — not needed so far.)

### Not affected

- `SslAutoConfiguration` stayed in `spring-boot-autoconfigure`.
- Core auto-configuration mechanics (`@AutoConfiguration`, `@ConditionalOnClass`,
  `AutoConfiguration.imports`, `EnvironmentPostProcessor` via `spring.factories`) —
  unchanged, no code changes needed.
- Bean-definition registration (`BeanDefinitionRegistryPostProcessor`,
  `ResolvableType`-based generic `ProcessService` beans) — unchanged.
- JPA/Hibernate usage (`PersistenceUnitUtil`, `Hibernate.unproxy`) under Hibernate 7 —
  unchanged.
- Spring Boot 4.1.0 manages JUnit Jupiter **6.0.x** and Mockito 5.23; tests kept
  running without changes (root `mockito.version` is upgraded separately).

## The older versions a BPMS still holds (2026-08-15)

**Adapters** gain two optional questions and one report, all of them additive:

- `ProcessVersionCatalog#tasksOfVersion(workflowModuleId, bpmnProcessId, version)` reads the
  model of a version the BPMS still holds and returns its tasks; `null` means "this BPMS
  cannot say", which switches the check off for that adapter.
- `ProcessVersionCatalog#activeInstanceCountOf(...)` counts the workflows still running on a
  version; `null` again means "cannot say".
- `WorkflowTaskInvoker#registerDeployedVersion(adapterId, workflowModuleId, bpmnProcessId,
  version)` reports the version the BPMS assigned to the model deployed by this boot. Report
  it even when the BPMS deployed nothing because the resources were unchanged, otherwise the
  startup check runs only on boots which changed a model.

An adapter implementing none of them keeps working exactly as before.

**Applications** gain `vanillabp.adapters.<id>.outfaded-versions` (a list, in the grammar of
the `version` attribute) and `vanillabp.adapters.<id>.outfaded-versions-in-use` (`LOG` by
default, `FAIL` to make workflows on a faded-out version stop the boot). Both are
adapter-scoped and resolvable per workflow module and workflow.

**Behaviour which changed without a property:** a `@WorkflowTask` or `@WorkflowStartedByBpms`
method whose version range excludes the version this boot deployed no longer has to match a
task respectively a start event of the deployed model. It is reported as a warning if it
matches no version the BPMS holds at all.

## Two writers on one workflow aggregate (2026-08-15)

**Platform integrations** implement one new method of the core's `TransactionRunner`:
`isConcurrentModification(Throwable)` answers whether a failure is an optimistic locking
conflict. Spring checks for `OptimisticLockingFailureException` (JPA as well as MongoDB),
Quarkus walks the chain of causes for `jakarta.persistence.OptimisticLockException`, which
JTA delivers wrapped in a `RollbackException`. The method has a default returning `false`,
so a platform integration or test double which does not implement it keeps compiling and
simply stays silent.

**Adapters** gain one optional report:
`WorkflowTaskInvoker#reportConcurrentTokenElements(workflowModuleId, bpmnProcessId,
elementIds)`, called during `wireBpmn` with the elements which can put a second token into a
running workflow - a non-interrupting boundary event, a parallel or inclusive gateway
forking into several flows, a parallel multi-instance activity, a non-interrupting event
subprocess. Element IDs, not a boolean: the message names what made VanillaBP say this. An
adapter which cannot read models reports nothing, and the check stays silent then.

**Applications** see two new messages and no new property:

- a WARN per BPMN process whose model can produce concurrent tokens while its workflow
  aggregate has no version attribute, naming the elements and the four ways out;
- an ERROR when the commit of a transaction VanillaBP owns (a `@WorkflowTask` method, a
  workflow the BPMS started on its own, the `@WorkflowEnded` notification) fails on a version
  conflict.

**There is no retry, deliberately.** The exception is passed on unchanged, so the BPMS
applies its retry semantics and ends in an incident. A retry inside the framework would
repeat whatever the handler did before the commit failed - a call to a remote API, for
instance - while hiding that anything went wrong. Adding a retry property later is easy;
taking a silent retry away from applications which grew used to it is not. The rule, the four
ways an application can avoid the collision and its own duty on the other side of it are
described by the wiki page
[Workflow aggregates](https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-aggregates#two-writers-on-one-aggregate).
