package org.alicep.benchmark;

import static java.lang.management.ManagementFactory.getMemoryPoolMXBeans;

import java.io.Closeable;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;

class EdenMonitor implements Closeable {

  /**
   * The size of a {@link MemoryUsage} instance.
   */
  public static int SAMPLE_ERROR_BYTES = 16 + 4 * Long.BYTES;

  private static final String COLLECTOR = "PS Scavenge";
  private static final String POOL = "PS Eden Space";

  public static EdenMonitor create() throws InterruptedException {
    ReclamationsQueue reclamations = ReclamationsQueue.create(COLLECTOR, POOL);
    MemoryPoolMXBean edenPool = getMemoryPoolMXBeans()
        .stream()
        .filter(bean -> bean.getName().equals(POOL))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Eden pool not found"));
    EdenMonitor monitor = new EdenMonitor(reclamations, edenPool);
    monitor.sample();
    return monitor;
  }

  private final ReclamationsQueue reclamations;
  private final MemoryPoolMXBean edenPool;
  private long lastUsed;

  private EdenMonitor(ReclamationsQueue reclamations, MemoryPoolMXBean edenPool) {
    this.reclamations = reclamations;
    this.edenPool = edenPool;
  }

  /**
   * Returns the amount of stack used since construction, or the last call to {@link #sample()}.
   *
   * <p>Only accurate to within {@link #SAMPLE_ERROR_BYTES} at the best of times.
   */
  public long sample() throws InterruptedException {
    return measureUnreclaimed() + reclaimed();
  }

  @Override
  public void close() {
    reclamations.close();
  }

  private long measureUnreclaimed() {
    long firstUsed = edenPool.getUsage().getUsed();
    int numUsageCalls = 1;
    long finalUsed;

    // Keep calling getUsage() until it changes value, indicating we have completely consumed a page.
    do {
      finalUsed = edenPool.getUsage().getUsed();
      numUsageCalls++;
    } while (finalUsed == firstUsed);
    long unreclaimed = finalUsed - lastUsed - SAMPLE_ERROR_BYTES * numUsageCalls - 16;
    lastUsed = finalUsed;
    return unreclaimed;
  }

  private long reclaimed() throws InterruptedException {
    long totalReclaimed = 0;
    while (reclamations.hasNext()) {
      totalReclaimed += reclamations.nextReclaimed();
    }
    return totalReclaimed;
  }
}
