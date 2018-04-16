package org.alicep.benchmark;

public interface CheckedRunnable<E extends Throwable> {
  Object run() throws E;
}
