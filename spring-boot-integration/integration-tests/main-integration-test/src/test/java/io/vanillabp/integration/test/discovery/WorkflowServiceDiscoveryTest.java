package io.vanillabp.integration.test.discovery;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.DefaultResourceLoader;

import io.vanillabp.adapter.dummy.springboot.DummyAdapterConfiguration;
import io.vanillabp.adapter.dummy.springboot.deployment.DummyTaskWiringSource;
import io.vanillabp.adapter.dummy.springboot.processservice.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.TestTransactionRunnerConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.deployment.DeploymentTest;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.spi.process.ProcessService;

/**
 * How VanillaBP finds the workflow services of a Spring Boot application: it asks the
 * bean definitions, so a workflow service is found because it is a bean, no matter which
 * JAR its class arrived in and no matter who registered the bean.
 * <p>
 * The tests boot applications whose workflow service cannot be found by looking at
 * classes: one arrives with the auto-configuration of a library, one carries no
 * annotation of its own, and one belongs to a profile which is not active. The last one
 * is the case the discovery deliberately says nothing about - a class annotated
 * {@code @WorkflowService} without a bean is indistinguishable from one another profile
 * brings - and its BPMN process is deployed with nothing to serve it. The application
 * starts and the deployment reports the process, which is what a process no workflow
 * service claims costs; see
 * {@link io.vanillabp.integration.test.deployment.UnclaimedBpmnProcessTest} for the file
 * which carries such a process on purpose.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowServiceDiscoveryTest {

  static final String PROFILE_WITH_HANDLERS = "with-handlers";

  /**
   * The classes every application here is built of: the dummy adapter standing in for a
   * BPMS, the platform, and persistence doubles. The BPMN files and the workflow module
   * marker come from the test resources ('test-module', one process 'DummyProcess').
   */
  private static final List<Class<?>> PLATFORM = List.of(
      DummyAdapterConfiguration.class,
      DummyAdapterProcessServiceConfiguration.class,
      WorkflowModuleAutoConfiguration.class,
      SpringBootMigrationAdapterAutoConfiguration.class,
      TestPersistenceConfiguration.class, TestPhaseTwoOutboxConfiguration.class,
      TestTransactionRunnerConfiguration.class,
      WorkflowModuleConfiguration.class,
      DeploymentTest.TestConfig.class);

  /**
   * Imports the library's auto-configuration the way Spring Boot reads the ones on the
   * classpath: deferred, i.e. after every configuration class of the application.
   */
  @Configuration
  @ImportAutoConfiguration(LibraryWorkflowServiceAutoConfiguration.class)
  static class ApplicationUsingTheLibrary {

  }

  /**
   * Stands in for the BPMN model of 'DummyProcess': one task, which
   * {@link ProfiledWorkflowService} has the handler of.
   */
  @Configuration
  static class DummyProcessWithOneTask {

    @Bean
    DummyTaskWiringSource taskWiringSource() {

      return (
          adapterId,
          workflowModuleId,
          bpmnProcessId) -> "DummyProcess".equals(bpmnProcessId)
              ? List.of(new BpmnTaskSpec("Activity_Process", "processTask"))
              : List.of();

    }

  }

  private SpringApplicationBuilder application(
      final Class<?>... additionalClasses) {

    final var classes = new java.util.LinkedList<>(PLATFORM);
    classes.addAll(List.of(additionalClasses));
    return new SpringApplicationBuilder(classes.toArray(Class[]::new));

  }

  private static ProcessService<?> processServiceOf(
      final ConfigurableApplicationContext context,
      final Class<?> workflowAggregateClass) {

    return (ProcessService<?>) context
        .getBeanProvider(ResolvableType.forClassWithGenerics(ProcessService.class, workflowAggregateClass))
        .getObject();

  }

  @Test
  public void aWorkflowServiceOfALibraryIsFound() {

    try (var context = application(ApplicationUsingTheLibrary.class).run()) {

      // nothing about LibraryWorkflowService is reachable by a component scan of this
      // application, and its bean definition arrives after every configuration class
      Assertions.assertNotNull(
          processServiceOf(context, DiscoveryAggregate.class),
          "the workflow service of the library got no ProcessService");
      Assertions.assertNotNull(
          context.getBean(LibraryWorkflowService.class),
          "the library's workflow service is no bean");

    }

  }

  @Test
  public void aWorkflowServiceInheritingItsAnnotationIsFound() {

    try (var context = application(InheritedWorkflowService.class).run()) {

      // the annotation sits on the superclass, which is no bean itself
      Assertions.assertNotNull(
          processServiceOf(context, InheritedAggregate.class),
          "the inherited @WorkflowService got no ProcessService");

    }

  }

  @Test
  public void aWorkflowServiceOfAnInactiveProfileLeavesItsProcessUnclaimed(
      final CapturedOutput output) {

    // with the profile: the handler of 'processTask' is a bean, so the model deployed
    // is completely wired
    try (var context = application(ProfiledWorkflowService.class, DummyProcessWithOneTask.class)
        .profiles(PROFILE_WITH_HANDLERS)
        .run()) {

      Assertions.assertNotNull(
          processServiceOf(context, ProfiledAggregate.class),
          "the workflow service of the active profile got no ProcessService");

    }

    // what the boot above already wrote - only the output of the second one answers
    // the question
    final var writtenBeforeThisBoot = output.getAll().length();

    // without the profile: the class is on the classpath but no bean of it exists.
    // Nothing warns about the annotated class - the DEPLOYED model is what reports the
    // gap, as the process which nothing serves
    try (var context = application(ProfiledWorkflowService.class, DummyProcessWithOneTask.class)
        .run()) {

      final var captured = output.getAll().substring(writtenBeforeThisBoot);
      Assertions.assertTrue(
          captured.contains("process 'DummyProcess' of file 'DummyProcess.bpmn'"),
          "unexpected output: "
              + captured);
      Assertions.assertTrue(
          captured.contains("not get past its first task"),
          "unexpected output: "
              + captured);

    }

  }

  /**
   * A class loader recording every request for a class resource - the enumeration of the
   * classpath roots a scan starts with, and every single {@code .class} file read from
   * it.
   */
  static class ClassResourceRecordingClassLoader extends ClassLoader {

    private final List<String> requests = new CopyOnWriteArrayList<>();

    ClassResourceRecordingClassLoader(
        final ClassLoader parent) {

      super(parent);

    }

    List<String> getRequests() {

      return List.copyOf(requests);

    }

    private void record(
        final String name) {

      if (name.isEmpty() || "/".equals(name) || name.endsWith(".class")) {
        requests.add(name);
      }

    }

    @Override
    public URL getResource(
        final String name) {

      record(name);
      return super.getResource(name);

    }

    @Override
    public Enumeration<URL> getResources(
        final String name) throws IOException {

      record(name);
      return super.getResources(name);

    }

    @Override
    public InputStream getResourceAsStream(
        final String name) {

      record(name);
      return super.getResourceAsStream(name);

    }

  }

  @Test
  public void theBootReadsNoClassResourcesToFindTheWorkflowServices() {

    final var recording = new ClassResourceRecordingClassLoader(
        Thread.currentThread().getContextClassLoader());
    final var contextClassLoader = Thread.currentThread().getContextClassLoader();

    // both ways to a resource loader lead through this class loader: the one the
    // application is built with, and the one a scan constructing its own resolver
    // would fall back to
    Thread.currentThread().setContextClassLoader(recording);
    try (var context = application(ProfiledWorkflowService.class, DummyProcessWithOneTask.class)
        .profiles(PROFILE_WITH_HANDLERS)
        .resourceLoader(new DefaultResourceLoader(recording))
        .run()) {

      Assertions.assertNotNull(
          processServiceOf(context, ProfiledAggregate.class),
          "the workflow service was not found at all");

    } finally {
      Thread.currentThread().setContextClassLoader(contextClassLoader);
    }

    // a whole-classpath scan asks for the roots ("") and then reads one resource per
    // class it finds - 40 000 of them in an application of a decent size
    Assertions.assertEquals(
        List.of(),
        recording.getRequests().stream().filter(name -> !name.endsWith(".class")).toList(),
        "the boot enumerated the classpath roots");

    // what stays is Spring reading the metadata of the configuration classes this
    // application NAMED, which is why it cannot grow with the classpath: every class
    // resource read belongs to a class the application handed to SpringApplication
    final var namedClasses = classesOfTheApplication(
        ProfiledWorkflowService.class, DummyProcessWithOneTask.class);
    Assertions.assertEquals(
        List.of(),
        recording
            .getRequests()
            .stream()
            .filter(name -> namedClasses.stream().noneMatch(name::startsWith))
            .toList(),
        "the boot read class resources of classes it was not given");

  }

  /**
   * The resource paths of the classes the application was built of, nested classes
   * included (a nested class' resource path starts with the enclosing class' one).
   */
  private List<String> classesOfTheApplication(
      final Class<?>... additionalClasses) {

    final var classes = new java.util.LinkedList<>(PLATFORM);
    classes.addAll(List.of(additionalClasses));
    return classes
        .stream()
        .map(clazz -> clazz.getName().replace('.', '/'))
        .toList();

  }

}
