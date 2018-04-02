package org.alicep.benchmark;

import static org.alicep.benchmark.Bytes.bytes;
import static org.alicep.benchmark.MemGauge.measureMemoryUsage;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MemGaugeTests {

  @Test
  public void testLong() {
    assertEquals(bytes(24), measureMemoryUsage(Long::new));
  }

  @Test
  public void testString() {
    assertEquals(bytes(56), measureMemoryUsage(i -> String.format("%08x", i)));
  }

  @Test
  public void testByteArray() {
    // Round up to a multiple of 4 and add 16 bits of header (object header + size)
    assertEquals(bytes(24), measureMemoryUsage(i -> new byte[5]));
  }

  @Test
  public void testEvenSizedPointerArray() {
    // 4 bytes per pointer and a 16-bit header (object header + size)
    assertEquals(bytes(56), measureMemoryUsage(i -> new String[10]));
  }

  @Test
  public void testOddSizedPointerArray() {
    // Round up to a multiple of 2, 4 bytes per pointer and a 16-bit header (object header + size)
    assertEquals(bytes(80), measureMemoryUsage(i -> new String[15]));
  }
}
