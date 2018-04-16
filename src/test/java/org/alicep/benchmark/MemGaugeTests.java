package org.alicep.benchmark;

import static org.alicep.benchmark.Bytes.bytes;
import static org.alicep.benchmark.MemGauge.memoryConsumption;
import static org.alicep.benchmark.MemGauge.objectSize;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.management.MemoryUsage;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class MemGaugeTests {

  @Test
  public void objectSize_null() throws InterruptedException {
    assertEquals(bytes(0), objectSize(() -> null));
  }

  @Test
  public void objectSize_object() throws InterruptedException {
    assertEquals(bytes(allocation(0)), objectSize(() -> new Object()));
  }

  @Test
  public void objectSize_objectArray() throws InterruptedException {
    for (int i = 0; i < 9; ++i) {
      int size = i;
      assertEquals("new Object[" + i + "]",
          bytes(allocation(4 * i)),
          objectSize(() -> new Object[size]));
    }
  }

  @Test
  public void objectSize_2dObjectArray() throws InterruptedException {
    for (int i = 0; i < 3; ++i) {
      for (int j = 0; j < 3; ++j) {
        int width = i;
        int breadth = j;
        assertEquals("new Object[" + i + "][" + j + "]",
            bytes(allocation(4 * i) + i * allocation(4 * j)),
            objectSize(() -> new Object[width][breadth]));
      }
    }
  }

  @Test
  public void objectSize_long() throws InterruptedException {
    assertEquals(bytes(allocation(Long.BYTES)), objectSize(() -> new Long(10000)));
  }

  @Test
  public void objectSize_string() throws InterruptedException {
    for (int i = 2; i < 10; ++i) {
      int size = i;
      assertEquals(i + "-character String",
          bytes(allocation(Integer.BYTES + 4) + allocation(Character.BYTES * i)),
          objectSize(() -> String.format("%0" + size + "x", 10)));
    }
  }

  @Test
  public void objectSize_byteArray() throws InterruptedException {
    for (int i = 0; i < 19; ++i) {
      int size = i;
      assertEquals("new byte[" + i + "]",
          bytes(allocation(i)),
          objectSize(() -> new byte[size]));
    }
  }

  @Test
  public void objectSize_reportsResidentNotAllocatedMemory() throws InterruptedException {
    assertEquals("allocateByteArrays(32)", bytes(allocation(5)), objectSize(() -> allocateByteArrays(32)));
  }

  @Test
  public void objectSize_throwsIfResidentMemoryChanges() {
    AtomicInteger size = new AtomicInteger();
    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() -> objectSize(() -> new long[size.getAndIncrement()]))
        .withMessageStartingWith("Did not stabilize after 1k iterations");
  }

  @Test
  public void edenUsedOverheadBytes_correct() throws InterruptedException {
    assertEquals(bytes(EdenMonitor.SAMPLE_ERROR_BYTES), objectSize(() -> new MemoryUsage(0, 0, 0, 0)));
  }

  @Test
  public void memoryConsumption_doNothing() throws InterruptedException {
    assertEquals(bytes(0), memoryConsumption(() -> null));
  }

  @Test
  public void memoryConsumption_allocateOneByteArray() throws InterruptedException {
    // Round up to a multiple of 4 and add 16 bytes of header
    assertEquals(bytes(24), memoryConsumption(() -> {
      // Easy for HotSpot to optimize away; MemGauge needs to be careful not to let that happen
      return new byte[5];
    }));
  }

  @Test
  public void memoryConsumption_byteArrays() throws InterruptedException {
    for (int i = 0; i < 9000; i = i + (i >> 4) + 1) {
      int size = i;
      try {
        assertEquals(i + " byte[5]s", bytes(i * 24), memoryConsumption(() -> allocateByteArrays(size)));
      } catch (IllegalArgumentException e) {
        throw new AssertionError("Failed to measure " + i + " byte[5]s", e);
      }
    }
  }

  @Test
  public void memoryConsumption_allocateTwoMillionByteArrays() throws InterruptedException {
    long used = memoryConsumption(() -> allocateByteArrays(2_000_000)).asLong();
    assertTrue("~48MB (was " + used + ")", used > 47_950_000 && used < 48_050_000);
  }

  @Test
  public void memoryConsumption_allocateEightMillionByteArrays() throws InterruptedException {
    long used = memoryConsumption(() -> allocateByteArrays(8_000_000)).asLong();
    assertTrue("~192MB (was " + used + ")", used > 191_500_000 && used < 192_500_000);
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

  private static int allocation(int usedBytes) {
    return 16 + roundUpToAMultipleOf(8, usedBytes);
  }

  private static int roundUpToAMultipleOf(int modulo, int value) {
    return modulo * (Math.floorDiv(value - 1, modulo) + 1);
  }
}
