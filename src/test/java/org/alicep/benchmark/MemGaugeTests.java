package org.alicep.benchmark;

import static org.alicep.benchmark.Bytes.bytes;
import static org.alicep.benchmark.MemGauge.memoryConsumption;
import static org.alicep.benchmark.MemGauge.objectSize;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

public class MemGaugeTests {

  @Test
  public void objectSize_long() {
    assertEquals(bytes(24), objectSize(() -> new Long(10000)));
  }

  @Test
  public void objectSize_string() {
    assertEquals(bytes(56), objectSize(() -> String.format("%08x", 10)));
  }

  @Test
  public void objectSize_byteArray() {
    // Round up to a multiple of 4 and add 16 bytes of header (object header + size)
    assertEquals(bytes(24), objectSize(() -> new byte[5]));
  }

  @Test
  public void objectSize_evenSizedPointerArray() {
    // 4 bytes per pointer and a 16-bit header (object header + size)
    assertEquals(bytes(56), objectSize(() -> new String[10]));
  }

  @Test
  public void objectSize_oddSizedPointerArray() {
    // Round up to a multiple of 2, 4 bytes per pointer and a 16-bit header (object header + size)
    assertEquals(bytes(80), objectSize(() -> new String[15]));
  }

  @Test
  public void memoryConsumption_doNothing() throws InterruptedException {
    assertEquals(bytes(0), memoryConsumption(() -> null));
  }

  @Test
  public void memoryConsumption_allocateOneByteArray() throws InterruptedException {
    // Round up to a multiple of 4 and add 16 bytes of header (object header + size)
    assertEquals(bytes(24), memoryConsumption(() -> {
      // Easy for HotSpot to optimize away; MemGauge needs to be careful not to let that happen
      return new byte[5];
    }));
  }

  @Test
  public void memoryConsumption_allocateTwoByteArrays() throws InterruptedException {
    // Round up to a multiple of 4 and add 16 bytes of header (object header + size)
    assertEquals(bytes(2 * 24), memoryConsumption(() -> {
      byte[] bytes = new byte[5];
      bytes[2] = 3;
      return Arrays.copyOf(bytes, 5);
    }));
  }
}
