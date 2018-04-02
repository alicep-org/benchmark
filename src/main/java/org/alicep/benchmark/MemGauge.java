package org.alicep.benchmark;

import static org.alicep.benchmark.Bytes.bytes;

import java.util.Arrays;
import java.util.function.IntFunction;

public class MemGauge {

  /**
   * Returns the memory used by an object.
   *
   * <pre>assertEquals(bytes(24), measureMemoryUsage(Long::new));</pre>
   *
   * @param factory Provider of instances to measure
   * @return Memory usage in bytes
   */
  public static Bytes measureMemoryUsage(IntFunction<?> factory) {
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
    long medianWith;
    long medianWithout;
    do {
      for (int i = head; i < tail; ++i) {
        @SuppressWarnings("unused")
        Object obj = factory.apply(i);
        long memory1 = monitor.memoryUsed();
        long memory2 = monitor.memoryUsed();
        obj = null;
        long memory3 = monitor.memoryUsed();
        without[i] = memory2 - memory1;
        with[i] = memory3 - memory2;
      }
      Arrays.sort(without);
      Arrays.sort(with);
      long q1Without = without[tail / 4];
      medianWithout = without[tail / 2];
      long q1With = with[tail / 4];
      medianWith = with[tail / 2];
      if (q1Without == medianWithout && q1With == medianWith) {
        break;
      }
      if (tail % 1000 == 0) {
          System.out.println("Number without after " + tail + " iterations:");
          System.out.println(Arrays.toString(without));
          System.out.println();
          System.out.println("Number with after " + tail + " iterations:");
          System.out.println(Arrays.toString(with));
          System.out.println();
      }
      if (tail >= 10000) {
          throw new AssertionError("Did not stabilize after 10k iterations");
      }
      head = tail;
      tail = tail + 4;
      without = Arrays.copyOf(without, tail);
      with = Arrays.copyOf(with, tail);
    } while (true);

    // Take the difference between the medians
    return bytes(medianWith - medianWithout);
  }
}
