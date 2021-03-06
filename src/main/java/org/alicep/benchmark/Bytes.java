package org.alicep.benchmark;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Map;

import com.google.common.collect.ImmutableMap;

public class Bytes implements Comparable<Bytes> {

  public static Bytes bytes(long bytes) {
    checkArgument(bytes >= 0);
    return new Bytes(bytes);
  }

  public static Bytes kilobytes(double kilobytes) {
    checkArgument(kilobytes >= 0);
    return new Bytes((long) (kilobytes * 1_000));
  }

  public static Bytes megabytes(double megabytes) {
    checkArgument(megabytes >= 0);
    return new Bytes((long) (megabytes * 1_000_000));
  }

  public static Bytes gigabytes(double gigabytes) {
    checkArgument(gigabytes >= 0);
    return new Bytes((long) (gigabytes * 1_000_000_000));
  }

  public static Bytes petabytes(double gigabytes) {
    checkArgument(gigabytes >= 0);
    return new Bytes((long) (gigabytes * 1_000_000_000_000L));
  }

  public static Bytes exabytes(double gigabytes) {
    checkArgument(gigabytes >= 0);
    return new Bytes((long) (gigabytes * 1_000_000_000_000_000L));
  }

  private final long bytes;

  private Bytes(long bytes) {
    this.bytes = bytes;
  }

  public long asLong() {
    return bytes;
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof Bytes)) {
      return false;
    }
    return bytes == ((Bytes) obj).bytes;
  }

  @Override
  public int hashCode() {
    return Long.hashCode(bytes);
  }

  @Override
  public int compareTo(Bytes o) {
    return Long.compare(bytes, o.bytes);
  }

  private static final Map<Integer, String> CONSTRUCTORS = ImmutableMap.<Integer, String>builder()
      .put(0, "")
      .put(1, "kilobytes")
      .put(2, "megabytes")
      .put(3, "gigabytes")
      .put(4, "terabytes")
      .put(5, "petabytes")
      .put(6, "exabytes")
      .build();

  String suggestedConstructor() {
    checkArgument(bytes >= 0);
    if (bytes < 995) return "Bytes.bytes(" + bytes + ")";
    double scaled = bytes;
    int scale = 0;
    while (scaled >= 999) {
      scaled /= 1000;
      scale += 1;
    }
    String significand;
    if (scaled < 9.995) {
      significand = String.format("%.2f", scaled);
    } else if (scaled < 99.95) {
      significand = String.format("%.1f", scaled);
    } else {
      significand = Long.toString(Math.round(scaled * 10) / 10);
    }

    return String.format("Bytes.%s(%s)", CONSTRUCTORS.get(scale), significand);
  }

  private static final Map<Integer, String> SCALES = ImmutableMap.<Integer, String>builder()
      .put(0, "")
      .put(1, "k")
      .put(2, "M")
      .put(3, "G")
      .put(4, "T")
      .put(5, "P")
      .put(6, "E")
      .build();

  @Override
  public String toString() {
    checkArgument(bytes >= 0);
    if (bytes < 995) return bytes + "B";
    double scaled = bytes;
    int scale = 0;
    while (scaled >= 999) {
      scaled /= 1000;
      scale += 1;
    }
    String significand;
    if (scaled < 9.995) {
      significand = String.format("%.2f", scaled);
    } else if (scaled < 99.95) {
      significand = String.format("%.1f", scaled);
    } else {
      significand = Long.toString(Math.round(scaled * 10) / 10);
    }

    return String.format("%s%sB", significand, SCALES.get(scale));
  }
}
