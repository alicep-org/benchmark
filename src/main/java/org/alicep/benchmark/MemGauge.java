package org.alicep.benchmark;

import static java.util.Arrays.sort;
import static org.alicep.benchmark.Bytes.bytes;

import java.util.Arrays;

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
      System.gc();
      monitor.sample();

      // Java rounds all allocations to a multiple of Long.BYTES
      // Our measurements are rounded up to a multiple PENDING_OVERHEAD_BYTES
      // So repeat command a few times to get byte-level accuracy
      int repeats = EdenMonitor.SAMPLE_ERROR_BYTES / Long.BYTES;

      for (int i = 0; i < estimates.length; i++) {
        for (int j = 0; j < repeats; ++j) {
          sink = command.run();
          sink = null;
        }

        // Estimate how much memory command consumed
        estimates[i] = (monitor.sample() / repeats) & ~7L;

        // If the data looks sketchy, take more samples
        if (i == 4) {
          Arrays.sort(estimates);
          if ((estimates[0] != estimates[1] && estimates[1] != estimates[2]) || estimates[1] < 0) {
            estimates = Arrays.copyOf(estimates, 25);
            System.gc();
            monitor.sample();
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
   *
   * @throws InterruptedException if interrupted
   * @throws E if {@code factory} throws E
   */
  public static <E extends Exception> Bytes objectSize(CheckedRunnable<E> factory) throws E, InterruptedException {
    try (ReclamationsQueue reclamations = ReclamationsQueue.create("PS MarkSweep", "PS Old Gen")) {

      int head = 0;
      int tail = 8;
      long[] measurements = new long[tail];
      do {
        while (head < tail) {
          Object obj = factory.run();
          System.gc();
          measurements[head++] = -reclamations.lastReclaimed();
          sink = obj;
          sink = null;
          obj = null;
          System.gc();
          measurements[head++] = reclamations.lastReclaimed();
        }

        // If the majority of differences are the same, return that
        sort(measurements, 0, tail);
        for (long i = 0, last = 0, count = 0; i < tail; ++i) {
          if (measurements[(int) i] == last) {
            count++;
            if (count >= tail / 2) {
              return bytes(last);
            }
          } else {
            last = measurements[(int) i];
            count = 1;
          }
        }

        if (tail >= 1024) {
          throw new AssertionError("Did not stabilize after 1k iterations:\n" + Arrays.toString(measurements));
        }
        tail = tail + 8;
        if (measurements.length < tail) {
          measurements = Arrays.copyOf(measurements, measurements.length * 2);
        }
      } while (true);
    }
  }
}
