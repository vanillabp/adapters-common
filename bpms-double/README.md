![Header](../readme/vanillabp-headline.png)

# The BPMS double

An adapter which pretends to be a business process management system. It implements the whole
adapter SPI, logs what it was asked and answers whatever the test told it to answer, so an
application boots and runs a workflow in a test without an engine anywhere near it.

This is the same code the platform integration tests itself with. Nothing here is a second
implementation written for outside users: what an outsider gets is what the platform's own test
suites run against on every build, and that is the reason it is published at all (see decision 33
in the repository's DECISIONS.md).

- [What it is for](#what-it-is-for)
- [Adding it to a Spring Boot application](#adding-it-to-a-spring-boot-application)
- [Adding it to a Quarkus application](#adding-it-to-a-quarkus-application)
- [Configuring it](#configuring-it)
- [Steering it: the hooks](#steering-it-the-hooks)
- [Playing the BPMS: calling in](#playing-the-bpms-calling-in)
- [What this artifact promises](#what-this-artifact-promises)
- [What it does not promise](#what-it-does-not-promise)

## What it is for

You are writing something that lives next to VanillaBP rather than inside it: an election cache, a
persistence store, an extension of the deployment pipeline, a library your application registers.
Your test needs an application which boots with VanillaBP wired up, and without this artifact the
cheapest way to get one is to depend on a real BPMS adapter and hope its engine starts quietly.

The double gives you the same application without the engine. It deploys every BPMN file it finds
and accepts every workflow operation, and it hands your test the wire in both directions: the test
can call in as if a task had been delivered, and it can watch what VanillaBP sent out.

It is not a BPMN engine. No model is executed and no token moves. Where the thing you are testing
needs a process to really run, you want a real adapter instead.

## Adding it to a Spring Boot application

One dependency, in test scope:

```xml
<dependency>
  <groupId>io.vanillabp</groupId>
  <artifactId>bpms-double-spring-boot</artifactId>
  <version>2.0.0</version>
  <scope>test</scope>
</dependency>
```

The auto-configuration registers itself, so nothing else is needed to make the application boot.

## Adding it to a Quarkus application

Two dependencies, in test scope, and they belong in the module which RUNS the tests. For an
extension of your own that is the deployment module, because a Quarkus extension is tested from
there:

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

The deployment artifact has to be named. Quarkus resolves the deployment half of an extension from
the runtime jar's descriptor when the extension is on the application's own classpath, but a
test-scoped extension is not, so the build has to be told.

Both artifacts, and the core they share, carry their versions in
`io.vanillabp:vanillabp-bom`, so an application importing that BOM writes no version here.

## Configuring it

The double is an adapter of the type `dummy`, and it is configured where every VanillaBP adapter is:

```yaml
vanillabp:
  adapters:
    test:
      type: dummy
```

Any number of ids may carry that type. Two of them is how a test plays a migration from one BPMS to
another, and each id gets its own pair of beans.

Two properties switch on behaviour a logging double would otherwise not have:

|                  Property                   |                                                                                                                                What it does                                                                                                                                |
|---------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `dummy-adapter.at-least-once-delivery`      | The double reports the delivery behaviour of a BPMS which repeats a task whose outcome it never learned. Turn it on to exercise the delivery record and an outbox.                                                                                                         |
| `dummy-adapter.read-aggregate-in-phase-two` | The double loads the workflow aggregate while phase two runs, the way an adapter of a remote BPMS does because it builds the variables it sends out of the aggregate. Off by default, because most test doubles of `AggregatePersistenceAware` implement nothing but save. |

## Steering it: the hooks

The double holds no state of its own. Everything it answers comes from beans your test declares,
each of them an interface with `default` methods for everything but its one core question, so a
later VanillaBP release can add a question without breaking what you wrote.

|              Hook               |                                                                       What it answers                                                                        |
|---------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `DummyTaskWiringSource`         | Which BPMN tasks a process has, and which processes a file holds. The double parses nothing, so this is the model.                                           |
| `DummyBpmsInitiatedStartSource` | Which start events the BPMS fires on its own, so `@WorkflowStartedByBpms` methods are validated against something.                                           |
| `DummyProcessVersionSource`     | Which versions of a process the BPMS has deployed, so a version range or a version tag resolves.                                                             |
| `DummyHealthSource`             | What the adapter contributes to the health endpoint, including an exception.                                                                                 |
| `DummyTaskAwarenessSource`      | Whether this BPMS knows a task, a user task or a workflow. This is what drives the election across several adapters, and the visibility delay after a start. |
| `DummyViewerSource`             | The process definitions, the BPMN XML and the workflow history the viewer API asks for.                                                                      |
| `DummyPhaseTwoListener`         | Watches every phase-two operation, and fails one by throwing.                                                                                                |
| `DummyDeploymentListener`       | Watches every call of the deployment pipeline, with the adapter id, the method, the workflow module and a detail.                                            |

Declaring one is declaring a bean. On Spring Boot that is a `@Bean` or a `@Component` in the test's
own context, on Quarkus a CDI bean; several beans of one hook type are allowed and the double asks
them in the order the platform resolves them.

A test which needs a hook to change its answer between two test methods puts that state into the
hook bean and resets it there. The double itself has no reset, because it has nothing to reset.

## Playing the BPMS: calling in

The deployment service is the hand your test puts on the BPMS. It offers three methods no adapter
SPI declares, and they are what a test calls to make something arrive from the outside:

- `invokeTask(workflowModuleId, bpmnProcessId, context)` runs a `@WorkflowTask` method through the
  core, the way a real adapter does when its BPMS delivers a task, and returns the outcome.
- `startWorkflowByBpms(workflowModuleId, bpmnProcessId, context)` reports a workflow the BPMS
  started on its own and returns the aggregate id plus the variables to write back.
- `notifyWorkflowEnded(workflowModuleId, bpmnProcessId, context)` reports that a workflow ended.

On Spring Boot the instance is a bean named `DummyAdapter_DeploymentService_<adapterId>`:

```java
final var bpms = context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);
```

On Quarkus the deployment services arrive as one CDI bean of type
`List<AdapterDeploymentService<Object, Object>>`, so a test filters that list for a
`DummyDeploymentService` with the adapter id it wants.

The process service is registered the same way, as `DummyAdapter_ProcessService_<adapterId>` on
Spring Boot, but a test rarely needs it: what it does is observed through `DummyPhaseTwoListener`.

## What this artifact promises

Publishing turns three things a test can see into a contract. They are listed here so that a test
which relies on one of them is relying on something written down rather than on something noticed.

### The log lines

Every line the double writes starts with `Dummy-Adapter[` plus the adapter id plus `]: `, at level
INFO. The wording after that prefix is promised for these:

```
Dummy-Adapter[test]: Reading BPMN 'Sample.bpmn' for my-module
Dummy-Adapter[test]: Reading DMN 'Decision.dmn' for my-module
Dummy-Adapter[test]: Preparing BPMN 'Sample.bpmn' for my-module
Dummy-Adapter[test]: Wiring BPMN process 'Sample' for my-module
Dummy-Adapter[test]: Deploying resources for my-module
Dummy-Adapter[test]: Starting workflow processing for my-module
Dummy-Adapter[test]: Stopping workflow processing for my-module
Dummy-Adapter[test]: Invoking task 'the-task' of my-module
```

The process service logs one line per operation and phase, reading
`Dummy-Adapter[test]: Starting workflow (phase one) of BPMN process 'Sample' of workflow module 'my-module'`
and its phase-two counterpart, with `Completing task`, `Canceling task`, `Completing user task`,
`Canceling user task`, `Broadcasting signal`, `Pushing the changed aggregate`, `Correlating message`
and `Starting workflow by message` in the same shape. The awareness probes log
`Checking awareness of task` and `Checking awareness of workflow`.

### The bean names

`DummyAdapter_DeploymentService_<adapterId>` and `DummyAdapter_ProcessService_<adapterId>` on
Spring Boot, with the three call-in methods described above.

### What the pipeline does with a file

The double reads a BPMN file as one process whose id is the file's path below the workflow module's
BPMN location with `.bpmn` stripped, so `sub/Second.bpmn` becomes the process `sub/Second` and two
files of the same name in different directories stay apart. A `DummyTaskWiringSource` which names
processes for that file overrides this, which is how a file holding a second executable process is
modelled.

The model object and the processing context are plain `Object` instances carrying nothing.
`readDmn` passes the context through, and `prepareBpmn` passes an existing one through and creates
one where there is none.

## What it does not promise

The double is written for tests and nothing stops it from running in production, because a library
which refuses to run outside a test would have to know it is in one and there is no honest way to
ask. It is meant for test scope, every example here says so, and an application which ships it does
so on its own judgement.

Nothing about the number of log lines, their order relative to lines other components write, or the
wording of anything not listed above is promised. Neither is the class layout: which class a hook
lives in, which constructor the double has and how the platform modules build it are free to change.

A future release adds a fluent API for steering the double from one place in a test instead of one
bean per hook. The hooks keep working underneath it.
