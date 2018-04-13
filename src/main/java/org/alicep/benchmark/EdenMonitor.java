package org.alicep.benchmark;

import static com.google.common.base.Preconditions.checkState;
import static java.lang.management.ManagementFactory.getGarbageCollectorMXBeans;

import java.io.Closeable;
import java.lang.management.GarbageCollectorMXBean;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;

class EdenMonitor implements Closeable, NotificationListener {

  private static final String POOL = "PS Eden Space";

  private static class Collection {
    long id;
    long freed;

    Collection(long id, long freed) {
      this.id = id;
      this.freed = freed;
    }
  }

  @SuppressWarnings("restriction")
  private static final NotificationFilter ONLY_GC_NOTIFICATIONS = notification ->
      notification.getType().equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION);

  public static EdenMonitor create() throws InterruptedException {
    GarbageCollectorMXBean scavengeBean = getGarbageCollectorMXBeans()
        .stream()
        .filter(bean -> bean.getName().equals("PS Scavenge"))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("ParallelSweep GC not enabled"));
    checkState(Arrays.asList(scavengeBean.getMemoryPoolNames()).contains(POOL), "Eden pool not found");
    EdenMonitor monitor = new EdenMonitor(scavengeBean);
    ((NotificationEmitter) scavengeBean).addNotificationListener(monitor, ONLY_GC_NOTIFICATIONS, null);
    monitor.reset();
    return monitor;
  }

  private final GarbageCollectorMXBean scavengeBean;
  private final BlockingQueue<Collection> collections = new LinkedBlockingDeque<>();
  private long lastSeen;

  private EdenMonitor(GarbageCollectorMXBean scavengeBean) {
    this.scavengeBean = scavengeBean;
    this.lastSeen = scavengeBean.getCollectionCount();
  }

  public void reset() throws InterruptedException {
    System.gc();
    while (hasNext()) {
      nextFreed();
    }
  }

  public long freed() throws InterruptedException {
    System.gc();
    long freed = 0;
    while (hasNext()) {
      freed += nextFreed();
    }
    return freed;
  }

  @Override
  public void close() {
    try {
      ((NotificationEmitter) scavengeBean).removeNotificationListener(this);
    } catch (ListenerNotFoundException e) {
      // Close should be idempotent
    }
  }

  private boolean hasNext() {
    return lastSeen != scavengeBean.getCollectionCount();
  }

  private long nextFreed() throws InterruptedException {
    Collection collection = collections.take();
    lastSeen = collection.id;
    return collection.freed;
  }

  @Override
  @SuppressWarnings("restriction")
  public void handleNotification(Notification notification, Object handback) {
    CompositeData data = (CompositeData) notification.getUserData();
    GcInfo info = GarbageCollectionNotificationInfo.from(data).getGcInfo();
    long before = info.getMemoryUsageBeforeGc().get(POOL).getUsed();
    long after = info.getMemoryUsageAfterGc().get(POOL).getUsed();
    collections.add(new Collection(info.getId(), before - after));
  }
}
