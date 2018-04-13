package org.alicep.benchmark;

import static org.alicep.benchmark.Bytes.bytes;
import static org.alicep.benchmark.MemGauge.objectSize;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MemGaugeTests {

  @Test
  public void objectSize_long() {
    assertEquals(bytes(24), objectSize(Long::new));
  }

  @Test
  public void objectSize_string() {
    assertEquals(bytes(56), objectSize(i -> String.format("%08x", i)));
  }

  @Test
  public void objectSize_byteArray() {
    // Round up to a multiple of 4 and add 16 bits of header (object header + size)
    assertEquals(bytes(24), objectSize(i -> new byte[5]));
  }

  @Test
  public void objectSize_evenSizedPointerArray() {
    // 4 bytes per pointer and a 16-bit header (object header + size)
    assertEquals(bytes(56), objectSize(i -> new String[10]));
  }

  @Test
  public void objectSize_oddSizedPointerArray() {
    // Round up to a multiple of 2, 4 bytes per pointer and a 16-bit header (object header + size)
    assertEquals(bytes(80), objectSize(i -> new String[15]));
  }
}
