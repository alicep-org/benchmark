package org.alicep.benchmark;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Map;

import com.google.common.collect.ImmutableMap;

class Nanos {

  private static final Map<Integer, String> SCALES = ImmutableMap.<Integer, String>builder()
      .put(0, "s")
      .put(-3, "ms")
      .put(-6, "μs")
      .put(-9, "ns")
      .put(-12, "ps")
      .build();

  public static String foramtNanos(long nanos) {
    return formatNanos((double) nanos);
  }

  public static String formatNanos(double nanos) {
    checkArgument(nanos >= 0);
    if (nanos == 0) return "0s";
    double timePerAttempt = nanos;
    int scale = -9; // nanos
    while (timePerAttempt < 1.0) {
      timePerAttempt *= 1000;
      scale -= 3;
    }
    while (timePerAttempt >= 999 && scale < 0) {
      timePerAttempt /= 1000;
      scale += 3;
    }
    String significand;
    if (timePerAttempt < 9.995) {
      significand = String.format("%.2f", timePerAttempt);
    } else if (timePerAttempt < 99.95) {
      significand = String.format("%.1f", timePerAttempt);
    } else {
      significand = Long.toString(Math.round(timePerAttempt));
    }

    if (Nanos.SCALES.containsKey(scale)) {
      return String.format("%s %s", significand, Nanos.SCALES.get(scale));
    } else {
      return String.format("%se%d s", significand, scale);
    }
  }
}
