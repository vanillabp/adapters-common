package io.vanillabp.integration.test;

import java.sql.Connection;
import java.util.concurrent.atomic.AtomicLong;

import io.agroal.api.AgroalPoolInterceptor;
import jakarta.inject.Singleton;

/**
 * How often the application took a connection out of the pool, so a test can claim that it
 * did not touch its database at all.
 * <p>
 * A connection is what every statement needs, so counting connections is the claim from
 * below: zero connections taken is zero statements issued, whatever the code would have sent
 * on them. A test which measures TIME cannot make that claim - a poller sleeping and a poller
 * asking every second both look like "the workflow went through".
 * <p>
 * The count is static because this is a bean of the application under test while the test
 * which reads it lives outside.
 */
@Singleton
public class CountingPoolInterceptor implements AgroalPoolInterceptor {

  private static final AtomicLong ACQUIRED = new AtomicLong();

  @Override
  public void onConnectionAcquire(
      final Connection connection) {

    ACQUIRED.incrementAndGet();

  }

  /**
   * @return How many connections were taken since the last {@link #forgetWhatWasAcquired()}
   */
  public static long acquired() {

    return ACQUIRED.get();

  }

  public static void forgetWhatWasAcquired() {

    ACQUIRED.set(0);

  }

}
