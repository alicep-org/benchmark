package org.alicep.benchmark;

import static org.alicep.benchmark.Bytes.bytes;
import static org.alicep.benchmark.Bytes.megabytes;
import static org.alicep.benchmark.MemoryAssertions.assertThatRunning;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class MemoryAssertionsTests {

  @Test
  public void typicallyRuns31Times() {
    AtomicInteger runs = new AtomicInteger(0);
    assertThatRunning(() -> {
      runs.incrementAndGet();
      return null;
    }).makesNoStackAllocations();
    assertThat(runs).hasValue(31);
  }

  @Test
  public void makesNoStackAllocations_succeedsWhenNoAllocationsMade() {
    assertThatRunning(() -> null).makesNoStackAllocations();
  }

  @Test
  public void makesNoStackAllocations_failsWhen40BytesAllocated() {
    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() -> assertThatRunning(() -> new long[3]).makesNoStackAllocations())
        .withMessageMatching("expected no stack allocations but ([89]\\d|100)% of runs allocated 40B\\n.*")
        .withMessageContaining("Consider using .allocates(Bytes.bytes(40))");
  }

  @Test
  public void makesNoStackAllocations_failsWhenUpTo40BytesAllocated() {
    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() -> assertThatRunning(allocateUpTo40Bytes()).makesNoStackAllocations())
        .withMessageMatching("expected no stack allocations but ([89]\\d|100)% of runs used at most 40B\\n.*")
        .withMessageContaining("Consider using .allocatesAtMost(Bytes.bytes(40))");
  }

  @Test
  public void makesNoStackAllocations_failsWhen16To40BytesAllocated() {
    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() -> assertThatRunning(allocate16To40Bytes()).makesNoStackAllocations())
        .withMessageMatching("expected no stack allocations but ([89]\\d|100)% of runs used between 16B and 40B\\n.*")
        .withMessageContaining("Consider using .allocatesBetween(Bytes.bytes(16), Bytes.bytes(40))");
  }

  @Test
  public void allocates40Bytes_failsWhenNoAllocationsMade() {
    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() -> assertThatRunning(() -> null).allocates(bytes(40)))
        .withMessageMatching("expected 40B to be allocated but ([89]\\d|100)% of runs allocated no memory\\n.*")
        .withMessageContaining("Consider using .makesNoStackAllocations()");
  }

  @Test
  public void allocates40Bytes_succeedsWhen40BytesAllocated() {
    assertThatRunning(() -> new long[3]).allocates(bytes(40));
  }

  @Test
  public void allocates40Bytes_failsWhen16To40BytesAllocated() {
    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() -> assertThatRunning(allocate16To40Bytes()).allocates(bytes(40)))
        .withMessageMatching("expected 40B to be allocated but ([89]\\d|100)% of runs used between 16B and 40B\\n.*")
        .withMessageContaining("Consider using .allocatesBetween(Bytes.bytes(16), Bytes.bytes(40))");
  }

  @Test
  public void allocates16Bytes_failsWhen16To40BytesAllocated() {
    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() -> assertThatRunning(allocate16To40Bytes()).allocates(bytes(16)))
        .withMessageMatching("expected 16B to be allocated but ([89]\\d|100)% of runs used between 16B and 40B\\n.*")
        .withMessageContaining("Consider using .allocatesBetween(Bytes.bytes(16), Bytes.bytes(40))");
  }

  @Test
  public void allocatesAtMost40Bytes_succeedsWhenNoBytesAllocated() {
    assertThatRunning(() -> null).allocatesAtMost(bytes(40));
  }

  @Test
  public void allocatesAtMost40Bytes_succeedsWhenUpTo40BytesAllocated() {
    assertThatRunning(allocateUpTo40Bytes()).allocatesAtMost(bytes(40));
  }

  @Test
  public void allocatesAtMost40Bytes_failsWhen48BytesAllocated() {
    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() -> assertThatRunning(() -> new long[4]).allocatesAtMost(bytes(40)))
        .withMessageMatching("expected at most 40B to be allocated but ([89]\\d|100)% of runs allocated 48B\\n.*")
        .withMessageContaining("Consider using .allocates(Bytes.bytes(48))");
  }

  @Test
  public void allocatesBetween16And32Bytes_failsWhenNoAllocationsMade() {
    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() -> assertThatRunning(() -> null).allocatesBetween(bytes(16), bytes(32)))
        .withMessageMatching("expected 16B–32B to be allocated but ([89]\\d|100)% of runs allocated no memory\\n.*")
        .withMessageContaining("Consider using .makesNoStackAllocations()");
  }

  @Test
  public void allocatesBetween16And32Bytes_succeedsWhen16BytesAllocated() {
    assertThatRunning(() -> new long[0]).allocatesBetween(bytes(16), bytes(32));
  }

  @Test
  public void allocatesBetween16And32Bytes_succeedsWhen32BytesAllocated() {
    assertThatRunning(() -> new long[2]).allocatesBetween(bytes(16), bytes(32));
  }

  @Test
  public void allocatesBetween16And32Bytes_failsWhen40BytesAllocated() {
    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() -> assertThatRunning(() -> new long[3]).allocatesBetween(bytes(16), bytes(32)))
        .withMessageMatching("expected 16B–32B to be allocated but ([89]\\d|100)% of runs allocated 40B\\n.*")
        .withMessageContaining("Consider using .allocates(Bytes.bytes(40))");
  }

  @Test
  public void allocatesBetween32And40Bytes_failsWhen16To40BytesAllocated() {
    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() -> assertThatRunning(allocate16To40Bytes()).allocatesBetween(bytes(32), bytes(40)))
        .withMessageMatching("expected 32B–40B to be allocated but ([89]\\d|100)% of runs used between 16B and 40B\\n.*")
        .withMessageContaining("Consider using .allocatesBetween(Bytes.bytes(16), Bytes.bytes(40))");
  }

  @Test
  public void oneFiveElementByteArrayConsumes24Bytes() {
    assertThatRunning(() -> new byte[5]).allocates(bytes(24));
  }

  @Test
  public void memoryConsumption_byteArrays() {
    for (int i = 0; i < 50000; i = i + (i >> 4) + 1) {
      int number = i;
      try {
        assertThatRunning(() -> allocateByteArrays(number))
            .allocates(bytes(i * 24))
            .allocatesBetween(bytes(Math.max((i - 1) * 24, 0)), bytes((i + 1) * 24));
      } catch (RuntimeException | AssertionError e) {
        throw new AssertionError("Failed to measure " + i + " byte[5]s", e);
      }
    }
  }

  @Test
  public void memoryConsumption_allocateTwoMillionByteArrays() {
    assertThatRunning(() -> allocateByteArrays(2_000_000)).allocates(megabytes(48));
  }

  @Test
  public void memoryConsumption_allocateEightMillionByteArrays() {
    assertThatRunning(() -> allocateByteArrays(8_000_000)).allocates(megabytes(192));
  }

  @Test
  public void returnsObjectConsuming0Bytes_passesWhenNullReturned() {
    assertThatRunning(() -> null).returnsObjectConsuming(bytes(0));
  }

  @Test
  public void returnsObjectConsuming0Bytes_failsWhenByteArrayReturned() {
    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() -> assertThatRunning(() -> new byte[5]).returnsObjectConsuming(bytes(0)))
        .withMessage("resident memory of returned object: expected 0B but was 24B");
  }

  @Test
  public void returnsObjectConsuming_usesDescriptionOnFailure() {
    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() ->
            assertThatRunning(() -> new byte[5]).describedAs("new byte[5]").returnsObjectConsuming(bytes(0)))
        .withMessage("new byte[5]: expected 0B but was 24B");
  }

  @Test
  public void returnsObjectConsuming24Bytes_failsWhenNullReturned() {
    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() -> assertThatRunning(() -> null).returnsObjectConsuming(bytes(24)))
        .withMessage("resident memory of returned object: expected 24B but was 0B");
  }

  @Test
  public void returnsObjectConsuming_measuresResidentNotAllocatedMemory() {
    assertThatRunning(() -> Arrays.copyOf(new long[15], 20)).returnsObjectConsuming(bytes(16 + Long.BYTES * 20));
  }

  @Test
  public void returnsObjectConsuming24Bytes_passesWhenByteArrayReturned() {
    assertThatRunning(() -> new byte[5]).returnsObjectConsuming(bytes(24));
  }

  private static Object allocateByteArrays(int allocations) {
    if (allocations == 0) {
      return null;
    }
    byte[] bytes = new byte[5];
    bytes[2] = 3;
    for (int i = 1; i < allocations; ++i) {
      bytes = Arrays.copyOf(bytes, 5);
    }
    return bytes;
  }

  /**
   * Simulates a horribly flaky test that allocates 0-40B.
   */
  private static ThrowingRunnable allocateUpTo40Bytes() {
    int repeatFor = EdenMonitor.SAMPLE_ERROR_BYTES / Long.BYTES;
    AtomicInteger nextSize = new AtomicInteger(repeatFor - 1);
    return () -> {
      int size = (nextSize.getAndIncrement() / repeatFor) % 4;
      if (size == 0) {
        return null;
      }
      return new long[size];
    };
  }

  /**
   * Simulates a horribly flaky test that allocates 16B-40B.
   */
  private static ThrowingRunnable allocate16To40Bytes() {
    int repeatFor = EdenMonitor.SAMPLE_ERROR_BYTES / Long.BYTES;
    AtomicInteger nextSize = new AtomicInteger(repeatFor - 1);
    return () -> {
      int size = (nextSize.getAndIncrement() / repeatFor) % 4;
      return new long[size];
    };
  }
}
