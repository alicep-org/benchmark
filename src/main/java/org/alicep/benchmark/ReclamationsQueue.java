package org.alicep.benchmark;

import static com.google.common.base.Preconditions.checkState;
import static java.lang.management.ManagementFactory.getGarbageCollectorMXBeans;
import static org.alicep.benchmark.Notifications.addNotificationListener;
import static org.alicep.benchmark.Notifications.getCollectionId;
import static org.alicep.benchmark.Notifications.getFreedMemory;
import static org.alicep.benchmark.Notifications.isCollectionNotification;
import static org.alicep.benchmark.Notifications.removeNotificationListener;

import java.io.Closeable;
import java.lang.management.GarbageCollectorMXBean;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import javax.management.ListenerNotFoundException;

class ReclamationsQueue implements Closeable {

  /**
   * Returns a queue of all reclamations made by {@code collector} from {@code pool}.
   */
  public static ReclamationsQueue create(String collector, String pool) {
    GarbageCollectorMXBean collectorBean = getGarbageCollectorMXBeans()
        .stream()
        .filter(bean -> bean.getName().equals(collector))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(collector + "not enabled"));
    checkState(Arrays.asList(collectorBean.getMemoryPoolNames()).contains(pool), "%s pool not found", pool);
    return new ReclamationsQueue(collectorBean, pool);
  }

  private final BlockingQueue<Object> notifications = new LinkedBlockingDeque<>();
  private final GarbageCollectorMXBean collectorBean;
  private final String pool;
  private long lastCollection;
  private final Object listener;

  private ReclamationsQueue(GarbageCollectorMXBean collectorBean, String pool) {
    this.collectorBean = collectorBean;
    this.pool = pool;
    this.lastCollection = collectorBean.getCollectionCount();
    listener = addNotificationListener(collectorBean, notifications::add);
  }

  /**
   * Returns whether any collection results are available.
   */
  public boolean hasNext() {
    return lastCollection != collectorBean.getCollectionCount();
  }

  /**
   * Returns the amount of memory reclaimed by the next enqueued collection result.
   *
   * <p>Eden space is always fully reclaimed (any live objects move to the survivor space), but in general returned
   * values can be positive or negative.
   *
   * @throws IllegalStateException if no collection occurred since the last one dequeued
   * @throws AssertionError if the internal listener is not reporting back collection results in a timely manner
   */
  public long nextReclaimed() throws InterruptedException {
    checkState(lastCollection != collectorBean.getCollectionCount());
    Object notification;
    do {
      notification = notifications.poll(2, TimeUnit.SECONDS);
      if (notification == null) {
        throw new AssertionError("BlockingCollectionQueue listener not responding");
      }
    } while (!isCollectionNotification(notification));

    lastCollection = getCollectionId(notification);
    return getFreedMemory(notification, pool);
  }

  /**
   * Returns the amount of memory reclaimed by the most recent collection.
   *
   * @throws IllegalStateException if no collection occurred since the last one dequeued
   * @throws AssertionError if the internal listener is not reporting back collection results in a timely manner
   */
  public long lastReclaimed() throws InterruptedException {
    long collection = collectorBean.getCollectionCount();
    checkState(lastCollection != collection);
    Object notification;
    do {
      do {
        notification = notifications.poll(2, TimeUnit.SECONDS);
        if (notification == null) {
          throw new AssertionError("BlockingCollectionQueue listener not responding");
        }
      } while (!isCollectionNotification(notification));
      lastCollection = getCollectionId(notification);
    } while (lastCollection != collection);
    return getFreedMemory(notification, pool);
  }

  @Override
  public void close() {
    try {
      removeNotificationListener(collectorBean, listener);
    } catch (ListenerNotFoundException e) {
      // Close should be idempotent
    }
  }
}
