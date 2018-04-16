package org.alicep.benchmark;

import static java.util.Arrays.stream;

import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.stream.LongStream;

public class MemoryAssertions {

  /**
   * Fluent API for asserting how much memory is allocated by {@code runnable}.
   *
   * <p>Successful tests will typically execute {@code runnable} 31 times, though extreme flakiness may trigger further
   * runs. Unsuccessful tests will run around twelve hundred times and include a recommendation for a non-flaky
   * assertion that could be used in future.
   *
   * <p>To avoid flakiness, tests will only fail if 5 samples (each averaging 6 executions) all fail the input
   * assertion.
   *
   * @param runnable the method to test
   * @return fluent API instance
   */
  public static MemoryAssertions assertThatRunning(ThrowingRunnable runnable) {
    return new MemoryAssertions(runnable).runOnce();
  }

  /**
   * Used to prevent HotSpot optimizing away unused objects. Volatile just to be certain.
   */
  @SuppressWarnings("unused")
  private static volatile Object sink;

  private final ThrowingRunnable runnable;
  private long[] allocations = new long[5];
  private int runs = 0;

  private MemoryAssertions(ThrowingRunnable runnable) {
    this.runnable = runnable;
  }

  /**
   * Assert the runnable makes no stack allocations.
   *
   * @return this fluent API instance
   */
  public MemoryAssertions makesNoStackAllocations() {
    sample();
    if (allocations.length == 5 && allocations[1] != 0) {
      allocations = Arrays.copyOf(allocations, 25);
      sample();
    }
    if (allocations.length == 25 && allocations[14] != 0) {
      allocations = Arrays.copyOf(allocations, 200);
      sample();
    }
    if (allocations.length == 200 && allocations[160] != 0) {
      StringBuilder message = new StringBuilder("Expected no stack allocations");
      suggestCheck(message);
      throw new AssertionError(message);
    }
    return this;
  }

  /**
   * Assert the runnable allocates {@code bytes} (with a 1% margin of error).
   *
   * @param bytes the number of bytes the runnable is expected to allocate
   * @return this fluent API instance
   */
  public MemoryAssertions allocates(Bytes bytes) {
    sample();
    if (allocations.length == 5 && !matches(allocations[1], bytes)) {
      allocations = Arrays.copyOf(allocations, 25);
      sample();
    }
    if (allocations.length == 25 && !(matches(allocations[6], bytes) && matches(allocations[14], bytes))) {
      allocations = Arrays.copyOf(allocations, 200);
      sample();
    }
    if (allocations.length == 200 && !(matches(allocations[40], bytes) && matches(allocations[160], bytes))) {
      StringBuilder message = new StringBuilder("Expected ").append(bytes).append(" to be allocated");
      suggestCheck(message);
      throw new AssertionError(message);
    }
    return this;
  }

  /**
   * Assert the runnable allocates at most {@code bytes} (plus a 1% margin of error).
   *
   * @param bytes the maximum number of bytes the runnable is expected to allocate
   * @return this fluent API instance
   */
  public MemoryAssertions allocatesAtMost(Bytes bytes) {
    sample();
    if (allocations.length == 5 && !atMost(allocations[1], bytes)) {
      allocations = Arrays.copyOf(allocations, 25);
      sample();
    }
    if (allocations.length == 25 && !atMost(allocations[14], bytes)) {
      allocations = Arrays.copyOf(allocations, 200);
      sample();
    }
    if (allocations.length == 200 && !atMost(allocations[160], bytes)) {
      StringBuilder message = new StringBuilder("Expected at most ").append(bytes).append(" to be allocated");
      suggestCheck(message);
      throw new AssertionError(message);
    }
    return this;
  }

  /**
   * Assert the runnable allocates between {@code minBytes} and {@code maxBytes} (with a 1% margin of error).
   *
   * @param minBytes the minimum number of bytes the runnable is expected to allocate
   * @param maxBytes the maximum number of bytes the runnable is expected to allocate
   * @return this fluent API instance
   */
  public MemoryAssertions allocatesBetween(Bytes minBytes, Bytes maxBytes) {
    sample();
    if (allocations.length == 5 && !(atLeast(allocations[1], minBytes) && atMost(allocations[1], maxBytes))) {
      allocations = Arrays.copyOf(allocations, 25);
      sample();
    }
    if (allocations.length == 25 && !(atLeast(allocations[6], minBytes) && atMost(allocations[14], maxBytes))) {
      allocations = Arrays.copyOf(allocations, 200);
      sample();
    }
    if (allocations.length == 200 && !(atLeast(allocations[40], minBytes) && atMost(allocations[160], maxBytes))) {
      StringBuilder message = new StringBuilder("Expected ").append(minBytes).append("–").append(maxBytes)
          .append(" to be allocated");
      suggestCheck(message);
      throw new AssertionError(message);
    }
    return this;
  }

  private static boolean matches(long bytes, Bytes expected) {
    if (bytes <= 995) {
      return expected.asLong() == bytes;
    } else {
      long error = expected.asLong() - bytes;
      return Math.abs(error) < bytes * 0.01;
    }
  }

  private static boolean atMost(long bytes, Bytes expected) {
    if (bytes <= 995) {
      return expected.asLong() >= bytes;
    } else {
      return expected.asLong() >= bytes * 0.99;
    }
  }

  private static boolean atLeast(long bytes, Bytes expected) {
    if (expected.asLong() <= 995) {
      return expected.asLong() <= bytes;
    } else {
      return expected.asLong() * 0.99 <= bytes;
    }
  }

  private void suggestCheck(StringBuilder message) {
    Bytes min = Bytes.bytes(allocations[20]);
    Bytes max = Bytes.bytes(allocations[180]);
    message.append(" but ");
    if (allocations[180] == 0) {
      long percentageZero = LongStream.of(allocations).filter(n -> n <= 0).count() / 2;
      message.append(percentageZero).append("% of runs allocated no memory\n")
          .append("Consider using .makesNoStackAllocations()");
    } else if (allocations[20] == 0) {
      message.append("90% of runs used at most ").append(max).append("\n")
          .append("Consider using .allocatesAtMost(").append(max.suggestedConstructor()).append(")");
    } else if (allocations[20] == allocations[180]) {
      long percentageSame = stream(allocations).filter(x -> x == allocations[100]).count() / 2;
      message.append(percentageSame).append("% of runs allocated ").append(max).append("\n")
          .append("Consider using .allocates(").append(max.suggestedConstructor()).append(")");
    } else {
      message.append("80% of runs used between ").append(min).append(" and ").append(max).append("\n")
          .append("Consider using .allocatesBetween(").append(min.suggestedConstructor())
          .append(", ").append(max.suggestedConstructor()).append(")");
    }
  }

  private void sample() {
    if (runs == allocations.length) {
      return;
    }
    try (EdenMonitor monitor = EdenMonitor.create()) {
      monitor.sample();

      // Java rounds all allocations to a multiple of Long.BYTES
      // Our measurements are rounded up to a multiple of SAMPLE_ERROR_BYTES
      // So repeat command a few times to get byte-level accuracy
      int repeats = EdenMonitor.SAMPLE_ERROR_BYTES / Long.BYTES;

      for (; runs < allocations.length; runs++) {
        for (int j = 0; j < repeats; ++j) {
          sink = runnable.run();
          sink = null;
        }

        // Estimate how much memory command consumed
        allocations[runs] = (monitor.sample() / repeats) & ~7L;

        // If the data looks sketchy, take more samples
        if (runs == 4) {
          Arrays.sort(allocations);
          if ((allocations[0] != allocations[1] && allocations[1] != allocations[2]) || allocations[1] < 0) {
            allocations = Arrays.copyOf(allocations, 25);
            System.gc();
            monitor.sample();
          }
        }
      }
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      if (e instanceof InterruptedException || e instanceof InterruptedIOException) {
        Thread.currentThread().interrupt();
      }
      throw new AssertionError(e);
    }
    Arrays.sort(allocations);
    if (allocations.length == 200 && allocations[20] < 0) {
      throw new AssertionError("Too much noise; could not sample allocations");
    }
  }

  private MemoryAssertions runOnce() {
    try {
      runnable.run();
      System.gc();
      return this;
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      if (e instanceof InterruptedException || e instanceof InterruptedIOException) {
        Thread.currentThread().interrupt();
      }
      throw new AssertionError(e);
    }
  }
}
