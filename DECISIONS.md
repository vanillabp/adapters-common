# Decision log

Decisions this repository's code points at. A number is handed out once and never reused or
renumbered, so a citation stays resolvable; a decision which gets overturned keeps its entry,
marked as superseded and naming the entry which replaced it.

A citation in code reads `see decision 7 in the repository's DECISIONS.md`, and it names an entry
of THIS repository only. A decision which spans the platform and an adapter gets an entry in each
affected repository, each written from that repository's side, because a pointer into another
repository is the fragile kind this log exists to avoid.

An entry says what was decided, why, and what that means for the code. Anything longer is
documentation and belongs in the [wiki](https://github.com/vanillabp/adapter-platform-integration/wiki),
which an entry may link.

### 1. A class opens its fields one by one, not as a whole

The process service, the delivery store and the handlers of the core hold dozens of fields,
most of them collaborators nobody outside the class needs. Which of them a caller may read
belongs to the surface of the class, so an accessor is declared per field, and `@Getter` on the
class is refused even where an IDE offers it: it would publish the current field list and then
keep publishing whatever field a later change adds.
`@SuppressWarnings("LombokGetterMayBeUsed")` on such a class is what keeps that offer from
coming back.

### 2. A workflow is progressed after the caller's transaction committed

Every operation which moves a workflow forward is planned inside the transaction the application
called from and executed after that transaction committed, through the phase-two outbox. The
reason is that the application's write and the BPMS command cannot be committed together: a
remote BPMS has no transaction to join, and even an embedded engine cannot repeat a command which
lost a concurrency conflict inside the caller's transaction, because the conflict leaves that
transaction rollback-only. Advancing the process while the application rolls back is the failure
this avoids; a workflow which the application believes it started and which the BPMS never saw is
the other.

The outbox dispatches at-least-once, so everything phase two does has to be repeatable. Where an
operation can be identified, `PhaseOperation` gives it an idempotency key, and where it cannot
(a broadcast signal has nothing to deduplicate on, and `AGGREGATE_CHANGED` reads its values at
dispatch time) the entry carries none and the residual duplicate is documented rather than hidden.
A test which called VanillaBP has to wait for the BPMS to catch up instead of reading its state in
the next line.

What such a key answers is decided by entry 22, which supersedes this paragraph in that one
respect: a key deduplicates an operation which is still planned, not one which already reached the
BPMS. Everything else here stands.

### 3. Phase one asks, phase two acts

The part of an operation which runs in the caller's transaction only ASKS: does the parked task
still exist, is a subscription waiting for this message, is such a message declared in a deployed
model. It never advances anything. That keeps a guiding error synchronous, thrown where the
application made the call and where a stack trace still points at business code, while everything
which changes the BPMS waits for the commit and is retried by the outbox.

An adapter answers phase one as exactly as its BPMS allows. An embedded engine answers from the
caller's transaction for free; a remote BPMS answers what its API can answer without a round trip
which would be wrong anyway, and says in its own README where it stays silent.

### 4. An adapter answers the election only for its own scope

Locating the BPMS which holds a workflow is a walk over the prioritized adapters, and the walk
stops at the first `ACTIVE`. It is therefore exactly as right as the answers it gets, which is why
the duty sits with the adapter and is written down in the election contract of
`MigratableProcessService`: an adapter answers `ACTIVE` only for a workflow deployed under ITS
scope, and `UNKNOWN_TO_BPMS` for everything else. Neither a task key nor an aggregate ID is proof,
because two adapter ids may address one backend (that is the migration from tenants to prefixes)
and two workflow modules of one backend may carry the same aggregate ID. `WorkflowScope` is what
the core hands the probes so they can tell.

`BPMS_UNAVAILABLE` never falls through to the next adapter: the unavailable BPMS is the one most
likely to hold the workflow, so the probe is retried briefly and then fails by name. There is no
fallback in this walk at all, because a fallback means operating on a workflow which belongs to
somebody else.

### 5. What the election remembered is a hint, never an answer

`WorkflowAdapterCache` shortcuts the walk with the adapter which answered last time, and the same
association is written down whenever VanillaBP knows it for certain: when a start is scheduled,
after phase two, and on every inbound delivery. A hit is still probed, never trusted, and a stale
hit repairs itself by falling through to the full walk.

The hint also turns the meaning of an unknown answer around. An adapter which SHOULD hold the
workflow and does not report it is a reason to wait out that adapter's
`workflowVisibilityDelay` and look again, because an eventually consistent BPMS needs a moment
after the start. The same answer without a hint is a workflow nobody ever heard of, and fails
immediately.

A hint sits under the BPMN process id of the process which was running when VanillaBP learned the
answer, and that is not always the id the application addresses. A task of a secondary process is
delivered to the instance of THAT process, while every `ProcessService` call reaches the primary
one. The write stays where it is, because it names the process which really ran and because nothing
then has to be migrated. The READ asks for every id the workflow service serves, the reading
instance's own id first. All of those processes belong to one workflow aggregate, so a hint written
under any of them says something about the same workflow, and the own id goes first because a walk
which just elected wrote its answer there. A hint which turns out to be stale is dropped, and one
whose workflow ended is marked, under the id it was read from: writing that under the own id would
leave the entry which will be read again next time saying the old thing.

### 6. A processed delivery is written down, and a redelivery is answered from the record

A BPMS which delivers at-least-once will deliver a task twice, and the second delivery must not
run the `@WorkflowTask` method again. The record is written in the SAME transaction as the change
to the aggregate, so a handler which rolls back leaves no record and runs again on the next
delivery, which is the behaviour a rollback promises. The record carries the outcome including a
BPMN error code, so the redelivery can be answered without the handler.

Two timestamps, not one: `RECORDED_AT` is the moment the handler ran and is what the age of an open
task is measured from, `LAST_SEEN_AT` is refreshed by every redelivery of a still open task and is
what the retention deletes by. Without the second one the record of a task which the BPMS keeps
delivering would expire under the retention while the task is still open, and the next delivery
would run the handler a second time.

### 7. An adapter setting is resolved from the most specific place which sets it

Anything an adapter can be told about a single task is resolvable at four levels, task before
workflow before workflow module before adapter, and the most specific value wins. That is one
resolution rule in `MigrationAdapterProperties.resolveForAdapter`, not one per property, which is
why a new adapter-specific key costs a key and nothing else.

Precedence between levels is not the same question as precedence between sources. A workflow
module's own configuration file supplies DEFAULTS which the application always outranks, while a
more specific KEY still beats a less specific one no matter which file either came from.

### 8. What a convention can derive is not configured, and what is wrong is said at startup

An application configures what deviates from the convention. The adapter section of the single
adapter type on the classpath, the workflow module sections, and the location its BPMN files are
read from are all derived in `MigrationAdapterProperties.normalize(ClasspathFacts)` before
validation runs, so a zero-configuration application boots.

Everything which is nevertheless wrong is reported when the application boots, never first when a
workflow runs, and every message names the property key the reader would write, with the values it
found. An unconfigured application still starts and is led to a working setup by its own log
rather than by the documentation.

### 9. Identifiers are scoped at the BPMS boundary and nowhere else

A workflow module keeps its identifiers apart from those of other modules either by a namespace of
the BPMS or by a prefix, and which of the two is the module's configuration. So the registries of
the core, the `ProcessService`, the BPMN files and the configuration all stay keyed by the PLAIN
identifiers, only the call into the BPMS carries the scoped ones, and everything coming back is
translated before the core sees it. `NameClashAvoidanceService` is the one place which resolves
the mode and builds the scoped form.

No code may assume either shape, and an adapter which cannot separate modules at all says so at
deployment time instead of deploying two modules on top of each other.

### 10. The sync model decides what leaves for the BPMS, and only that

`@SyncWithBPMS` and `@NoSyncWithBPMS` on an aggregate class, on an attribute or on a nested type
decide which of its values a command carries, and `AggregateSyncSupport` is the single
implementation of that model, including the inheritance along that chain and the validation which
runs at startup. Nothing else is added: a correlated message carries no content of its own, and a
value excluded from the model stays out of every command.

The reason the model exists at all is portability. A value which a BPMS expression reads has to be
in the payload on a remote BPMS, so an application which relies on the engine reading its aggregate
live works on one BPMS and silently takes the wrong branch on the next.

Beside the shared values travels the variable named after the aggregate's ID attribute, and that
one is written no matter what the model says, because on a BPMS without a business key it is the
only way back from a process instance to the workflow.

### 11. When VanillaBP calls the application, a transaction for that aggregate is open

Loading an aggregate, running a handler and saving it are three calls which the application's
persistence cannot bracket by itself, and the outbox and the delivery log demand to be enlisted in
the same unit of work as the aggregate. So VanillaBP opens one, and it opens the RIGHT one:
`TransactionRunnerResolver` resolves per aggregate, taking the most specific
`TransactionRunnerAware` bean, then a `TransactionRunner` bean of the application, then the
platform default. A tie between two equally specific beans ends the start with both bean names.

Because it is resolved per aggregate, an application whose storage brings its own transactions can
supply one, which is what makes a deployment without a relational database possible. Whether a
transaction is running is asked of that same runner, never of the platform, or an application with
its own storage would be refused a `startWorkflow` it can perfectly well do.

### 12. A failure of phase two is classified by the adapter and judged by the core

Only the adapter can read its BPMS's errors, and only the core knows what to do with the verdict.
So `MigratableProcessService.isPhaseTwoFailureRepeatable` answers one question, defaulting to
repeatable, and `MigrationProcessService.runPhaseTwo` turns a `false` into a
`PhaseTwoPermanentFailure` which every store blocks the entry on immediately instead of retrying
it as often as `vanillabp.outbox.block-after-attempts` allows, fifty times by default. The same cut
runs through the concurrent-token check and the transaction annotations: the adapter or the platform
integration reports facts, the core decides.

Repeating a failure which will never succeed ends in a blocked entry either way, and every attempt
on the way there writes a log line nobody learns anything from, so the classification errs towards
repeatable and each adapter's README lists what it calls permanent and why.

### 13. A delivery whose version the BPMS did not report is served only by an unrestricted method

*Superseded by decision 20: a range may now reach a method from the `@BpmnProcess` of its class, and this entry names the method annotations only.*

`@WorkflowTask(version = ...)` and its two siblings match against the version of the DEPLOYED
process definition as the BPMS counts it, or against a version tag of the model. When a delivery
arrives without a version, only a method without a version range serves it, and if every method of
that task names a version the delivery fails with a guiding message.

The rule replaced an assurance that the first registered method wins, which rested on the order
`Class.getDeclaredMethods()` happens to return and which the language does not define. Version
ranges of one task must not overlap either, which is checked at startup, so a delivery is never
served by an arbitrary one of two candidates.

### 14. Two writers on one aggregate are made visible, not resolved

As soon as a process holds more than one token, two branches write the same aggregate, and a
persistence layer which writes the whole record lets the later commit undo what the earlier one
wrote. VanillaBP does not resolve that. It reports the version conflict with workflow module,
process, aggregate and task, and propagates the exception unchanged so the BPMS runs its own
retries.

There is deliberately no retry of our own. A handler may have called a remote API before its commit
failed, so repeating it silently would repeat that call and hide the conflict at the same time.
What VanillaBP does instead is warn once per process when the model can produce a second token and
the aggregate class has no version attribute, because there the conflict would not even be
detected.

### 15. The check of the versions a BPMS still holds is driven by the core

A BPMS keeps every version of a process it ever deployed, and instances keep running on the old
ones. Whether the application still serves them is a judgement with a message text, a policy and an
outfading configuration attached, so it lives in the core; an adapter only answers two questions on
`ProcessVersionCatalog` and reports through `registerDeployedVersion` which version THIS boot
deployed, which is the border between the application's own model and the older ones.

Building the same judgement in every adapter would give every BPMS its own wording and its own
gaps.

Which process ids are asked about is the core's question as well. A workflow module may declare a
process id no BPMN file of this boot carries any more, which is how a renamed process keeps being
served, and an adapter cannot arrive at that id on its own: it comes from the annotations of the
application, which only the core reads, and the models of this deployment name themselves and not
the id somebody renamed away from. What the BPMS still holds under such an id an adapter can read
perfectly well once it has been told the id. So the core asks every adapter of the module for the
catalog of such a declared-only id through `processVersionCatalogOf`, whose default answer is
nothing, and an adapter whose BPMS cannot be searched by process id stays as it is.

That is also why a `deployedVersion` of null carries two meanings now. Where the module deployed
the process, no version reported by the BPMS means there is no older one to speak of; where the id
was only declared, every version the BPMS holds under it is an older one, and the ordinary reports
run over all of them. Only the core can tell the two apart, because only it knows what the
application declared.

### 16. The two tables VanillaBP owns come from one schema artifact

The phase-two outbox and the task delivery log are ours, so `vanillabp-schema` ships one
database-neutral Liquibase changelog for them plus the SQL generated from it per database, and the
runtime DDL creates the same columns. Nothing else is shipped: gruelbox's table belongs to
gruelbox and the engine tables belong to the engine, both documented rather than copied.

Where the application creates the schema itself, both stores check at startup that their table AND
its columns exist, and name the table, the property and the artifact to apply. Checking only the
table is what let a later column slip through once.

### 17. An adapter id is an identity and is never renamed while anything is still open

Every persisted record which belongs to a workflow carries the adapter id it was written for: the
outbox entry so a pending call reaches the BPMS it was planned for, the delivery record so a
redelivery is recognised as the same delivery. Renaming an id therefore orphans them, and the
symptom shows up much later as a workflow which was persisted and never started, or as a handler
which runs a second time.

So the ids the stores still hold open work for are asked once at startup and compared with the
configured ones, and a mismatch warns with both readings and with the property which acknowledges
it. A warning, not a failed boot, because the entries are waiting rather than lost. A store which
cannot answer says so by returning nothing and the check stays quiet.

### 18. Reading a metric costs no more than reading a number

A gauge is read on every scrape, in every instance, so a gauge which counts rows in a table turns
monitoring into load. Every measurement which is not already in memory goes through
`CachedGaugeValue`, whose window is `vanillabp.metrics.gauge-cache`, and the wrapping happens once
in `MicrometerVanillaBpMetrics` so no store can forget it.

A store which cannot answer at all leaves a gap rather than reporting zero, because zero pending
calls is a statement somebody will act on. The same reasoning fixes the naming: the prefix is
`vanillabp.`, the place is a TAG and never part of the name, and nothing which is unique per
workflow ever becomes a tag, or every workflow would get its own time series.

### 19. A start asks for numbers, and asks as many questions on the last day as on the first

Booting an application puts questions to the BPMS and to the two tables VanillaBP owns: which
versions the BPMS holds, how many workflows still run on an older one, how many tasks it is
holding open, which adapter ids the persisted state still names. Every one of them is answered
from data which keeps growing for as long as the application is in production, so every one of
them can turn a ten-second start into a two-minute one after two years, and nobody sees it coming.

The first half of the rule is that a start asks for a number, or for the existence of one row, and
never for the rows themselves. `COUNT(*)`, `DISTINCT`, a search which reads its `totalItems`, a
statement carrying a row limit: the reducing is the database's work or the cluster's. Reading the
first row of an unlimited result set is not that, however much it looks like it in the code, because
a JDBC driver decides for itself how much it transfers before `next()` answers and PostgreSQL's
reads everything.

The second half is that how many questions get asked belongs to the shape of the application rather
than to its history. Once per workflow module, per BPMN process, per adapter and per version a BPMS
holds is fine, since those numbers change when somebody deploys, and `outfaded-versions` is what an
operator bounds the last of them with. Once per running workflow, per open task or per record is
not, because nobody deploys to make that number grow.

Where a question cannot be asked that way it is switched off or made conditional on something an
operator understands, never on a time limit. A check which sometimes runs is worse than no check,
because its silence stops meaning anything.

The guard counts the questions instead of measuring the duration, which would only flicker on a
build runner. `StartupQuestionCostTest`, `OldProcessVersionsTest` and `PersistedAdapterIdTest` ask
the same start twice, once against a fresh installation and once against years of history, and
compare what was asked.

### 20. A version range belongs to a method or to a whole workflow service class

`@BpmnProcess(version = ...)` is the fallback of `@WorkflowTask`, `@WorkflowStartedByBpms` and
`@WorkflowEnded`. A method naming no range serves the range of the `@BpmnProcess` its process was
declared with; a method naming one keeps it word by word. This replaces decision 13, which promised
the same thing about the method annotations alone.

An application which brings two generations of a model had to repeat the range on every single
method, which is one statement written many times, and a method added later without the attribute
silently served every version. What a team wants there is one handler class per generation, and the
attribute which says that has sat on `@BpmnProcess` since version 1 with nothing reading it.

The method wins and the two ranges are not intersected. With an intersection, `version = "5-7"`
would mean different things depending on a declaration elsewhere in the file, and a range which
cannot be read off the annotation in front of you is worse than one repeated. Which declaration
applies is decided by the PROCESS a delivery came from, not by the class: a class declares one
`bpmnProcess` plus any number of `secondaryBpmnProcesses`, each with a version of its own, and one
method may serve elements of both. So the range is resolved per (class, BPMN process), which is how
handler methods are registered anyway.

A method which inherits a range is restricted, so it does not serve a delivery whose version the
BPMS did not report, exactly like a method naming a range itself. That is the consistent reading and
also the surprising one, since the method carries no attribute at all, which is why every message
about such a range names the declaration it came from. A complaint about something the reader cannot
see next to the method reads as a defect of VanillaBP.

Two classes for one process are the point of the feature and boot as long as their ranges are
disjoint; overlapping ones end the start naming both classes. The ranges compared are the EFFECTIVE
ones. Untouched by all of this: one `ProcessService` per workflow aggregate. Which process
`startWorkflow` starts is decided by the primary declaration, and different primary processes for
one aggregate remain ambiguous.

### 21. A workflow service is found because it is a bean

Spring Boot discovers the classes annotated by `@WorkflowService` among the bean definitions of
the application, not by scanning the classpath for them. The scan it replaces read every class
resource of every JAR, 42 816 of them in the demo it was measured on, to find a single workflow
service, and it cost 15.9 seconds of a 24.4 second start under `spring-boot:run` and 4.3 of 9.0
from the packaged JAR.

Being a bean is what a workflow service has to be anyway: the handler object of a task delivery is
resolved through the bean factory, so a class without a bean cannot serve a task no matter how it
was found. So the discovery asks nothing of an application it did not already have to bring, and
where the class sits stops mattering, which is the whole reason the scan existed.

The bean definitions are also the only place where the question has a correct answer. Whether a
service belongs to THIS run is decided by the active profile and by every other condition Spring
evaluates while it refreshes, so a class list read from the classpath, or written into an index
while the application was built, answers a different question: what could be a bean in some run.
An index the way the Quarkus integration uses Jandex cannot close that gap either, because Quarkus
decides its bean set while the application is built and Spring has no such closed world.

Scoping the scan instead of dropping it was measured and rejected. Both candidates, the packages
of `AutoConfigurationPackages` and the classpath roots carrying a `META-INF/workflow-module`
marker, lose the workflow services of a common library, which live in a root with neither, and
which the global workflow module picks up today.

The discovery runs as a `BeanDefinitionRegistryPostProcessor` ordered last rather than as an
imported `BeanRegistrar`, because a registrar runs while the configuration classes are being read
and would see only the definitions registered up to that point. A library's auto-configuration
contributes later, and that is the case this exists for.

A class carrying the annotation without being a bean is passed over without a word. It cannot be
told apart from a class another profile brings, and the application which really lost its handlers
is told so further down, by the report the deployment writes about the BPMN processes no workflow
service of this run claims. It names the process, what a workflow of it costs (it can be started
and gets no further than its first task) and the two ways out, writing the workflow service or
taking the process out of the file. What this gives up is the loudest symptom a forgotten workflow
service used to have: it does not end the boot any more, and whoever does not read the startup log
does not learn about it.

### 22. An idempotency key says an operation is planned once, not that it ever happened

The key of a phase-two operation deduplicates against the entries which have not been dispatched
yet. What it protects is the window entry 2 opened: between the application's commit and the BPMS
command, where a crash has to leave the operation repeatable and a redelivery must not run it
twice. It stops answering a question nobody asked it, whether this operation ever happened before.

Before this, a dispatched entry kept blocking its key until the retention deleted it, seven days
by default. A second, entirely legitimate operation of the same key inside that window was
dropped: an offer round asking partner 42 again in the second round correlates the same message
name with the same correlation id for the same aggregate, and the workflow then waits for a message
which was never sent. The retention was never the screw to turn - shortening it makes the
collision rarer, not impossible - so the window ends where the reason for it ends, at the dispatch.

The stores carry that in the column or field the unique constraint spans: it holds the idempotency
key while the entry waits and the entry's own ID once the entry was dispatched, so the constraint
covers exactly the operations which are still planned and the derived key stays readable next to
it for whoever reads the table during support. It is never null, because not every database treats
two nulls as different values. gruelbox owns its table and has no such column, so that store
releases a dispatched entry when it meets one: the row has done its work and is deleted, in the
caller's transaction, which costs its trail and is the price of not owning the table.

The at-least-once guarantee moves nowhere: a redispatch reads the very entry which is not done
yet, so it is the store's own bookkeeping which carries it - gruelbox' attempt count, the
`STATUS`/`ATTEMPTS` columns of the stores VanillaBP wrote itself - and never the key. Which is why
the key is bounded to the smallest limit of the four stores, 250 characters, and hashed beyond it:
gruelbox refuses a longer unique request ID before any database sees it, and an aggregate ID a
domain model legitimately uses reaches that length.

A key names the operation it deduplicates, because completing a task and cancelling it are two
pieces of work on one task ID. The one deliberate exception is a workflow start by message, which
carries the plain start's key: a workflow is started at most once per aggregate, whichever of the
two started it.

What this does not fix is two operations planned in the same batch of work. Multi-instance
siblings of one aggregate share workflow module, BPMN process and aggregate ID - a called process
is a secondary workflow of the SAME aggregate - so three elements of a multi-instance call
activity are told apart by their correlation id alone, and business data does not have to differ.
All three are pending at the same moment, the first one wins, and the others are discarded. Telling
a sibling from a redelivery needs to know which activation asked, which nothing on the outbound
side reports today. So the discard is made audible instead of silent: the store logs the technical
half at DEBUG, and the core turns the outbox' answer into a WARN naming what was dropped and both
causes it cannot tell apart. The remedy the message asks for is the one which exists: vary the
correlation id per round or element.

Which activation planned a key is decided by entry 23, which supersedes this paragraph in that one
respect: siblings are told apart now, and the remedy above is what remains for a caller repeating
itself within one activation or outside any. Everything else here stands.

On Camunda 8 the cluster deduplicates as well, by the `messageId` the adapter derives from the same
values, for as long as the message time-to-live lasts. That net is the cluster's, it is longer than
this one, and no VanillaBP setting shortens it - which is why the adapter's message says so instead
of calling the refusal a redelivery.

### 23. A key names the activation which planned it

The idempotency key of a message correlation carries the activation of the BPMN element the
correlation was planned in, where there is one. It is the only key which does, and the reason is
that it is the only one which has to deduplicate PER activation.

What entry 22 left open was two operations planned in the same batch of work. A called process is a
secondary workflow of the SAME aggregate, so three elements of a multi-instance call activity agree
in workflow module, BPMN process and aggregate id, and a correlation id read from business data does
not have to differ either. All three were pending at the same moment, the first one won, and the
other two were dropped. Telling a sibling from a redelivery needs to know which activation asked,
and the BPMS knows: it is running one element instance per element.

So the adapter reports it, as the identity of the ACTIVATION rather than of the delivery. The two
contracts are opposite and were confused because one BPMS answers one value for both: a delivery
identity has to stay EQUAL while the BPMS repeats itself, so a redelivery can be answered from its
record, while an activation identity has to DIFFER between two activations of one element and says
nothing about redeliveries. Camunda 7 is the proof that they are two questions - it reports no
delivery id at all, because it delivers inside its own transaction, and still knows which activity
instance is executing.

The core reads it from the thread it invoked the handler on, so the application passes nothing and
its own signatures stay as they are. A task delivery opens that scope and so does a workflow the
BPMS started; the end of a workflow does not, because a workflow ends once. A handler which hands
work to a thread of its own sees no activation and gets the key every VanillaBP application had
before - absent rather than failing, so nothing which works today breaks on the upgrade.

The other keys deliberately do NOT carry it. A workflow is started at most once per aggregate,
whichever activation asks; a task is completed at most once, and its task id already names one
activation of one element; a broadcast signal and a correlation without a correlation id carry no
key at all, and giving them one would start deduplicating what is deliberately not deduplicated.

What this does not fix is a correlation planned outside any activation. A REST endpoint correlating
the same message name with the same correlation id twice for one aggregate is indistinguishable
from a repeat of itself, and the narrowed window of entry 22 stays the only answer there. The
warning says which of the two cases it is looking at, because the remedy differs.

### 24. What a number decides is what decides whether it gets a property of its own

The retention of a dispatched outbox entry and the retention of a task-delivery record are two
properties, `vanillabp.outbox.retention` and `vanillabp.delivery.retention`, because they answer two
different kinds of question. One is operational: how long can support still read what was
dispatched. The other is correctness: does a redelivery arriving later than this run the
`@WorkflowTask` method a second time. Nobody weighing disk space against a support trail should be
weighing a business method running twice at the same time.

They were one property, and rightly so, for as long as both governed a deduplication window. Entry
22 ended that: the outbound window ends with the dispatch, so on that side the number stopped
deciding anything a workflow depends on, while on the inbound side it went on being the only thing
between a late redelivery and business code running again. An installation shortening the number to
keep its outbox table small was shortening a correctness window with the same hand, and had no way
of seeing it.

The new property FOLLOWS the old one where it is not set. That keeps every installation on the
behaviour it had, including the ones which lowered the old number deliberately, and it means an
application which never cared about either notices nothing. Where exactly one of the two is moved
away from the default, the startup says which window applies to what - the trigger is "differs from
the default" rather than "was written down", because a bound property cannot tell those apart and
the case worth a message is the one where somebody moved a number.

It is read GLOBALLY, unlike the settings next to it in the same section. What deletes the records is
one cleanup per store, constructed with one period and deleting by age across the whole table
respectively collection, so a value per workflow module would have to be honored by a different
deletion in each of the four stores VanillaBP ships. A property which is bound per module and
silently ignored there is worse than not having one.

**And there is deliberately no check comparing it against what a BPMS can redeliver within.** That
was the alternative to splitting, and it fails on what an adapter can truthfully answer: it knows
the INTERVAL at which it hands unacknowledged work out again - the Camunda 8
`async-task-lock-renewal`, an hour by default - and not the horizon within which the last such
handout falls. The horizon is set by how long the application is stopped, because a stopped
application refreshes no record and the first cleanup run after it starts deletes what expired
meanwhile, and by whoever gets around to resolving an incident. A check against an interval of
minutes, guarding a risk measured in days, would be green in exactly the installations about to run
business code twice, and a green message which means nothing teaches its reader to ignore the next
one.

### 25. A workflow is located by asking, not by a registry

Every operation on an existing workflow asks the configured adapters which of them holds it,
and the answer is not written down anywhere persistent. A registry mapping workflow module,
BPMN process and aggregate id to an adapter id was considered and rejected: it would be a
second source of truth about something the BPMS already knows, it would need a schema, a
cleanup and a repair path of its own, and it would be wrong exactly when it matters, after a
crash or a migration.

What that costs is one question per operation, which is a query against a remote BPMS and
nothing at all against an embedded one, plus the walk over the adapters until one answers.
The only accelerator is `WorkflowAdapterCache`, whose entries are hints and never answers
(entry 5), so a lost hint costs a walk and never a wrong route.

The workload this is sized for is the one VanillaBP is built for, and that is a product
decision rather than a limit somebody forgot to lift: business processes whose steps are
minutes and days apart, implemented in high quality and as cheaply as possible. An
application which really moves thousands of operations per second has to be optimised
anyway, and a project of that kind carries the budget to build what it needs; VanillaBP
buying that case with complexity everybody else pays for would be the wrong trade. So the
probe per operation stays, and an application in that other shape gets a design of its own
rather than a registry bolted on here.

Where the cost does show up first is a cluster of application nodes, and the lever there is
the cache rather than the design: a shared `WorkflowAdapterCache` (Redis, Hazelcast, whatever
the application already runs) is written once when the workflow starts and read by every
node, so the BPMS is asked once per workflow instead of once per node. That is the
recommended answer to "the election is our bottleneck", and it is the reason the cache SPI
is part of the integration SPI rather than an internal class.

Two consequences the code carries visibly. The re-dispatch of a workflow start probes
`awarenessOfWorkflowForRedispatch` instead of consulting a record, which is why that probe
must never answer optimistically, and the residual duplicate window after a crash is
documented per adapter rather than closed. And an adapter which cannot be asked at all
answers optimistically, which is safe while it is the only BPMS configured and is the reason
a migration setup containing such an adapter routes by list order (see the wiki page
"BPMS migration").

### 26. There is no switch which lets an adapter act in phase one

`MigratableProcessService` used to ask every adapter whether it needs a two-phase commit for
starting workflows, and the core skipped the outbox for six operations where the answer was
`false`: the start, the start by message, a task completion, a cancellation, a correlated
message, a broadcast signal and a pushed aggregate. The method was named after starts, gated
much more than starts, and described a variant which acts in phase one and leaves phase two
empty. No adapter ever answered `false`, Camunda 7 deliberately so although it runs embedded
(decision 2 of that adapter's log: a command which loses a concurrency conflict cannot be
repeated inside the caller's transaction, because the conflict leaves that transaction
rollback-only). What the switch really offered was a way to build an adapter the core does
not run, and the next adapter is written by a team we are not sitting next to.

So the method is gone rather than renamed, and entry 3 holds without exception: phase one
asks, phase two acts, for every adapter and every operation. Two things follow from it which
were conditional before and are unconditional now. Every application needs a resolvable
`PhaseTwoOutbox`, and needs it at startup, because there is no adapter left which sends
nothing through it. And every application needs a transaction VanillaBP can run its work in,
because the aggregate and the outbox entry are written together or not at all.

The embedded fast path is not forbidden forever; it is simply not part of this SPI. Bringing
it back is a new decision here, with the core change which makes the core skip the outbox in
exactly the cases the adapter names, and it will not come back as a sentence in a javadoc
with no code behind it.

What the removal does NOT touch is the inbound direction. A BPMS which delivers a task inside
its own transaction still does, and `TaskInvocationContext.runInCurrentTransaction()` is where
an adapter says so.

### 27. Phase one asks once, and the waiting happens where no transaction is open

Locating the BPMS which holds a workflow is a question per operation (entry 25), and until now
that question was allowed to take its time wherever it was asked: an unreachable BPMS was retried
twice half a second apart, and an adapter which a hint said should hold the workflow was asked
again until its `workflowVisibilityDelay` was used up, ten seconds on Camunda 8. In phase one that
runs inside the transaction the application called from, which holds a database connection and
the locks on the workflow aggregate. One lagging exporter is then enough to park every caller in
the connection pool, and an application whose BPMS is slow stops being able to do anything at all,
including the work which has nothing to do with that BPMS.

So the walk asks how patient it may be. Phase one asks every adapter once and never sleeps. The
dispatch of a phase-two entry may do both, because no application transaction is open there and a
repetition costs an entry another attempt rather than a connection.

Three goals decide what happens to the answers, and they are Stephan's, written down here because
they are the reason the table below looks like it does:

1. a workflow NO BPMS knows has to raise `WorkflowNotFoundException` out of the `ProcessService`
   method the application called;
2. the one to three seconds Camunda 8's secondary storage lags behind in normal operation must not
   produce an error anywhere, while an exporter which stopped (a full Elasticsearch, say) should
   end up in a Camunda incident;
3. a BPMS which is not available right now produces an exception - and asking it is the only
   honest way to find out.

|                  The probe answers                  |                phase one does                |              the dispatch does               |                      a read does                       |
|-----------------------------------------------------|----------------------------------------------|----------------------------------------------|--------------------------------------------------------|
| `ACTIVE`                                            | the operation runs                           | the operation runs                           | the adapter answers                                    |
| `COMPLETED`                                         | warned no-op                                 | the entry is consumed                        | the adapter answers (an ended workflow is viewable)    |
| `UNKNOWN_TO_BPMS`, task operation                   | `TaskNotFoundException` at once              | the entry is consumed (the task is gone)     | -                                                      |
| `UNKNOWN_TO_BPMS`, workflow operation, hint present | the operation is planned, the caller returns | waits out the window, then repeats the entry | waits out the window, then `WorkflowNotFoundException` |
| `UNKNOWN_TO_BPMS`, no hint                          | `WorkflowNotFoundException` at once          | the entry is consumed (stale)                | `WorkflowNotFoundException` at once                    |
| `BPMS_UNAVAILABLE`                                  | exception naming the adapter, at once        | retried twice, then the entry is repeated    | retried twice, then the exception                      |

The read is the column this decision first forgot, and a red blueprint nightly is what
said so (story 176): the viewer of a workflow started seconds ago asked Camunda 8 while
its exporter was still behind, got the honest "unknown" and raised
`WorkflowNotFoundException` in the application, which goal 2 above forbids. Where a write
operation is planned in phase one and does its waiting in the dispatch, a read has no
second place to go: nothing repeats it, so it either waits itself or hands the caller an
error. It therefore waits like the dispatch does, bounded by a hint and by the adapter's
`workflowVisibilityDelay`. What that costs is the mirror image again: a read carrying a
stale hint answers after the window instead of at once, and the message then names the
adapter which was expected to hold the workflow, because an exporter which stopped looks
exactly like one which is behind.

What makes the hint the dividing line: a hint exists only where VanillaBP knew the answer without
asking anybody - it started the workflow itself, or a delivery for that workflow arrived from that
BPMS. It is therefore evidence that the workflow exists, which turns "unknown" from a wrong id
into a read model running behind. Without a hint and with a unanimous "unknown", nobody has ever
seen this workflow.

A task probe is not a search, which is why it keeps the fast answer. On Camunda 8 `awarenessOfTask`
updates the job's timeout and `awarenessOfUserTask` sends an empty user-task update; both are
engine commands which answer exactly and never touch the exporter. Only `awarenessOfWorkflow` -
message correlation, the aggregate push, the viewer, the re-dispatch probe - searches the
secondary storage, and only that branch needed a new answer.

Where an exporter outage becomes visible depends on what the work hangs on, and that boundary is
part of this decision rather than something to discover later. Work behind a JOB - a
`@WorkflowTask`, an asynchronous task whose completion never arrives - runs out of the job's
retries in the cluster and Camunda raises an incident, which is what goal 2 asks for. Work with no
job behind it, a correlation from a REST endpoint for instance, has nothing which could become an
incident: there the blocked outbox entry and the counter of blocked entries are the place it shows.

Goal 3 cannot be answered without asking. What is available without a question is the adapter's
last known health, and that is stale by construction - "was reachable n seconds ago" is either a
false alarm or a false calm. The probe itself is where unreachability surfaces, and it is one
question per operation, the price entry 25 accepts. Health may enrich the MESSAGE ("this adapter
has been reporting itself unreachable since 12:04"); it does not replace the question.

The residual this leaves is the mirror image of the old one and is named rather than closed: a
workflow operation carrying a stale hint - the workflow ended long ago and is out of the read
model - is planned instead of refused at the call. Its entry is repeated and finally blocked. It
used to be an exception after ten seconds of waiting; it is a blocked entry now, and the more
common case of an ended workflow which the BPMS still knows keeps answering `COMPLETED` and stays
a warned no-op.

### 28. An adapter is registered completely or it does not exist

The collaborators an adapter needs from the platform used to arrive one setter call at a time,
after the constructor had already returned a usable object. A registrar which forgot one produced
an adapter that deployed its BPMN files, ran its tasks and never reported a workflow end, and
nothing anywhere failed: the object was valid, its field was simply null. There was also no place
which said what a complete registration is - the list lived in whoever had written the last
registrar, and the three adapters had drifted apart in exactly the way that invites.

So the platform hands its collaborators over in one object, `AdapterCollaborators`, and an adapter
takes it in its constructor. Five of them are mandatory, because both platform integrations
provide them for every application: the wiring half and the runtime half of the task SPI, the
name-clash scoping, the aggregate sync and the pre-commit registrar. A set built without one of
them throws, naming the adapter id and what is missing, and says that no application can configure
this away - it is the registration code.

Two are handed over as `Optional`: the invoker for workflows which ended, and the one for
workflows the BPMS started by itself. An adapter has to work without them, because an application
which never asks for either has nothing to report to. Both platforms do provide them today,
though, out of the same core bean as the mandatory ones - so an adapter built without one is
nearly always a registration which left it out, and the build writes a WARN naming the adapter id
and the collaborator. That is the second line: the first one is a compile error the day a
registrar is written, the second one is a line in the log of the boot which caused it.

What is NOT in the object: what an adapter resolves from its own configuration (a job timeout, a
retry backoff, the variables a worker fetches) and what its own extension contributes (its
metrics). Those are the adapter's own arguments, and the platform has nothing to say about them.

The alternative was a check at `startWorkflowProcessing` asking each adapter which collaborators
had arrived. It is the smaller change and it would have caught the same defect, one boot later.
The parameter object was chosen because it changes every adapter's constructor, and the moment to
do that is before an adapter written outside this repository exists (Stephan, 2026-08-28).

### 29. An operation says everything about itself, an adapter says what it does

One two-phase operation used to be written down five times in the core: a pair of methods on the
adapter SPI, a constant carrying its persisted name and idempotency-key rule, a typed `schedule*`
default building its outbox call, a registration in the router, and an `execute*` body in the
process service which differed from its neighbour in a probe and a log line. An adapter wrote it
twice more. Four of those places knew nothing an operation needed to decide - they repeated what
the operation already was - and the escalation stories waiting in the roadmap would have added a
sixth.

An operation is therefore one `PhaseOperation`: its name, its key rule, the `Election` naming
which BPMS serves it, whether every adapter has to be able to serve it, whether the activation the
call was planned in travels with it, and the words it names itself with in a message a developer
reads. The core reads the probe, the patience of the election and the shape of the failure off the
election; the router dispatches by name; the process service runs everything through one `execute`
and one `executePhaseTwo`. Adding an operation is a constant here and a handler in each adapter
which can serve it, and a test adds one to prove it stays that way.

What an operation does NOT say is what it does, because that is not one answer: it is one per
BPMS. An adapter contributes a `PhaseOperationHandler` per operation - phase one asks, phase two
acts, which is entry 3 - and an operation missing from its map is an operation this BPMS has
nothing like. That is a legitimate answer for an operation which says so (a signal, a push into a
running instance) and a defect for the rest, so the boot refuses an adapter which cannot serve
what every adapter has to serve. The map is the statement and the boot reads it; asking the
adapter's class anything would be reflection, and reflection is a lie in a native image - a method
nobody registered looks like a method nobody wrote, and every adapter of a native application
would be refused. The first attempt did exactly that and the native build of this repository
caught it.

The reason to do it now rather than when the escalation stories arrive: the next adapter is built
by a vendor rather than by this team, so the shape they implement against has to be the one we
want to live with (Stephan, 2026-08-28). A compatibility bridge carried the three adapters this
repository ships across, one pull request each, and went with the pair of methods once they had
moved - so `phaseOperations()` is abstract and is the only way an adapter describes outbound work.

### 30. A task operation is routed by the record the delivery of that task wrote

Entry 25 buys the freedom to migrate a workflow between BPMS with one question per operation:
which adapter holds this? For a task that question was asked twice on Camunda 8 - once by the
election, as `newUpdateTimeoutCommand` against the job, and once again a moment later by the
adapter's own phase one, which sends the same command as its pre-commit check. Two round trips to
a cluster for something VanillaBP had already written down.

Because it had: the delivery record of that task names the adapter which delivered it and says the
task was left open. It lives in the database of the workflow aggregate, so it is read inside the
transaction the caller has open anyway, and every node of a cluster sees it - unlike the election
cache of entry 5, which is why a shared cache is not the answer for this one operation. What the
record could not say was WHICH task it belonged to, so it carries the task id now, and the moment
the application's completion of that task reached the BPMS.

This is not the registry entry 25 rejected, and the difference is the fallback rather than the
wording. Nothing is written for the sake of routing: the record exists, is written by the delivery
itself in the delivery's transaction, and is deleted by a retention which was there before. It
answers only about a task, never about a workflow. What decides whether it may answer is therefore
not how an operation is declared, but whether the call at hand names a task. `aggregateChanged`
addresses a workflow and names a task whenever the application passes a task id, and that call is
elected from the record like any other, while the overload without one walks the adapters as it
always did. A closed record is narrower still: it turns into the warned no-op only for the
operations which end the task it names, because a push writes into a scope that outlives the task
and the workflow may well run on. And where it says nothing - no store,
`deduplicate-deliveries` switched off, the retention passed, an upgrade whose open tasks predate
it, Camunda 7 which reports no delivery at all because it delivers in the application's
transaction - the walk runs exactly as it did. A registry which is wrong routes wrongly; this one
is either right or silent.

The moment a task was closed is written after phase two succeeded, on the dispatching thread, and
not when the caller asked. Between the two the task is still open for the BPMS, and on Camunda 8 it
is this very record which answers the redeliveries that renew the job's lock - marking it earlier
would let that lock expire. A second call inside that window is refused by the outbox' idempotency
key already (entry 22), and that key is free again once the entry was dispatched: from there on the
record is what makes a repeated completion the warned no-op it always was.

The record sits under the BPMN process id of the process which DELIVERED the task, and for a task a
called process handed out that is the secondary id, while the application completes it on the
primary process service. Reading the own id alone found nothing for exactly those tasks: the
operation paid the probe this entry exists to save, and the row `markTaskClosed` should have closed
stayed open, ageing and counting as an open task for good. So the read asks for every BPMN process
id the workflow service serves, the reading instance's own id first, and the ids a rename left
behind belong to that list because they are declared. The write moves nowhere. A record still says
which process handed the task out, which is what the delivery-log store keys on and what makes a
record written by an older version readable without migrating anything.

What this does not touch: phase two elects by probing, as it always did, so a workflow which
changed its BPMS between the call and the dispatch is still found. And the adapters keep their
phase one, so a task which disappeared between the delivery and the call still fails synchronously
where it always failed.

### 31. A cache VanillaBP ships lives in a repository of its own

The election cache is an interface an application implements for a reason which has nothing to do
with its domain: a second node starts with an empty cache, and everything that follows is
infrastructure work. That work is the same in every project, so VanillaBP ships one implementation
of it, for Hazelcast, in
[hazelcast-shared-election-cache](https://github.com/vanillabp/hazelcast-shared-election-cache)
with a release line of its own. The platform integrations stay free of any Hazelcast dependency.

Three things follow, and each of them is the reason for the split rather than a consequence of it.
The SPI stays the contract: an application whose infrastructure is Redis, a table of its own or
anything else is not a second-class case, and nothing about the shipped implementation may make it
one. A cache which needs a new Hazelcast version must not drag the platform's release with it,
which it would as soon as a platform module depended on it. And a member of a Hazelcast cluster is
a component with its own operational story, its own compatibility table and its own failure modes,
which belong next to the code that has them and not in the documentation of the platform.

What stays as it was is entry 25: a shared cache is the answer where the election becomes a
bottleneck, on Redis, Hazelcast or whatever the application already runs. That reasoning does not
change because one such cache now exists ready-made. No entry of this log ever promised that
VanillaBP ships none; the sentences which said so lived in the javadoc of `WorkflowAdapterCache`
and in the texts which followed it, and they now point at the implementation while still saying
that an application with different infrastructure writes its own.

### 32. The workflow service is the class which serves, not the class which declares

`@WorkflowService` is `@Inherited`, and what that means for VanillaBP is decided here, because four
places rely on one answer: the Spring Boot discovery, the Quarkus build step, the scanners of the
core and the reflective registration a native image is given.

The workflow service is the class which SERVES the BPMN process. A class inheriting the annotation
from a superclass is one of those, and the superclass is a declaration rather than a workflow
service of its own. Everything read per workflow service is read on the serving class: the archive
it sits in decides the workflow module, its simple name is the BPMN process id where the annotation
names none, and its handler methods are the ones it offers, the inherited ones among them.

Spring Boot arrived at that reading by itself, because it registers the class of the bean, and
version 1 did the same. Quarkus did not: Jandex reports the class an annotation SITS on and resolves
no `@Inherited`, so the declaration used to become the workflow service, and one source file served
two different BPMN processes depending on the platform it ran on. Quarkus is the platform which
moved, because VanillaBP on Quarkus exists from version 2 onwards, which was unreleased when this
was decided, so no application had been promised the other reading.

A class which is no bean serves nothing, so the workflow services are the beans. Spring Boot reads
that set from the bean definitions (entry 21). Quarkus cannot ask ArC while it is still generating
beans, so the build approximates the set by the classes of the family which are not abstract, and
each of them has to be a CDI bean of its own. A bean of a subclass does not stand in for a class
inheriting the declaration, because that subclass is a different workflow service, serving a process
of its own wherever the id follows the class name. This is the one point where Quarkus asks more
than Spring Boot does, and it is what keeps the set registered and the set required to be beans from
drifting apart. It is loud rather than silent: the build names the class, the declaration it
inherited and the ways out of it.

Where several classes of one workflow aggregate come out of that walk, nothing new happens. Classes
declaring the same process split the handlers of that process, and classes declaring different
primary processes end the build with the message which always ended it. Two subclasses of a base
naming no `bpmnProcess` are that second case, since each of them names its process after itself.

A declaration nobody can serve is reported instead of registered. An abstract class carrying the
annotation with no concrete subclass in the index has no class to hand a task to, and saying so is
better than the bean question the developer used to get about a class they made abstract on purpose.

### 33. The BPMS double is published, and the platform's own tests use the published one

An application which needs a BPMS to boot cannot be tested by anything which has no BPMS, so every
repository next to VanillaBP either starts a real engine or writes a double of its own. VanillaBP
ships that double instead, as `bpms-double` plus one module per platform, because the alternative
was for each such repository to depend on a real adapter and hope its engine starts quietly.

The point is not the artifact, it is which code goes into it. The platform's own tests run against
the published modules rather than against a copy kept in the test tree, and that is the whole
argument for publishing: a double nobody uses is a double nobody notices is broken. A second,
separately maintained one would compile under this repository's CI and would never run a workflow,
so a wrong default or an uncalled path would surface in somebody else's build after a release. The
two doubles this repository used to keep drifted apart while both were under its own CI and both
were run every day, which is what that failure looks like while it is still cheap.

It lives here and not in a repository of its own, which is where entry 31 sent the shared election
cache, because the reasons differ. The cache has a release cycle of its own and must not drag the
platform's; the double has none, since it implements the adapter SPI and follows every change to it
in the same commit. A repository of its own would put a release round trip between an SPI change
and the tests which prove it.

What that costs is a contract. The log lines the double writes, the names of the beans it
registers and the way it turns a filename into a BPMN process id are things a consumer can rely on,
so they are written down in `bpms-double/README.md` and changed deliberately. Everything else about
the double, the class layout included, stays free to move.

### 34. The core names the ids nobody deployed, the adapter decides what to do with them

A workflow module may declare a BPMN process id it deploys nothing under, which is how a renamed
process keeps being served. Only the core knows those ids, because they come from the annotations
of the application and the models of this deployment name themselves rather than the id somebody
renamed away from, and it already hands them over once per boot to ask what the BPMS holds for
them (decision 15). What the BPMS holds under such an id is then readable by an adapter whose BPMS
answers for a process id, which is what the Camunda 7 adapter does with the models its engine
kept.

Serving the workflows running under them is a second duty, and the core does not decide it. What
it takes to reach such a workflow is BPMS knowledge: an identifier which carries no process id
reaches the old id by itself, a Camunda 8 job type under `use-prefix` carries one and needs a
subscription per name, and Camunda 7 needs the models its engine still holds, because the way a
task is wired lives in them and nowhere else. So the core answers two things and stops there.
`taskWiringOfProcessesNobodyDeployed` says WHICH ids a workflow module declares without a model
and WHAT its `@WorkflowTask` methods serve for each of them, and the adapter composes its BPMS'
own identifiers from that answer. Deciding it here would put one engine's notion of a
subscription into the core, which is the mistake version 1 made with the outbox.

The method is named after the question rather than after today's answer, and that is deliberate.
What an application may name its wiring by belongs to the surface of `spi-for-java` and can
widen; what an adapter needs from the core does not change, being the wiring in the form it
composes its own identifiers from. A name saying "task definitions" would have to be changed
with the first such widening, and every citation of it with it. An adapter therefore reads an
entry as "what to compose from" and never assumes it is spelled the way its own BPMS spells an
identifier.

What an entry holds today is the `taskDefinition` of a method, so a method naming a BPMN element
id contributes nothing and an id can be named with an empty collection. That is not a gap in the
answer, it is the honest shape of it: an element is matched through the model, and the model of
that id is the one thing the application does not have. An adapter which composes from task
definitions can therefore say which workflows it will not reach, which is what the Camunda 8
adapter does while it starts.

### 35. What an extension needs from VanillaBP is a contract it describes, not a base class it inherits

The Business-Cockpit adapters of version 1 were not adapters at all: they were extensions built on
the platform integration's internals - `AbstractTaskWiring`, `TaskHandlerBase`,
`AdapterAwareProcessService` and a second `@ConfigurationProperties("vanillabp")` bean of their
own. Every one of those is a class of the platform which happened to be public, so every change to
it was a change to the extension, and an extension for Quarkus could not exist at all.

Version 2 gives an extension four things and no base class. It describes its own annotation
(`HandlerContract`: how a method is matched, what may stand in its parameter list, whether the
return value is delivered) and VanillaBP runs those methods with the mechanics of `@WorkflowTask`.
It contributes an `AggregateServiceFactory` and gets one bean of its service per workflow
aggregate, injectable with the aggregate as its type argument. It asks the election which BPMS
holds a workflow instead of assuming the first-priority one. And it configures itself below
`vanillabp.extensions.<extension>`, with a per-workflow-module override.

What ties them together is that each is parameterized BY the extension rather than shaped after
one: the annotation type, the service interface, the extension id. Nothing in the core knows what
a user-task detail is, and an application not using an extension sees none of it. The sample
extension of both platform integrations is the proof: it builds against the two SPI artifacts and
the platform-neutral core, and would stop proving anything the moment it needed a dependency on a
platform integration.

Two shapes were deliberately not chosen. A base class per platform is what version 1 had. And a
cockpit-shaped SPI in the core - `getUserTaskDetails` and friends - would have made the next
extension a change to VanillaBP.

### 36. An extension's handler contract may arrive after the workflow services were scanned

The workflow services are scanned while the process-service beans are created. Whether an
extension's own bean exists by then depends on what else the application does - on Spring Boot an
auto-configuration's position, on Quarkus which bean is resolved first - and an extension cannot
influence it and should not have to reason about it.

So the registry remembers every workflow service which was registered, and a contract arriving
later is applied to all of them; a workflow service arriving later is scanned for every contract
known by then. Both orders produce the same result, which is what lets an extension register its
contract wherever it is convenient.

The alternative was to demand that contracts be registered before the scan, enforced by a startup
check. It would have worked, and it would have made the first question of every extension author a
question about the boot order of two platforms.

### 37. The core answers a path against the declared types, and says nothing where they cannot decide

The values an aggregate shares are a nested structure, so a BPMN expression may navigate into them, and
everything the sync model decides about a top-level attribute it decides about every segment below one. A
model reading `order.internalCode` where that attribute carries `@NoSyncWithBPMS` therefore reads `null`,
and on an embedded engine that is often silent: a gateway with a default flow takes it and a Camunda 7
conditional event answers false and waits for good, with no incident and no log line. So the check an
adapter runs while its models are deployed has to be able to ask about a path and not only about a name,
which is what `WorkflowAggregateSync#whatAPathFinds` and `WorkflowTaskWiring#unsharedWorkflowAggregatePaths`
are for.

The walk resolves each segment against the DECLARED type of the one before it, using the same primitives
which produce the values: the readable attributes of a type, the mode that type derives from its
annotations, and the table deciding what a value becomes. It answers one of three gaps. The segment is a
readable attribute the sync model keeps back. The type before it has no readable attribute of that name, so
the shared values carry no such member. Or the value before it travels as ONE value, a number or a text,
and therefore carries nothing below it, which is what makes `order.dueDate.year` read
nothing where `dueDate` is a `LocalDate`.

Where the declared types cannot decide, the answer is that nothing was decided, and no caller may read that
as approval. A `Map` answers whatever key it happens to hold, a raw or wildcard collection hides its
elements, an interface or an abstract type is whichever implementation the application assigned, and past
the nesting limit the values are cut anyway. The walk judges no method call for the same reason: what
`order.total.doubleValue()` resolves to depends on the runtime class the BPMS' serialization produced, which
a declared type does not name.

One residual risk is accepted rather than designed away. A concrete class can still be subclassed, so a
segment which is no attribute of the declared type may be one of the object actually assigned. The answer
names the declared type it read against, which is what lets a developer see a false positive for what it is,
and the alternative would be to refuse every answer about any non-final type and thereby report nothing at
all. The FIRST segment is the one exception a caller has to handle itself, because a name which is no
attribute of the aggregate may simply be a variable the model provides.

`AggregateSyncSupportTest` holds the five answers and the refusals, `WorkflowTaskRegistryTest` the boundary
around the first segment.

### 38. A check judges a model without asking which application version deployed it

For every check VanillaBP makes against a BPMN model it must not matter whether the model comes
from the current deployment or was already in the BPMS. A check may say yes or no, and it may say
that its BPMS cannot tell. What it may not do is answer no because it read the wrong set of
models: after a rename the answer often lives only in a model an earlier application version
deployed, and a verdict formed over the current deployment alone judges the application by
what it happens to have redeployed.

Two rules follow, and the first outranks everything else. A check which cannot see every model
that could carry its answer stays SILENT, never refuses: a blind check costs a warning nobody
got, a wrong one ends a call the application made correctly. And where the BPMS can be asked,
the check reads what the BPMS holds for the ids the application declares - through one picture
per adapter rather than one query per check, so "cannot tell" is decided once and answered as a
value instead of being encoded as an absence. Reading models out of a BPMS is the adapter's
work; what the core contributes is which ids are declared, and where a core check needs a held
model it asks through the version catalog it already has.

The mirror image binds the deployment-time refusals: a refusal judges the model being DEPLOYED.
A model the BPMS already holds is only being read on behalf of a check, nobody can change it any
more, and what it carries and VanillaBP cannot read is a warning naming the version - never the
end of a boot.

The adapters carry the consequences in their own decision logs and cite this entry as the rule.
The startup diagnostics' `null` defaults (`processVersionCatalogOf` and its siblings) stay as
they are: `null` means "this BPMS cannot be asked", and every check reading the picture then
stays silent, which is the first rule applied one level up.

### 39. Which adapter ids a type serves is answered in one place

An application configures adapters, and both platform integrations plus every extension bridging
to a BPMS have to turn that configuration into the same list: the ids of ONE adapter type, one set
of beans per id, because several ids of one type is what a migration looks like. Three rules
decide the list, and every consumer which reimplemented it dropped one of them.

The first is the plain one: a section under `vanillabp.adapters.<id>` whose `type` is this type, an
id without a `type` being its own type. The second is convention over configuration: an id named in
`prioritized-adapters` which IS an adapter type needs no section at all, and this one cannot be read
off the bound sections, because a section may consist entirely of keys the core model does not know
(an adapter's own `rest-address`, `webapps`, `database-schema-update`) and then nothing binds for it.
The third is the application which configures nothing: the single adapter dependency IS the
configuration, and the id the core derives is the type.

Leaving one out is invisible until it is expensive. The Spring Boot registrar applied the second rule
only where NOTHING bound onto the core model, so a migration setup with one core key in the new
BPMS' section registered no beans for the old adapter while the core derived its section and the
election looked for something serving that id. The Quarkus producers of the BPMS double filtered
the sections and nothing else, so the same two rules were missing there. Outside this repository an
extension bridging to a BPMS registers one bridge per configured engine and needs the identical
list, and a copy of it which drops a rule leaves the adapter registered and the bridge missing.

So `MigrationAdapterProperties#adapterIdsOfType` answers it, `AdapterBeanRegistrarSupport` binds
the tree off the Spring environment and asks it, and a Quarkus consumer asks it of the mapped
properties. What stays platform-specific is where the properties come from, which is the only part
that differs. `MigrationAdapterPropertiesTest.AdapterIdsOfType` holds the rules,
`AdapterBeanRegistrarSupportTest` the Spring binding and `QuarkusAdapterIdsOfTypeTest` the Quarkus
one.

### 40. A name clash is asked about where the BPMS already holds the name

`validateNoCollidingProcessIds` compares the scoped identifiers of one deployment against each
other, so it never sees the identifier another application deployed into the same BPMS years ago.
That deployment succeeds and the BPMS then decides on its own which side a start or a message
reaches. So the clash check asks as well: the adapter queries its BPMS about the identifiers this
application is about to deploy, and reports what is held already through
`reportIdentifiersTheBpmsAlreadyHolds`.

The adapter asks and the core words the warning. Only the adapter can query its own BPMS, and only
the core knows the mode, the property which set it and the scoped form a plain identifier ends up
as, which is decision 9. The adapter therefore hands over PLAIN identifiers plus a sentence naming
the holder, and the core writes our side, their side and the change which frees the name.

The question does not belong on `ProcessVersionCatalog`. That interface is keyed by a workflow
module and a BPMN process, while a message name and a decision id are scoped by the module alone.
Its answer carries a version and no holder. It is obtained for the ids the application DECLARES,
and this question is about an id somebody else holds. And its contract promises answers cheap
enough for a task dispatch, while this one is put while deploying. A question the core drives
itself fails for a simpler reason: nothing in the adapter SPI carries a message name, a signal
name, an error code, an escalation code or a decision id, so the core cannot even form the set of
identifiers to ask about.

A QUERY serves a BPMN process id and a DMN decision id, and only on a BPMS which keeps a repository
to search. Camunda 7 answers both from its repository service and Camunda 8 from its definition
searches; the Process-Engine-API answers nothing, because its API carries no read method at all.

No index answers for a message name, a signal name, a BPMN error code or an escalation code, and
that is not the same as nobody being able to answer. Those names live in a BPMN model, and a BPMS
keeps the models and hands them back: `RepositoryService#getBpmnModelInstance` on Camunda 7,
`newProcessDefinitionGetXmlRequest` on Camunda 8, and both adapters read models that way already. So
the names CAN be determined, by reading a model rather than by asking an index, and what rules the
complete answer out is the cost: one model read per definition version a BPMS holds is a number which
grows for as long as the application is in production, which is what decision 19 forbids a start to
do. Where the models are being read anyway the question is free, and that is where it is asked, see
below. Camunda 7 can also be asked about the message and signal names of START events, and that
half-answer is left out on the other rule of decision 19: a check which sometimes runs is worse than
none, because its silence stops meaning anything. A task definition is read off a held model like the rest, and what
differs is whether it is scoped at all. On Camunda 7 it is not: a task definition is process-local
there, the expression is evaluated inside the process by VanillaBP's EL resolver, nothing subscribes
to it engine-wide, so that adapter does not rewrite it and the question does not exist. A Camunda 8
job type is the opposite and is prefixed, because a job type is what a worker subscribes to,
cluster-wide. `ScopedIdentifierKind` names the kinds one by one, so a warning says "message name"
where a developer would say it.

A finding is a warning and never ends a boot, which is decision 38 applied: whoever holds the name
may be an application running correctly, and ending this boot would not help it.

Two more checks of the same subject cost nothing and were missing, and both are about the workflow
modules of THIS application rather than about somebody else's deployment. An adapter rewrites every
message name, signal name, error code and escalation code of the models it deploys through
`scopedIdentifier`, so it holds all of them while it deploys and the core learned none of them:
`reportIdentifiersTheModelsDeclare` is where they arrive now, and two workflow modules whose names
end up as one scoped form are named with both sides. Task definitions are part of that, and on a BPMS
which subscribes to them cluster-wide they are the most expensive kind of the set: two modules using
the same task definition under `none` end up with one job type, and the worker of one module fetches
the jobs of the other. A held version's job type is live for as long as workflows run on that version,
so the second check reaches it too. An adapter whose BPMS keeps task definitions process-local reports
none of them and the core needs no case for it. What is not a finding is two processes of one module
sharing a task definition: with `prefix-task-definitions-per-process` at its default their scoped forms
differ anyway, and where an application switched that off the sharing is its own explicit choice. Under `use-prefix` the forms differ and there is
nothing to report; the modes which let two modules share a name are `none`, and `by-adapter` where one
`tenant-id` for the whole adapter puts every module into one scope. Several processes of ONE module
sharing a name is the scope working as intended and is never reported.

Under `by-adapter` the line is a question rather than a verdict, and says so: what keeps the modules
apart there is the BPMS' own isolation, which is the adapter's knowledge and not the core's, so
VanillaBP cannot see whether one scope covers both modules or each has its own. Saying it plainly is
the same rule the finding about a foreign holder follows - a reader who cannot tell a certainty from a
guess ends up ignoring both.

That one warns instead of refusing, although decision 38's reasoning about a foreign deployment which
runs correctly does not apply to two modules of one application. Two reasons of its own do. Nothing is
overwritten: two BPMN processes under one id leave the BPMS holding one definition, while two modules
sharing a message name keep both models and only make the runtime meaning of the name ambiguous,
which an application may have arranged on purpose. And an application which ran like this yesterday
must not be stopped by an upgrade.

The second one reaches the names a workflow module deployed years ago.
`ProcessVersionCatalog#identifiersOfVersion` answers which of those names ONE held version declares,
read from the model the BPMS still hands back, and `reportIdentifiersOfHeldVersion` holds it against
what the current deployment of the other modules declares. It is asked in the loop
`DeployedProcessVersionsCheck` already runs over the held versions older than this boot's and not
faded out, which already reads each of those models and already knows how many workflows run on
them, so the question adds no fetch on an engine which caches parsed definitions and no query at all.
An adapter whose model read goes over the wire is expected to hold the model for the length of that
version's turn; the core caches no model. A held version of the module which still deploys the name is
continuity and stays silent, and the finding is a held version of one module carrying the name another
module deploys today. That one can only warn: nobody can change a held model any more, and the
workflows on it are running correctly, which is why the message says how many there are. Its limit is
which models get read at all - a workflow module the application dropped entirely has no catalog, so
nothing is asked about it, and its names stay invisible.

What is hard here is not the query, it is the discriminator. An identifier equal to one we deploy is
matched by our own previous version first, and no engine records which application deployed a
definition. Camunda 7 comes closest, because its adapter stamps every deployment with a name and a
source, so a definition whose deployment carries neither was not made by this adapter id; two
applications configured with the same adapter id and the same workflow module id write the same
stamp, so even that stays a hint. Camunda 8 has no owner attribute at all and goes by the resource
name, which is openly a heuristic. `certainlyForeign` carries the difference into the message,
because a reader who cannot see whether a line names another application or their own earlier
deployment learns to ignore the whole message. `IdentifiersTheBpmsAlreadyHoldsTest` holds the wording
of that one, `CollidingIdentifiersOfWorkflowModulesTest` the two checks about this application's own
modules, `OldProcessVersionsTest` that the held versions are asked in the loop which reads their
models, and `StartupQuestionCostTest` that a boot says each of the three once per workflow module
respectively per held version, however much the BPMS has collected and however many workflows run on
it.
