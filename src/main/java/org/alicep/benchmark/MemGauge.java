package org.alicep.benchmark;

import static java.util.Arrays.sort;
import static org.alicep.benchmark.Bytes.bytes;

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
   * @param <E> checked exception thrown by command (RuntimeException if command is unchecked)
   * @param command command to run; should return any final result so HotSpot cannot optimize away any allocations
   * @return allocated memory in bytes, to the nearest 1%
   *
   * @throws InterruptedException if interrupted
   * @throws E if {@code command} throws E
   */
  public static <E extends Exception>
      Bytes memoryConsumption(CheckedRunnable<E> command) throws E, InterruptedException {
    // How accurate our memory measurements are (empirically)
    long granularity = 1;

    try (EdenMonitor monitor = EdenMonitor.create()) {
      for (long iterations = 1; ; iterations *= 4) {
        monitor.reset();
        for (long i = 0; i < iterations; i++) {
          sink = command.run();
        }
        sink = null;
        long consumption = monitor.freed();

        sink = new byte[0];
        sink = null;
        long emptyConsumption = monitor.freed();

        granularity = Math.max(granularity, emptyConsumption);
        double lowerBound = (double)(consumption - granularity) / iterations;
        double upperBound = (double) consumption / iterations;
        if (upperBound < 0.05 || upperBound < lowerBound * 1.01) {
          return bytes(Math.round((lowerBound + upperBound) / 2));
        }
      }
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
