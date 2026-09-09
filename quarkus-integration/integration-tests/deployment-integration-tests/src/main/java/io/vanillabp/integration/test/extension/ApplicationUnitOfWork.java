package io.vanillabp.integration.test.extension;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import io.vanillabp.integration.spi.TransactionRunner;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A transaction runner an APPLICATION brings, the shape used where the aggregates live
 * in a store the platform does not manage. It runs the work as it is and counts what it
 * was asked for, which is all a test about the resolution needs.
 * <p>
 * That it is a bean of type {@link TransactionRunner} is the point: the platform's own
 * runner is a bean too, and only because that one is restricted to its concrete class
 * does this one stay the single bean of the SPI type.
 */
@ApplicationScoped
public class ApplicationUnitOfWork implements TransactionRunner {

  private final AtomicInteger inCurrentCalls = new AtomicInteger();

  private final AtomicInteger requireNewCalls = new AtomicInteger();

  @Override
  public <T> T requireNew(
      final Supplier<T> work) {

    requireNewCalls.incrementAndGet();
    return work.get();

  }

  @Override
  public <T> T inCurrent(
      final Supplier<T> work) {

    inCurrentCalls.incrementAndGet();
    return work.get();

  }

  @Override
  public boolean isRollbackOnly() {

    return false;

  }

  /**
   * @return How often the extension asked to run in the caller's transaction
   */
  public int getInCurrentCalls() {

    return inCurrentCalls.get();

  }

  /**
   * @return How often a new transaction was asked for
   */
  public int getRequireNewCalls() {

    return requireNewCalls.get();

  }

}
