package org.alicep.benchmark;

import static java.util.Arrays.sort;
import static org.alicep.benchmark.Bytes.bytes;
import static org.alicep.benchmark.EdenMonitor.EDEN_USED_OVERHEAD_BYTES;

import java.util.Arrays;
import java.util.function.Supplier;

public class MemGauge {

  /**
   * Used to prevent HotSpot optimizing away unused objects. Volatile just to be certain.
   */
  @SuppressWarnings("unused")
  private static volatile Object sink;

  /**
   * Returns the memory allocated on the stack by {@code command}.
   *
   * <p>Accurate to the byte for smaller (&lt; 5KB) values.
   *
   * @param <E> checked exception thrown by command (RuntimeException if command is unchecked)
   * @param command command to run; should return any final result so HotSpot cannot optimize away any allocations
   * @return allocated memory in bytes
   *
   * @throws InterruptedException if interrupted
   * @throws E if {@code command} throws E
   */
  public static <E extends Exception>
      Bytes memoryConsumption(CheckedRunnable<E> command) throws E, InterruptedException {
    long[] estimates = new long[5];
    try (EdenMonitor monitor = EdenMonitor.create()) {
      // Java rounds all allocations to a multiple of Long.BYTES
      // Our measurements are rounded up to a multiple PENDING_OVERHEAD_BYTES
      // So repeat command a few times to get byte-level accuracy
      int repeats = EDEN_USED_OVERHEAD_BYTES / Long.BYTES;

      long initialUsed = monitor.used();
      for (int i = 0; i < estimates.length; i++) {
        for (int j = 0; j < repeats; ++j) {
          sink = command.run();
          sink = null;
        }
        long k = monitor.used();
        int numPendingCalls = 1;
        long finalUsed;

        // Keep calling used() until it ticks up, indicating we have completely consumed a page.
        do {
          finalUsed = monitor.used();
          numPendingCalls++;
        } while (finalUsed == k);

        // Calculate how much memory we consumed calling used()
        long overheadFromCallingUsed = EDEN_USED_OVERHEAD_BYTES * numPendingCalls;

        // Estimate how much memory command consumed
        estimates[i] = ((finalUsed - initialUsed - overheadFromCallingUsed - 16) / repeats) & ~7L;

        initialUsed = finalUsed;

        // If the data looks sketchy, take more samples
        if (i == 4) {
          Arrays.sort(estimates);
          if ((estimates[0] != estimates[1] && estimates[1] != estimates[2]) || estimates[1] < 0) {
            estimates = Arrays.copyOf(estimates, 25);
            System.gc();
            monitor.clear();
            initialUsed = monitor.used();
          }
        }
      }

      if (estimates.length > 5) {
        // Strip outliers and take the average of the rest
        Arrays.sort(estimates);
        try {
          return bytes((long) Arrays
              .stream(estimates)
              .skip(estimates.length / 5)
              .limit(2 * estimates.length / 5)
              .average()
              .getAsDouble());
        } catch (IllegalArgumentException e) {
          System.out.println(Arrays.toString(estimates));
          throw e;
        }
      }

      // Return the second-smallest result
      return bytes(estimates[1]);
    }
  }

  /**
   * Returns the memory used by an object.
   *
   * <pre>assertEquals(bytes(24), objectSize(Long::new));</pre>
   *
   * @param factory Provider of instances to measure
   * @return memory usage in bytes
   */
  public static Bytes objectSize(Supplier<?> factory) {
    MemoryAllocationMonitor monitor = MemoryAllocationMonitor.get();
    if (monitor.memoryUsed() == -1) {
      throw new AssertionError("Cannot measure memory on this JVM");
    }

    // Measure GCs with and without the object in question in.
    // Sometimes a GC will be unusually low; often it will be unusually high.
    // Keep going until the 1st quartile equals the median for both sets of measurements.
    int head = 0;
    int tail = 7;
    long[] without = new long[tail];
    long[] with = new long[tail];
    long[] differences = new long[tail];
    do {
      for (int i = head; i < tail; ++i) {
        @SuppressWarnings("unused")
        Object obj = factory.get();
        long memory1 = monitor.memoryUsed();
        long memory2 = monitor.memoryUsed();
        obj = null;
        long memory3 = monitor.memoryUsed();
        without[i] = memory2 - memory1;
        with[i] = memory3 - memory2;
      }
      sort(without);
      sort(with);

      // If the second quartiles are constant, take the difference between the medians
      long q1Without = without[tail / 4];
      long medianWithout = without[tail / 2];
      long q1With = with[tail / 4];
      long medianWith = with[tail / 2];
      if (q1Without == medianWithout && q1With == medianWith) {
        return bytes(medianWith - medianWithout);
      }

      // If the majority of differences are the same, return that
      if (differences.length > 30) {
          for (int i = 0; i < differences.length; ++i) {
              differences[i] = with[i] - without[i];
          }
          sort(differences);
          for (long i = 0, last = 0, count = 0; i < differences.length; ++i) {
              if (differences[(int) i] == last) {
                  count++;
                  if (count >= differences.length / 2) {
                      return bytes(last);
                  }
              } else {
                  last = differences[(int) i];
                  count = 1;
              }
          }
      }

      if (tail % 100 == 3) {
          System.out.println("Number without after " + tail + " iterations:");
          System.out.println(Arrays.toString(without));
          System.out.println();
          System.out.println("Number with after " + tail + " iterations:");
          System.out.println(Arrays.toString(with));
          System.out.println();
      }
      if (tail >= 1000) {
          throw new AssertionError("Did not stabilize after 1k iterations:\n"
                  + Arrays.toString(with) + "\n" + Arrays.toString(without));
      }
      head = tail;
      tail = tail + 4;
      without = Arrays.copyOf(without, tail);
      with = Arrays.copyOf(with, tail);
      differences = Arrays.copyOf(differences, tail);
    } while (true);
  }
}
