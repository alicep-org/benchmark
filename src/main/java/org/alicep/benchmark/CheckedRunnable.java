package org.alicep.benchmark;

public interface CheckedRunnable<E extends Exception> {
  Object run() throws E;
}
