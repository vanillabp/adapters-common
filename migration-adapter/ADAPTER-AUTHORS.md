# Writing a VanillaBP adapter for your BPMS

This document is for a team implementing the VanillaBP adapter SPI for a business process
management system, from outside this repository. Read it from top to bottom once. It describes
the two interfaces you implement, the calls the core expects back from you, the promises your
answers make, and the things about VanillaBP you cannot infer from your own BPMS.

Everything stated here is taken from the code of this repository or from a numbered entry of its
[`DECISIONS.md`](../DECISIONS.md). Where a statement is an assumption rather than something the
code holds, it says so. What the core promises you is held by tests of this repository, and
[`README.md`](./README.md) names them per section, so a promise which stops being true turns a
build red here rather than surfacing in your adapter.

## Vocabulary

A *process* is the BPMN model, a *workflow* is one running instance of it. Use the two words
strictly this way; the SPI does.

An *adapter type* is your implementation as a whole, named by a short constant such as
`camunda7`. An *adapter id* is one configured instance of that type, and an application may
configure several ids of one type at once. That multiplicity is the migration feature and the
reason nearly every rule below is written per id rather than per type.

A *workflow module* is one deployable unit of BPMN files plus the code serving them, identified by
the file `META-INF/workflow-module` whose content is the module id. A *workflow aggregate* is the
application's own entity holding all state of one workflow; it is addressed by its id, and its id
is the only thing VanillaBP carries into a BPMS about it.

The *core* below means `migration-adapter`, the platform-neutral part of VanillaBP. The *platform
integration* is the Spring Boot or Quarkus layer which brings the core to life and hands your
adapter its collaborators.

## 1. What an adapter is, and what it is not

An adapter translates between the core and one BPMS. That sentence is narrower than it sounds,
because most of what looks like adapter work belongs to the core:

|                      Owned by the core                      |                                                  What that means for you                                                   |
|-------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------|
| Which BPMS holds a workflow (the election)                  | You answer probes about your own scope. You never pick an adapter.                                                         |
| The transaction around a workflow aggregate                 | You are called inside a transaction the core opened, or after it committed. You never open one for the application's data. |
| The transaction outbox and its idempotency keys             | You never persist an outbox entry, and you never derive its key.                                                           |
| The record of the task deliveries VanillaBP processed       | You report an identity per delivery. The core decides what to do with a repeated one.                                      |
| Configuration validation and the startup messages around it | You validate your own keys, in the same shape.                                                                             |
| The viewer and history API the application sees             | You answer it for your BPMS in native ids; the core namespaces them.                                                       |

Before you implement anything, ask which part of it is the same for every BPMS. That part is
already in the core or belongs there, and building it in an adapter is the most common wrong turn
this SPI invites. Version 1 of VanillaBP put the outbox and the eventual-consistency handling into
the individual adapters, which is exactly why an application could not be migrated from one BPMS
to another. If you find yourself writing retry logic, an idempotency registry or a rule about
which BPMS should serve a call, stop and ask us instead of writing it.

Five consequences are worth stating before the interfaces.

You build one runtime object per configured adapter id, never one per type. Two ids of your type
side by side is how an application migrates from one cluster, one tenancy model or one engine
version to the next, and an adapter which builds a single instance from whichever id it finds
first silently breaks that.

What makes two such ids DIFFERENT is knowledge only you have, and the core asks you for it once at
startup wherever more than one id of your type is configured
(`AdapterDeploymentService.validateDistinctAdapterInstances`). End the boot for a pair which
addresses the same system, and name the property which would tell the two apart, because the core
formats no message here. An embedded engine wants a database or a table prefix of its own, which is
what Camunda 7 answers with `data-source-name` and `table-prefix`; a remote one wants an address,
credentials, a cluster or a tenant of its own, which is what Camunda 8 answers with; the
Process-Engine-API can tell nothing apart and refuses the second id outright. Two ids which
deliberately share one backend are a supported setup, and what keeps them from answering for each
other is the scope contract of section 4.

The id is an identity and not a label. Every outbox entry and every delivery record carries the one
it was written for, so renaming an id orphans the work those records still hold open, and the
symptom arrives much later as a workflow which was saved and never started (decision 17). Read it
from configuration and derive it from nothing which can move.

Your configuration lives under `vanillabp.adapters.<id>.*`, in the same tree the platform keys
live in, contributed as an overlay of the `vanillabp` prefix. Do not open a namespace of your own.
Properties which vary by scope resolve at four levels, the most specific configured value winning:
a task, then its workflow, then its workflow module, then the adapter.

You ship both platforms. A feature which exists only on Spring Boot does not exist, because
VanillaBP promises the same behaviour on Quarkus and the next platform after it.

You never implement the integration SPI. `PhaseTwoOutbox`, `TaskDeliveryLog`, `TransactionRunner`,
`AggregatePersistenceAware` and `WorkflowAdapterCache` are implemented by the platform or by the
application. The core uses them on your behalf, and a type from the adapter SPI must never appear
in a module business code compiles against.

Your messages are part of the SPI although no interface carries them. VanillaBP's promise is that a
developer reaches a working configuration from what the application says at startup, with almost no
documentation, so every failure of yours names the workflow module, the BPMN process and the
workflow aggregate it is about, says what was attempted, and gives at least one way out with the
exact property key or the change to make. Validate at startup rather than at the first workflow,
prefer a warned no-op over an exception for work which already happened, and never echo a
credential. Nothing enforces this, and a review holds you to it anyway.

## 2. The two interfaces

You implement `AdapterDeploymentService<BPMN, PC>` and `MigratableProcessService<A>`, one instance
of each per configured adapter id. Both live in `io.vanillabp.integration.adapter.spi`, the package of
the artifact `io.vanillabp:vanillabp-adapter-spi`, which is the one dependency your core needs from
VanillaBP: the integration SPI and the extension SPI arrive with it.

### 2.1 The deployment pipeline

`AdapterDeploymentService` extends `ExtensionWiringService`, so its methods arrive in a fixed
order per workflow module:

```
readBpmn → prepareBpmn → wireBpmn → deployResources → startWorkflowProcessing
                                                       …
                                                      stopWorkflowProcessing
```

The same pipeline is drawn call by call, with every call-back you make while wiring, under
[Deployment pipeline](./README.md#deployment-pipeline) in the core's README.

`BPMN` is your own model type, whatever your BPMS parses a file into. `PC` is a processing context
you invent: an accumulator the core threads through the whole pipeline, from the first file of a
module to `startWorkflowProcessing`. Extensions such as the Business Cockpit join a pipeline by
matching both types, so declaring interfaces rather than concrete classes widens who can join you.

|                               Method                               |                               Who calls it, and when                               |                                                              What it has to do                                                              |                                                                                                        What a wrong answer costs                                                                                                         |
|--------------------------------------------------------------------|------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `getAdapterId()`, `getAdapterType()`                               | the core, everywhere                                                               | the id from configuration, the type as a constant                                                                                           | an adapter answering its type where an id is meant serves one instance for all of them                                                                                                                                                   |
| `readBpmn(module, filename, stream, isVanillaBpBpmn)`              | the deployment pipeline, per BPMN file                                             | parse the stream into your model and return one entry per executable process the file declares; throw `BpmnParseException` on a parse error | the stream is owned by the pipeline: closing it yourself breaks the next reader                                                                                                                                                          |
| `prepareBpmn(module, existingContext, filename, processId, model)` | per process, before wiring                                                         | rewrite the model for your BPMS and return the context, never `null`                                                                        | this is called once per PROCESS while all processes of a file share one model, so rewrite once per FILE and remember that in the context; both Camunda adapters guard it, and the failure mode is doubled prefixes and stacked listeners |
| `wireBpmn(module, filename, processId, model, context)`            | per process                                                                        | inspect the model and make the wiring calls of section 3                                                                                    | a task with no handler is then found by a running workflow rather than by a boot                                                                                                                                                         |
| `deployResources(module, context)`                                 | once per module, after all files were wired                                        | push the resources to your BPMS, then report the deployed version per process                                                               |                                                                                                                                                                                                                                          |
| `startWorkflowProcessing(module, context)`                         | once per module and adapter, after every adapter of that module finished deploying | open workers, subscriptions, job executors                                                                                                  | nothing runs                                                                                                                                                                                                                             |
| `stopWorkflowProcessing(module, context)`                          | on a graceful shutdown, extensions first and adapters last                         | close what you opened                                                                                                                       | handlers are cut off mid-flight; see the shutdown paragraph in section 5                                                                                                                                                                 |
| `getOrder()`                                                       | the pipeline                                                                       | order among the wiring services sharing your model type                                                                                     |                                                                                                                                                                                                                                          |
| `defaultNameClashAvoidance()`                                      | while deploying                                                                    | what "nothing configured" means for your BPMS; every existing adapter answers `BY_ADAPTER`, which is version 1's behaviour                  | an application upgrading without touching its configuration stops finding its running workflows                                                                                                                                          |
| `warnAboutUnscopedIdentifiers(module, fromDefault)`                | once per module and id, when the mode is `NONE`                                    | name the alternatives YOUR BPMS offers                                                                                                      | the default names only what every BPMS can offer                                                                                                                                                                                         |
| `validateDistinctAdapterInstances(ids)`                            | once per type at startup, only with more than one id                               | fail the boot where two ids of your type cannot be told apart                                                                               | two ids silently address one backend, and the election has nothing to distinguish them by                                                                                                                                                |
| `checkHealth()`                                                    | the platform's health endpoint, on the request thread                              | the cheapest question your BPMS answers, returning within a bounded time                                                                    | throwing is turned into `DOWN` as a backstop; an unconfigured connection is `UNKNOWN`, never `DOWN`                                                                                                                                      |

A failure thrown out of `wireBpmn` or `deployResources` is subject to
`vanillabp.adapters.<id>.deployment-failure`. The default `fail` ends the boot; `warn` lets an
adapter which is not first in the priority order fail without preventing the application from
starting, which is what an application migrating away from an old BPMS needs when that BPMS is
down. Throwing is all you do. The policy is the core's
(`DeploymentFailureFailTest`, `DeploymentFailureWarnTest`).

In your constructor, call
`AdapterPlatformVersion.requireCompatiblePlatform(adapterType, aClassOfYourCore)` and ship a
descriptor `META-INF/vanillabp/adapter-<type>.properties` naming the platform version you were
built against. Without it, an application combining your adapter with an older platform fails with
a `NoSuchMethodError` somewhere inside your code instead of a message naming both versions.

### 2.2 The runtime service

`MigratableProcessService<A>` is what the core's `MigrationProcessService` delegates to. It has
three groups of methods: everything the BPMS is asked to DO, everything it is asked ABOUT, and a
few switches saying what your BPMS can and cannot do.

What your BPMS does is one method: `phaseOperations()`, returning a
`PhaseOperationHandler<A>` per `PhaseOperation`. This is the whole of what you write about
outbound work. An operation is defined once in the core, and the definition carries its persisted
name, its idempotency-key rule, which BPMS serves it, whether every adapter has to serve it, and
the words it names itself with in a message. What it DOES is the only part which differs per BPMS,
and that is your handler (decision 29 in the repository's `DECISIONS.md`). What is left on the
interface once the handlers carry the operations is drawn under
[An operation is defined once](./README.md#an-operation-is-defined-once).

The core operations today are `START_WORKFLOW`, `START_WORKFLOW_BY_MESSAGE`, `COMPLETE_TASK`,
`CANCEL_TASK`, `COMPLETE_USER_TASK`, `CANCEL_USER_TASK`, `CORRELATE_MESSAGE`, `SEND_SIGNAL` and
`AGGREGATE_CHANGED`. Leaving one out of the map says your BPMS has nothing like it. That is a
legitimate answer for `SEND_SIGNAL` and for `AGGREGATE_CHANGED`, and the application learns about
it as a `PhaseOperationNotSupported` when it actually asks. For every other operation the boot
refuses your adapter and names it (`AdapterOperationsAtStartupTest`). The map itself is the
statement, and the boot reads the map: nothing looks behind a handler, because that would be
reflection and a native image has none.

A handler is a pair of methods, and the split between them is the same for every adapter and every
operation: phase one asks, phase two acts (decision 3).

`phaseOne(PhaseOneRequest)` runs inside the transaction the application called from, and it only
ASKS. Does the parked task still exist, is a subscription waiting for this message, does a
deployed model declare it at all. Where your BPMS offers a lock for what phase two will do, taking
that lock is asking as well. Throwing here fails the caller's transaction, which is the point: the
stack trace still points at the business code which made the call. Doing nothing here is a
legitimate answer, and starting a workflow is the case where there is nothing to ask about.

`phaseTwo(PhaseTwoRequest)` runs after that transaction committed, on the outbox dispatcher's
thread, and it is the only place your BPMS is changed.

Phase one must never advance the process, whether your BPMS is remote or embedded. The
embedded case is the one people get wrong, so here is the reason in full: an engine command which
loses a concurrency conflict cannot be repeated inside the caller's transaction, because the
conflict leaves that transaction rollback-only, and repeating just the engine part in a
transaction of its own would advance the process while the application rolls back. There is no
switch which would let you act in phase one. There used to be one, no adapter ever answered it,
and it was removed rather than renamed (decision 26). An adapter which starts a workflow or
completes a task in phase one breaks the contract silently: the core schedules phase two anyway,
and the operation happens twice.

`PhaseTwoRequest` gives you the aggregate's ID rather than the aggregate, because phase two runs
after the commit and whatever you need is loaded there and then. Its `activationId()` names the
element instance the operation was planned in, and it travels only for operations whose definition
says so. Read it if your BPMS deduplicates messages in a net of its own: three elements of a
multi-instance call activity reach the outbox as three operations and would reach such a BPMS as
one message otherwise, and VanillaBP cannot repair that from its side.

What your BPMS is asked about are the four awareness probes and the read-only viewer methods.
The probes are section 4. The viewer methods, `getProcessDefinitions`, `getBpmnXml` and
`getWorkflowHistory`, have no phases and no transaction; their defaults throw a guiding message,
so a BPMS which cannot serve them says so by not overriding. Definition ids are yours and native;
the core namespaces them per adapter id before the application sees them and strips the namespace
before calling you back. An empty list or `null` means "this adapter does not know it", which the
core turns into the exception the SPI documents. A BPMS which records no element history reports
that history as `null`, which is an answer and not an error.

The remaining methods are switches. Each of them is small and each of them changes what the core
does:

|                  Method                  | Default |                                                                                                                                                                                                                                   What it decides                                                                                                                                                                                                                                    |
|------------------------------------------|---------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `deliversTasksAtLeastOnce()`             | `false` | say `true` where your BPMS learns a task's outcome only AFTER the application committed locally, which is every remote BPMS and an embedded one on a datasource of its own. The default is right only where the delivery shares the application's transaction, because a repeated delivery there proves nothing was committed. What it decides is whether a missing delivery log is worth a guiding message at startup. It does not switch deduplication on; `getDeliveryId()` does. |
| `isPhaseTwoFailureRepeatable(Throwable)` | `true`  | a `false` blocks the outbox entry after one attempt instead of retrying it. Say it only for what your BPMS answers identically every time, a malformed request or an identifier which does not exist. Keep that list short: repeating is the safe answer.                                                                                                                                                                                                                            |
| `workflowVisibilityDelay()`              | none    | how long an `UNKNOWN_TO_BPMS` of your BPMS may still turn into `ACTIVE`. Report a window where your probe reads a model which lags behind the engine, and no longer than it honestly needs: it is the due time an outbox entry gets before it asks again, and every one of those costs the entry an attempt.                                                                                                                                                                         |
| `canLocateWorkflows()`                   | `true`  | whether your workflow probe asks your BPMS or guesses. See section 4.                                                                                                                                                                                                                                                                                                                                                                                                                |
| `openTaskCount(module, process)`         | `null`  | how many tasks of a process your BPMS holds open, asked once at startup so an upgraded application learns how long its unrecorded deliveries can still surprise it. Answer only if your BPMS counts and you read the number; fetching the tasks to count them makes the boot grow with the years the application ran (decision 19).                                                                                                                                                  |

## 3. What the core hands you, and what you have to hand back

### 3.1 The collaborators arrive in one object

Your constructor takes an `AdapterCollaborators`, built by the platform integration for one
adapter id. Never take a collaborator by setter. The reason is a defect this SPI was reshaped
around: a registrar which forgot one setter produced an adapter which deployed its files, ran its
tasks and never reported a workflow end, with nothing failing anywhere (decision 28).

Five collaborators are always there, and a set built without one of them throws while naming your
adapter id (`AdapterCollaboratorsTest#aMissingMandatoryCollaboratorIsRefused`):

`workflowTaskWiring()` is what you call while you read a BPMN file. `workflowTaskInvoker()` is
where a delivered task goes at runtime. Both are implemented by the same core object, and they are
two interfaces because a mandatory call an adapter can forget will eventually be forgotten. The
Camunda 7 adapter left out the check that every `@WorkflowTask` method matched a task somewhere,
for a year, and a typo in a task definition therefore stayed silent until a workflow reached that
task. That check is the core's now, which is what section 3.2 ends with.

`scoping()` is `NameClashAvoidanceSupport`, how you keep the identifiers of two workflow modules
apart. `workflowAggregateSync()` tells you which values of an aggregate your BPMS may see.
`preCommitRegistrar()` is where you hang a check which has to run right before the caller's
transaction commits, which is what shrinks the window in which its answer can go stale.

Scoping is worth its own paragraph, because the core only ever speaks plain identifiers and
everything BPMS-specific about them is yours. `modeFor(module, process, adapterId)` answers which
of three modes applies, and what a mode MEANS is your BPMS' business. `BY_ADAPTER` is its own
isolation mechanism, a tenant on both Camunda adapters and whatever plays that part on yours; it is
the default every adapter reports, because it is what version 1 did and an application upgrading
without touching its configuration has to find its running workflows again. `USE_PREFIX` puts the
workflow module id in front of the identifiers your BPMS sees and takes it off again on the way
back, which is the answer where a vendor licenses per tenant; `scopedProcessId` and its siblings
give you the one form, `plainProcessId` and its siblings the other. `NONE` scopes nothing, and
every adapter reports it per workflow module naming the alternatives its own BPMS offers. Refuse a
mode your BPMS cannot serve while you deploy, rather than putting every workflow module into one
scope, and where your isolation is configured while no level asks for `BY_ADAPTER`, hand that
property key to `validateNoneNameClashStrategy`: the setting and the mode contradict each other,
and quietly ignoring the setting is the one outcome nobody asked for. On your side it comes down to
one habit: scope on the way out, at every boundary your BPMS sees, and unscope on the way back,
the identifiers of an inbound delivery included.

Where you hold the support in a place it may be absent, a unit test or a helper built without a
platform around it, do not write `scoping == null ? plain : scoping.scoped…` yourself. The same six
methods exist as STATIC ones taking the support as their first argument
(`NameClashAvoidanceSupport.scopedProcessId(scoping, module, process, adapterId)`), and they answer
the plain identifier where there is nothing to scope by. Two hand-written copies of that check
eventually disagree, and a disagreement here is a query which finds nothing and looks exactly like
a workflow which does not exist.

Two collaborators arrive as `Optional`, because an application which never asks for them has
nothing to report to: `workflowEndedInvoker()` and `bpmsInitiatedStartInvoker()`. Work without
them. Both platform integrations do provide them, so an adapter built without one is nearly always
a registration which left it out, and the build says so in a warning naming your adapter id.

What is not in the object is what you resolve from your own configuration, a job timeout or a
retry backoff or the variables your worker fetches, and what your own extension contributes. Those
stay your own constructor arguments.

### 3.2 What you call while you deploy

These are calls on `WorkflowTaskWiring`, made from `wireBpmn`, per BPMN process. You are the only
one who can read your own BPMN dialect, which is why everything the core needs about a model
arrives here.

|                                     Call                                      |                                                                                              Why it is yours                                                                                               |
|-------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `validateTaskWiring(module, process, tasks)`                                  | every BPMN task has to be served by a `@WorkflowTask` method; all defects are collected into one message. Call it for every executable process of a file, the ones no workflow service claims included     |
| `taskParameterNames(module, process, taskDefinition)`                         | if your BPMS ships a variable payload with a delivery, you have to know the names before you subscribe. The answer is the sorted union over the methods serving that element in different process versions |
| `workflowTaskCompletesAsynchronously(module, process, taskDefinition)`        | refuse a wiring which cannot keep a task open, while the application boots rather than as an incident                                                                                                      |
| `workflowsShareTheWorkflowAggregate(module, process, otherProcess)`           | a called process running on the same aggregate has to be handed the caller's identity, and one running on its own aggregate must not                                                                       |
| `reportConcurrentTokenElements(module, process, elementIds)`                  | the elements which can put a second token into a running workflow; the core warns where the aggregate has no version attribute                                                                             |
| `registerProcessVersions(adapterId, module, process, catalog)`                | only where your BPMS can place version tags. The core asks you again after the deployment, for the ids the application declared without a model - see below                                                |
| `unsharedWorkflowAggregateProperties(module, process, names, adapterDefault)` | the identifiers your models read which the aggregate does not share, so the developer hears about it at startup                                                                                            |
| `unsharedWorkflowAggregatePaths(module, process, paths, adapterDefault)`      | the same question for a whole dotted path, if you can read one out of your model. The core walks the declared types and names the segment which stops the path, or says nothing where they cannot decide   |
| `resolveWorkflowAggregateIdName(module, process)`                             | the variable name a BPMS without a business key stores the aggregate's id under                                                                                                                            |

A `BpmnTaskSpec` carries a fourth thing next to the activity id, the task definition and whether a
handler is optional: the `name` attribute the modeller wrote on the element. Fill it. You walk that
element anyway, and you are the only one who can read your BPMN dialect, so an extension which
needs the label a person reads in a task list would otherwise parse the same bytes a second time.
Nothing VanillaBP decides depends on it: the core keeps the names of the tasks you hand to
`validateTaskWiring` and answers `ExtensionHandlers#bpmnTaskNameOf(module, process, activityId)`
with them, and an element you pass no name for is answered with nothing rather than with a guess.
The constructors and the `userTask` factory without a name are unchanged, so an adapter which does
not read one keeps compiling and keeps behaving as it did.

If your BPMS can start a workflow by itself, ask `BpmsInitiatedStartInvoker` to validate the start
events you found, and throw where your BPMS cannot report such a start at all: a workflow running
without an aggregate is worse than a deployment which failed. Ask `WorkflowEndedInvoker` whether a
handler for the end of a workflow exists at all, and attach your listener only where it does.

At the end of `deployResources`, per process, call `registerDeployedVersion`. Do it also when your
BPMS deployed nothing because nothing had changed, because only you can find out which version it
ended up with, and the core needs that border between the model of this boot and the older ones.

The module-level checks are the core's, not yours. Once the last adapter of a workflow module
finished deploying, the core makes four calls on `WorkflowTaskWiring`, and an adapter makes none
of them.

It begins by reporting the BPMN processes of the module which no `@WorkflowService` class claims,
read from `bpmnProcessesWithoutWorkflowService` and written with the deployment's other startup
reports. You never call it, but whether its report is complete is up to you: it can only name a
process you called `validateTaskWiring` for, so one you skipped because nothing claimed it stays
unmentioned.

The other three run per workflow module, in an order which matters.
`registerVersionsOfProcessesNobodyDeployed` comes first, once per adapter of the module, and it is
what brings the core back to your deployment service after everything was deployed:
`processVersionCatalogOf(module, process)`. It asks what your BPMS holds for a BPMN process the
application declares without bringing a model for it, which is what renaming a BPMN process leaves
behind - the old id stays in the BPMS with every version ever deployed under it and with the
workflows still running on them, and `@WorkflowService(secondaryBpmnProcesses = ...)` is how the
application says that it keeps serving them. Answer with the same catalog you would have registered
while wiring, searching by the id you would have deployed it under (your prefix, your tenant), or
`null` where your BPMS cannot be asked about the versions of a process - the default. Where you
answer `null`, nothing changes for your adapter.

What a catalog is asked is one question about the process and a few per version it holds.
`deployedVersionsOf` and `resolveVersion` place the version ranges which name a tag.
`activeInstanceCountOf` says how many workflows still run on a version, and `whatOlderVersionsMiss`
names what a model your adapter did not rewrite will never get. The rest read a model your BPMS
still holds: `tasksOfVersion` for the tasks of that version, so the core can tell whether the
application still serves them, `startEventsOfVersion` for the start events your BPMS fires on its
own there, and `concurrentTokenElementsOfVersion` for the elements which can put a second token
into one of its workflows. Each of them is the walk you already run while wiring, run over a model
your BPMS holds, so extract it once and use it for both directions rather than writing it twice.

`startEventsOfVersion` exists for the id a renamed process left behind. Nothing wires such an id
while an application boots, so the `@WorkflowStartedByBpms` methods kept for it are judged by
nothing unless you can say what the held models start on, while your BPMS may fire the old timer
every day. `concurrentTokenElementsOfVersion` is asked only about a version workflows still run
on, and it is asked because those workflows run longest: a parallel gateway your newest model
dropped keeps forking every workflow started before it, and an aggregate without a version
attribute loses an update there exactly as it would in the model just deployed.

Every model-reading question answers `null` where your BPMS cannot read that model, and the check
reading it then says nothing about that id instead of judging it by an answer you do not have.

The same boundary governs every check your adapter makes against a BPMN model: its verdict must
not depend on which application version deployed the model it judges. Read what your BPMS holds
for the ids the application declares wherever it can be asked, and where it cannot, say nothing
rather than refuse - decision 38 of the platform's DECISIONS.md carries the rule, including its
mirror image: a refusal judges the model being deployed, while a model your BPMS already holds
is only being read and is answered with a warning at worst.

Serving those workflows is a second matter, and that one IS yours.
`taskWiringOfProcessesNobodyDeployed(module)` on `WorkflowTaskWiring` names the BPMN processes the
application declares without a model, together with what its `@WorkflowTask` methods serve for
each of them, and you may ask it any time after the module was deployed. Read an entry as "what to
compose my own identifier from" rather than as an identifier of your BPMS: today it is the
`taskDefinition` a method names, and what an application may name its wiring by can widen. Whether
you have to act on it depends on how your BPMS hands work out. Where a task is addressed by a name
which does not carry the process id, such a workflow is served without anybody doing anything, and
there is nothing for you here. Camunda 8 under `use-prefix` is the other case: a job type is scoped
by the process it was deployed with, so the jobs of the workflows under the old id carry a name no
worker of the renamed application asks for, and nobody notices, because an unfetched job is not a
failed one. Open a subscription per task definition of the old id, or wire the models your BPMS
still holds under it, and say while starting what you cannot reach. An id may be named with no task
definition at all: a method wired to a BPMN element id names none, and without a model there is
nothing to read one off.

`validateNoUnwiredWorkflowTaskMethods` comes after those versions on purpose: it is the reverse of
the wiring check, every `@WorkflowTask` method held against the tasks of the whole module, and a
method kept for a renamed process is indistinguishable from a method wired to nothing until the
core knows what the BPMS still holds. What it judges is what your `validateTaskWiring` calls marked
as wired, which is the second reason to make that call for every executable process of a file.

`resolveProcessVersions` runs last, placing the version ranges which name a tag against the
catalogs you registered while wiring and the version you reported for this boot. It comes after the
reverse check because a method serving no task at all is the more basic defect.

### 3.3 What you call at runtime

`WorkflowTaskInvoker.invokeWorkflowTask(module, process, context)` is the one call which matters.
You build a `TaskInvocationContext` per delivery and make that call on your own thread; the core
resolves the handler, opens a transaction, loads the aggregate, binds the parameters, runs the
method, saves the aggregate, writes the delivery record and hands you back a `WorkflowTaskOutcome`.
The context and the two other ones you build are drawn beside the delivery they belong to, under
[Workflow-task processing](./README.md#workflow-task-processing).

What your context answers decides how much of VanillaBP works for your BPMS:

|           Method            |                                                                                                                                                                                                                                  What it is for                                                                                                                                                                                                                                   |
|-----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `getTaskDefinition()`       | routes to the `@WorkflowTask` method                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `getWorkflowAggregateId()`  | the aggregate's id in serialized form                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `getTaskId()`               | what a method with a `@TaskId` parameter completes the task by later                                                                                                                                                                                                                                                                                                                                                                                                              |
| `getTaskEvent()`            | created or cancelled, for the notifications a user task produces                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `getDeliveryId()`           | the identity of the DELIVERY: equal across redeliveries of one task, different for a new task instance. Without it the core cannot tell a redelivery from a new task and runs the handler again                                                                                                                                                                                                                                                                                   |
| `getActivationId()`         | the identity of the ACTIVATION: different between two activations of one element, saying nothing about redeliveries. It is not the delivery id under another name, and answering it with the delivery id is right only where your BPMS happens to name deliveries after element instances                                                                                                                                                                                         |
| `getAdapterId()`            | a delivery proves which BPMS holds the workflow, and the core remembers it                                                                                                                                                                                                                                                                                                                                                                                                        |
| `getProcessVersion()`       | matched against the `version` attribute of the annotations                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `getTaskParameter(name)`    | values the MODEL produced, never values of the aggregate                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `getMultiInstances()`       | index, total and element of a multi-instance activity                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `runInCurrentTransaction()` | whether the delivery already runs inside a transaction the application's persistence takes part in, which is the only case in which the core joins instead of opening one of its own. A transaction of your BPMS which that persistence does not join is not this                                                                                                                                                                                                                 |
| `predatesDeployedVersion()` | whether this delivery belongs to a workflow which was already running before the version this boot deployed. Nothing is routed by it. It turns the report about a task open for too long into an "at least", because such a task was open before VanillaBP ever wrote a record for it and its age counts from the first delivery afterwards. Answer it by comparing two values you already hold, never by asking your BPMS, and answer `false` where your BPMS counts no versions |

Then act on the outcome. A completed task is completed with the shared values, which you read
through `syncedWorkflowAggregateValues` (or its `...InCurrentTransaction` variant where the
handler's transaction is still open, which is what an embedded engine needs). A BPMN error is
thrown into the model by whatever mechanism your BPMS has, and the aggregate's changes stay
committed. A pending completion means you leave the task open and the application completes it
later by its task id.

WHICH values are shared is never your decision. `workflowAggregateSync()` answers it from the
aggregate's `@SyncWithBPMS` model and the default your adapter declares, and what is left to you is
when and where they land: an embedded engine writes them inside its own transaction at every point
it touches the workflow, a remote one attaches them to every command it sends on that workflow's
behalf. Beside those values travels the technical variable named after the aggregate's id
attribute, whose name `resolveWorkflowAggregateIdName` gives you, or the business key where your
BPMS has one. It is not part of the shared map and it does not depend on the model at all: write it
with every command, including for an aggregate annotated `@NoSyncWithBPMS`, which shares nothing
and would be unfindable afterwards without it (decision 10).

Any exception the core throws back at you means the transaction was rolled back and no record was
written. Apply your BPMS' retry semantics and never report the task as completed. A version
conflict on the aggregate was already logged once by the core with a guiding message, and it is
deliberately not retried inside VanillaBP: a handler may have called a remote API before the
commit failed.

Two more inbound notifications exist, and both are optional because not every BPMS can produce
them. `WorkflowEndedInvoker.workflowEnded(...)` reports that a workflow ended, as completed or as
terminated; report the weaker fact rather than inventing the distinction where your BPMS cannot
tell the two apart, and treat a missing aggregate as something to skip rather than an error.
`BpmsInitiatedStartInvoker.startWorkflowByBpms(...)` reports a workflow your BPMS started on its
own, through a timer, a signal or a conditional start event, and the core builds the aggregate for
it. The id it derives comes from your `getNaturalIdentity()` first, then from `getStartInstant()`,
then from a generated value. That order is the whole reason the second method is not called a
trigger time: what it ideally reports is the instant the engine scheduled the start for, because a
cyclic timer firing the same instant twice then addresses the same aggregate, but where your BPMS
does not hand that time to a listener you report the moment of the notification instead, and the
method's name does not then contradict you. If your notification can repeat after the aggregate was
committed, report a natural identity, because that is what keeps a repeated notification from
building a second aggregate.

## 4. The promises a probe makes

The election walks the prioritized adapters and stops at the first `ACTIVE`, so it is exactly as
right as the answers it gets. The core cannot check any of this. Which adapters may be asked is
its business; which workflows an adapter owns is only yours. The walk is drawn twice under
[Awareness contract](./README.md#awareness-contract-workflowawareness) in the core's README, once
as it runs inside the caller's transaction and once as it runs at the dispatch of an outbox entry.

Answer for the scope you are given, not for what your instance holds. Every probe takes a
`WorkflowScope`, the workflow module and the plain BPMN process ids the calling process service
serves. Anything outside it is `UNKNOWN_TO_BPMS`, and never `ACTIVE`, because the unknown answer
is what lets the walk reach the adapter which really holds the workflow (decision 4). Neither a
task id nor an aggregate id is sufficient evidence on its own. Two adapter ids may address one
backend, which is the supported setup migrating a workflow module from one scoping to another, and
there the keys are global and do answer. Two workflow modules of one backend may carry the same
aggregate id, because aggregate ids are unique per aggregate type and not across an application.
`ElectionScopeContractTest` holds this from the core's side: the walk reaching the holder, the
walk stopping at an adapter which claims more than it holds, and a workflow of another module of
the same adapter not being claimed.

A probe must never advance the workflow. It is a question asked before an operation is routed, and
it is asked of adapters which do not hold the subject at all, so a probe which completes a task or
correlates a message moves somebody else's workflow. A non-advancing command is still allowed
where it is the only way to ask, and it has to be scoped before it is sent. Renewing a lock is the
example: a job-timeout update or an empty user-task update answers exactly, renews a lock and
advances nothing.

The two negative answers mean different things and the difference is load-bearing.
`UNKNOWN_TO_BPMS` means a successful query found nothing, and only it permits falling back to the
next adapter. `BPMS_UNAVAILABLE` means you could not ask, and it must never fall back, because the
workflow may well live in your BPMS. Getting this backwards misroutes a migration in a way nothing
else will catch. `COMPLETED` is a real answer and not a variant of unknown: the BPMS asked is the
one which was responsible, and the operation simply comes too late.

The redispatch probe must never be optimistic. `awarenessOfWorkflowForRedispatch` is asked before
a recovered or repeated `START_WORKFLOW` entry is dispatched, and answering "known" SKIPS that
start. A wrong "known" therefore loses a workflow, while a wrong "unknown" costs the duplicate the
at-least-once residual permits anyway. Its default delegates to the ordinary workflow probe, which
is right only where that probe is an honest query. Where it is not, override this one and answer
`UNKNOWN_TO_BPMS`. `OutboxRedispatchMitigationTest#retriedStartEntryDoesNotStartASecondWorkflow`
is the probe doing its job.

`canLocateWorkflows()` deserves an honest answer for the same reason. If your BPMS cannot be asked
whether it holds a workflow, the only answer which keeps a single-BPMS application working is an
optimistic `ACTIVE`, and next to a second adapter that answer routes operations by list order
instead of by evidence and takes the other BPMS' workflows. Saying `false` here is what lets the
core refuse that combination while it boots. The message names your adapter, the workflow module
and the ways out, one of which is `vanillabp.election.guessing-adapters: ACCEPTED` for an operator
who accepts the risk. The answer is fetched after you deployed, so an adapter which only learns
what its BPMS can do while deploying may answer from what it found out by then.
`GuessingAdapterStartupTest` holds the refusal, `AcceptedGuessingAdapterStartupTest` the accepted
variant.

Report a visibility delay where your reads lag. A remote BPMS whose probe reads an exported read
model answers "unknown" for a workflow which exists, and that is what the read model knows rather
than a defect to hide. `workflowVisibilityDelay()` is how you say so.

Where the core waits and where it does not is decision 27, and it is worth knowing because it
explains what your answers cost:

|                  Your answer                  |                 Phase one does                  |       The dispatch of an entry does       |                      A read does                       |
|-----------------------------------------------|-------------------------------------------------|-------------------------------------------|--------------------------------------------------------|
| `ACTIVE`                                      | the operation runs                              | the operation runs                        | you answer                                             |
| `COMPLETED`                                   | a warned no-op                                  | the entry is consumed                     | you answer, an ended workflow is viewable              |
| `UNKNOWN_TO_BPMS` for a task                  | the caller gets a task-not-found error at once  | the entry is consumed, the task is gone   |                                                        |
| `UNKNOWN_TO_BPMS` for a workflow, with a hint | the operation is planned and the caller returns | repeats the entry, due in your window     | waits out your window, then a workflow-not-found error |
| `UNKNOWN_TO_BPMS` for a workflow, no hint     | a workflow-not-found error at once              | the entry is consumed as stale            | a workflow-not-found error at once                     |
| `BPMS_UNAVAILABLE`                            | an exception naming your adapter, at once       | retried twice, then the entry is repeated | retried twice, then the exception                      |

Phase one never sleeps, because it runs inside the caller's transaction and holds a database
connection and the locks on the aggregate. The waiting happens where no application transaction is
open. A read has no second place to go, so it waits itself, bounded by the same hint and the same
window. A hint exists only where VanillaBP knew the answer without asking anybody, because it
started the workflow itself or because a delivery for that workflow arrived from that BPMS. Every
row of that table is a case of `WorkflowLocatorTest`, and the read column additionally of
`ViewerApiTest#readWaitsForAnEventuallyConsistentAdapterToCatchUp`.

One question about a task may not reach you at all. Where the CALL names a task and the delivery of
that task wrote a record naming your adapter, phase one routes by that record instead of probing
(decision 30). A record of a task still open answers for every operation, a closed one only for the
operations which end that task, because the workflow may well run on. The dispatch of the entry
probes as it always did, so a workflow which changed its BPMS in between is still found, and where
no record exists the walk runs exactly as it always did. It is either right or silent.

## 5. What you must never assume

Every line here is a mistake an adapter has made or nearly made.

### About threads

Phase two runs on the outbox dispatcher's thread. There is no request scope, no security context,
no MDC of the caller, and nothing bound to the thread by the application. Whatever phase two needs
travels in the call's arguments, which is why the activation id is an argument rather than
something you read off the thread.

Your inbound threads are yours, and bounding their number is your decision to make. Camunda 8 runs
four platform threads by default because one blocking handler once stopped that adapter from
polling at all, and because every running handler holds a database connection.

`stopWorkflowProcessing` races the handlers which are still inside the application. Decide the
policy and write it down in your README. Camunda 8 waits for its handlers and for the cluster to
release its workers, and reports no job as failed while the shutdown runs.

The core may call your probes from any application thread and from the dispatcher at the same
time.

### About deliveries

Every delivery may come again: after a crash, after a lock timeout, or because your completion
command failed after the local commit. The core answers a repeated delivery from its record only
where you report a stable delivery id. Without one, the handler runs again and the application's
own idempotency is the only net.

Two deliveries of one task at the same moment both run. Your BPMS' lock is the only thing which
prevents it. When it happens, the core writes a warning naming the task and counts it, because a
record can only speak about work which is already committed and neither of the two had committed
when the other read (`InboundIdempotencyTest#twoDeliveriesAtTheSameTimeAreNamedAndCounted`).

A phase-two call may arrive twice, may arrive for a task which is already gone, and, for
everything except a start and a signal, may arrive after the workflow moved to another adapter.
Answer honestly; the core re-probes.

A rebuilt BPMS which restarts its key range makes old delivery records answer new deliveries.
Document the operational step for your BPMS. This one is an assumption rather than something a
test holds: it follows from the delivery id being your BPMS' own identity, and a BPMS which never
reuses a key after a rebuild would disprove it.

### About ordering

Outbox entries have no order. A pushed aggregate and a task completion scheduled in one
transaction may reach you in either order.

Phase two of a start may run before or after the first inbound delivery of that workflow arrives,
possibly on another node.

A message may be correlated before the subscription waiting for it exists. Do not treat "no
subscription" as an error unless your BPMS does.

`deployResources` of every adapter of a workflow module completes before any
`startWorkflowProcessing` runs. On the way down, extensions stop first and adapters last. Both
orders are held by `DeploymentServiceTest#extensionWiringServicesAreStoppedBeforeAdapters` and
`MultiAdapterDeploymentTest#bothAdaptersDeployAndStart`, with `ShutdownReverseOrderTest` for the
way down against a booted application.

## 6. Registering your adapter per platform

The dummy adapters of this repository are the templates, one per platform. They are small, they
are exercised by the platform's own tests, and they are kept current by those tests:

* Spring Boot: `spring-boot-integration/integration-tests/dummy-adapter`
* Quarkus: `quarkus-integration/integration-tests/dummy-adapter`

Which bean goes where on each platform, and which interfaces you never implement yourself, is drawn
under [What the platform hands an adapter](./README.md#what-the-platform-hands-an-adapter-adaptercollaborators).

### Spring Boot

Two auto-configurations, listed in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

The first one announces your adapter type. It extends `AdapterConfigurationBase`, runs before the
platform validates the configured types, and declares nothing else, because it has to be
constructible very early.

The second one registers your per-id beans through a `BeanRegistrar`. Use
`AdapterBeanRegistrarSupport.forEachConfiguredAdapterId(environment, ADAPTER_TYPE, …)`: the id set
comes from the runtime configuration, which is not bound into configuration-properties beans yet
at registration time, and that helper binds the core tree off the environment for you. It also
derives the ids the core derives by convention, which a naive read of the configured sections
would miss.

For each id, register one element bean of your `MigratableProcessService` and one of your
`AdapterDeploymentService`, named after the id, and take the collaborators from
`AdapterBeanRegistrarSupport.collaborators(supplierContext, adapterId)`.

Never register a bean of type `List<AdapterDeploymentService<…>>` on Spring Boot. The platform
collects element beans through `ObjectProvider` streams, and a list bean breaks that collection as
soon as a second adapter type is on the classpath, which is precisely the migration setup.

### Quarkus

Your adapter is a Quarkus extension of its own, with a runtime module and a deployment module.

The runtime module's `META-INF/quarkus-extension.yaml` declares `dependencies: [vanillabp]` and,
under `capabilities.provides`, the capability `io.vanillabp.adapter.<adapterType>`, where the
suffix equals your adapter type exactly. The VanillaBP extension validates that, independently of
your groupId. The extension's `name` can be anything; the convention is `vanillabp-<type>`.

The deployment module produces a `FeatureBuildItem` and two build items announcing your producers:
`VanillaBpMigratableProcessServiceBuildItem` and `VanillaBpAdapterDeploymentServiceBuildItem`,
each carrying your adapter type and the class name of the producer. The VanillaBP extension
registers the announced bean classes, so you do not register them again.

The runtime module holds those producers. Unlike Spring Boot, the platform expects one
`@Singleton` producer method returning a `List` with one instance per configured adapter id. Two
conventions come with that and both are part of the contract. The list's element type is the SPI
interface with both type parameters written literally as `Object`, whatever your model and context
classes are, because CDI's matching of differing type arguments is not reliable across modes and
the platform looks the beans up by the exact type. And the producer is `@Singleton` rather than
normal-scoped, because a deployment service usually has no no-arg constructor and is therefore not
client-proxyable.

Build the collaborators with
`AdapterCollaboratorsSupport.collaborators(adapterId, …)` from the runtime support module.

Your configuration overlay is a runtime `@ConfigRoot @ConfigMapping(prefix = "vanillabp")` in the
runtime module. Two things about it will cost you a day if you learn them the hard way. Every key
your adapter reads has to be modeled in that mapping, because the platform no longer ignores
unknown keys under `vanillabp.*`, which is how a typo becomes a startup failure. And never
`@Inject` a `@ConfigMapping` interface: injecting it turns the mapping into a static-init one, and
the whole `vanillabp.*` tree is then validated before any adapter extension registered its runtime
overlay, so every adapter-specific key fails the startup with a message pointing at the key rather
than at the injection which caused it. Read the mapping instead, through
`ConfigProvider.getConfig().unwrap(SmallRyeConfig.class).getConfigMapping(...)`.

### On both platforms

The authoritative set of adapter ids is always the platform's core properties. Your overlay maps
are per-known-id lookups only, never iterated to discover ids: environment-variable overrides can
materialize map entries which no configuration ever declared, and an id which exists only in an
overlay is invisible to the platform's validation.

## 7. What an adapter repository brings

Your repository is yours, and this section describes what the adapters in this workspace look like
so that a reader of one recognizes the next.

The module layout mirrors the platform's split. A `core` module in plain Java holds the SPI
implementations and all of your BPMS logic, with no Spring or Quarkus imports. A `spring-boot`
module and a `quarkus` module (itself split into `runtime` and `deployment`) only construct and
register what `core` defines. If you find yourself writing BPMS logic in a platform module, it
belongs in `core`.

Documentation is split the opposite way round from version 1. The wiki is user-facing and carries
two kinds of content: the configuration of your adapter, including what a BPMN model has to look
like for your BPMS and how much of a workflow aggregate reaches it, and the deviations, one
sentence per gap between your BPMS and VanillaBP's platform-wide contract. The `README.md` is
contributor-facing and carries the rationale, the alternatives you considered and the SPI
mechanics. A deliberate mode which conforms fully is not a deviation; document it with the
configuration which enables it.

`DECISIONS.md` holds the numbered decisions several places in your repository rely on, and it is
the only thing your code is allowed to cite, in the plain form `see decision 7 in the repository's
DECISIONS.md`. A citation into another repository's log does not resolve; a decision spanning two
repositories gets an entry in each, stated from that repository's side. An entry is superseded
rather than edited, keeps its number and names its successor, because a citation which shipped in
an older release still points at it.

Name what your BPMS cannot do, in a `GAPS.md` or on a deviations page, and let the deployment fail
where a missing capability would otherwise produce a workflow without an aggregate.

`UPGRADE.md` records every breaking change with the reasoning behind it, so an application knows
what a version bump costs.

Release lines are needed only where the client library you compile against decides the lowest BPMS
version your build accepts. Camunda 8 is that case: the client is the minimum cluster version, so
every bugfix would otherwise be deliverable only together with a cluster upgrade, and the adapter
therefore builds one artifact per cluster minor from one source tree. Where the engine is embedded
or sits behind a stable API, a pinned version and a documented range in the README is the whole
answer. If you do need lines, build them as profiles over one tree rather than as maintenance
branches, keep the VanillaBP-facing API identical on every line, and write into your README that a
growing per-line delta means the scheme has turned into a branch scheme and should be split
deliberately.

Testing follows one rule which is easy to state and easy to skip. Your user is the migration
adapter, so test at the SPI boundary against your real BPMS: the deployment pipeline, the two
phases, the probes. A developer-level end-to-end test through `ProcessService` is needed only
exemplarily, because most user-facing behaviour is already covered by the platform's own tests.
The one place that rule is inverted is the platforms: run your exemplary end-to-end flow on Spring
Boot AND on Quarkus, against the real engine, because a correct neutral core says nothing about
whether a platform's glue ever calls it. Measure instruction coverage separately per platform and
never feed one platform's execution data into the other's report: a low number for a platform
names the features that platform never runs, and mixing the data destroys the only thing the
number is good for.

### Booting an application in a test without your BPMS

Some of what you have to test has nothing to do with your BPMS: that your extension registers, that
your configuration binds, that your bean reaches the application at all. Starting your engine for
those is expensive and it hides the thing you wanted to see.

For those tests VanillaBP publishes a BPMS double, an adapter which implements the whole SPI and
answers whatever the test told it to answer. On Spring Boot it is one artifact:

```xml
<dependency>
  <groupId>io.vanillabp</groupId>
  <artifactId>bpms-double-spring-boot</artifactId>
  <version>2.0.0</version>
  <scope>test</scope>
</dependency>
```

On Quarkus it is the extension pair, and both belong in the module which runs the tests:

```xml
<dependency>
  <groupId>io.vanillabp</groupId>
  <artifactId>bpms-double-quarkus</artifactId>
  <version>2.0.0</version>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>io.vanillabp</groupId>
  <artifactId>bpms-double-quarkus-deployment</artifactId>
  <version>2.0.0</version>
  <scope>test</scope>
</dependency>
```

Configure it as an adapter of the type `dummy` and the application boots. What the double answers,
how a test steers it and what about it is promised across releases is in
[`bpms-double/README.md`](../bpms-double/README.md).

This does not replace the tests against your own BPMS. Everything about YOUR adapter is proven
against the real thing; the double is for the tests where a BPMS is in the way.

## 8. The checklist before your first pull request

1. One `MigratableProcessService` and one `AdapterDeploymentService` per configured adapter id,
   and two ids of your type refused at boot where nothing tells them apart. Configuration under
   `vanillabp.adapters.<id>.*`, validated at startup with messages naming the keys to add, and an
   unconfigured application still boots.
2. The pipeline in order: `readBpmn`, then `prepareBpmn` rewriting once per file, then `wireBpmn`
   with the wiring calls, then `deployResources` ending with `registerDeployedVersion` per process,
   then `startWorkflowProcessing` and `stopWorkflowProcessing`. Every name-clash mode either served
   or refused with a message. The module-level checks which follow the deployment are the core's
   and you call none of them; the only thing which comes back to you there is
   `processVersionCatalogOf`, answering for an id the application declares without deploying it,
   scoped the way you scope every other id. Those declared ids are also yours to ask about, with
   `taskWiringOfProcessesNobodyDeployed`, wherever your BPMS hands the work of a renamed
   process out under a name your subscriptions do not carry.
3. A handler per operation your BPMS can serve, and only the operations which allow it left out.
   Phase one asks, phase two acts, idempotently, throwing on anything but "already gone".
4. Probes scoped, never advancing, `UNKNOWN_TO_BPMS` and `BPMS_UNAVAILABLE` mapped honestly, the
   redispatch probe never optimistic, a visibility delay reported where your reads lag, and
   `canLocateWorkflows()` answered `false` where your BPMS cannot be asked about a workflow at all.
5. Inbound contexts carrying the delivery id, the activation id, the adapter id and the process
   version, and identifiers unscoped on the way in. Act on the outcome, send the shared values plus
   the variable named after the aggregate's id with every command, and never report a task as
   completed after an exception.
6. Permanent phase-two failures classified narrowly, your shutdown policy written down, your
   inbound threads bounded.
7. Your gaps written down honestly, and a deployment which fails where a missing capability would
   produce a workflow without an aggregate.
8. Both platforms, with the exemplary end-to-end flow running on each of them against your real
   BPMS.

## Where to look next

The SPI's own javadoc is the reference, and it is more detailed than this document in every place
the two overlap. Start with `MigratableProcessService`, whose type javadoc carries the election
and two-phase contracts in full.

[`README.md`](./README.md) of this module is the contributor documentation of the core: what the
election does with your answers, how the outbox dispatches, what the platform hands you and why.
Read it when you want to know why the SPI looks the way it does.

[`DECISIONS.md`](../DECISIONS.md) of this repository is where the reasoning lives which several
places rely on. This document points at entries 3, 4, 10, 17, 19, 26, 27, 28, 29 and 30.

If something here is wrong, or if you need a promise this SPI does not make, tell us. The SPI was
finalised before an adapter written outside this repository existed, precisely so that the shape
you implement against is the one we are willing to live with.
