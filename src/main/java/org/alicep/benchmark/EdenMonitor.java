package org.alicep.benchmark;

import static com.google.common.base.Preconditions.checkState;
import static java.lang.management.ManagementFactory.getGarbageCollectorMXBeans;
import static java.util.Arrays.stream;

import java.io.Closeable;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationListener;

class EdenMonitor implements Closeable, NotificationListener {

  private static final String POOL = "PS Eden Space";

  public static EdenMonitor create() throws InterruptedException {
    GarbageCollectorMXBean scavengeBean = getGarbageCollectorMXBeans()
        .stream()
        .filter(bean -> bean.getName().equals("PS Scavenge"))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("ParallelSweep GC not enabled"));
    checkState(Arrays.asList(scavengeBean.getMemoryPoolNames()).contains(POOL), "Eden pool not found");
    EdenMonitor monitor = new EdenMonitor(scavengeBean);
    monitor.listener = addNotificationListener(scavengeBean, monitor::notify);
    monitor.reset();
    return monitor;
  }

  private final GarbageCollectorMXBean scavengeBean;
  private final BlockingQueue<Object> notifications = new LinkedBlockingDeque<>();
  private Object listener;
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
      removeNotificationListener(scavengeBean, listener);
    } catch (ListenerNotFoundException e) {
      // Close should be idempotent
    }
  }

  private void notify(Object notification) {
    notifications.add(notification);
  }

  private boolean hasNext() {
    return lastSeen != scavengeBean.getCollectionCount();
  }

  private long nextFreed() throws InterruptedException {
    Object notification;
    do {
      notification = notifications.poll(2, TimeUnit.SECONDS);
      if (notification == null) {
        throw new AssertionError("EdenMonitor listener not responding");
      }
    } while (!"com.sun.management.gc.notification".equals(get(notification, "type")));

    Object gcInfo = get(notification, "userData", "gcInfo");
    long id = (long) get(gcInfo, "id");
    long before = (long) get(gcInfo, "memoryUsageBeforeGc", POOL, "used");
    long after = (long) get(gcInfo, "memoryUsageAfterGc", POOL, "used");
    lastSeen = id;
    return before - after;
  }

  @Override
  public void handleNotification(Notification notification, Object handback) {
    notifications.add(notification);
  }

  private static Object addNotificationListener(GarbageCollectorMXBean bean, Consumer<Object> notificationConsumer) {
    Method addMethod = stream(bean.getClass().getMethods())
        .filter(method -> method.getName().equals("addNotificationListener") && method.getParameterCount() == 3)
        .findAny()
        .orElseThrow(() -> new IllegalStateException("Cannot add gc listener"));
    Class<?> listenerType = addMethod.getParameters()[0].getType();
    if (!listenerType.isInterface()) {
      throw new IllegalStateException("Cannot add gc listener");
    }
    try {
      Object listener = Proxy.newProxyInstance(
          listenerType.getClassLoader(),
          new Class<?>[] { listenerType },
          new ListenerHandler(notificationConsumer));
      addMethod.setAccessible(true);
      addMethod.invoke(bean, listener, null, null);
      return listener;
    } catch (InvocationTargetException e) {
      throw new IllegalStateException("Cannot add gc listener", e.getCause());
    } catch (Exception e) {
      throw new IllegalStateException("Cannot add gc listener", e);
    }
  }

  private static void removeNotificationListener(GarbageCollectorMXBean bean, Object listener)
      throws ListenerNotFoundException {
    Method removeMethod = stream(bean.getClass().getMethods())
        .filter(method -> method.getName().equals("removeNotificationListener") && method.getParameterCount() == 1)
        .findAny()
        .orElseThrow(() -> new IllegalStateException("Cannot remove gc listener"));
    try {
      removeMethod.setAccessible(true);
      removeMethod.invoke(bean, listener);
    } catch (InvocationTargetException e) {
      if (e.getCause().getClass().getSimpleName().equals("ListenerNotFoundException")) {
        throw new ListenerNotFoundException(e.getMessage());
      }
      throw new IllegalStateException("Cannot remove gc listener", e.getCause());
    } catch (Exception e) {
      throw new IllegalStateException("Cannot remove gc listener", e);
    }
  }

  private static class ListenerHandler implements InvocationHandler {

    private final Consumer<Object> notificationConsumer;

    ListenerHandler(Consumer<Object> notificationConsumer) {
      this.notificationConsumer = notificationConsumer;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      if (method.getName().equals("toString") && method.getParameterCount() == 0) {
        return "EdenMonitor notification listener";
      } else if (method.getName().equals("hashCode") && method.getParameterCount() == 0) {
        return this.hashCode();
      } else if (method.getName().equals("equals") && method.getParameterCount() == 1) {
        Object other = args[0];
        return proxy == other;
      } else if (method.getName().equals("handleNotification") && method.getParameterCount() == 2) {
        Object notification = args[0];
        notificationConsumer.accept(notification);
        return null;
      } else {
        throw new UnsupportedOperationException(method.getName());
      }
    }
  }

  private static Object get(Object object, String... path) {
    Object result = object;
    for (String key : path) {
      try {
        try {
          Method method = result.getClass().getMethod("get", String.class);
          result = method.invoke(result, key);
        } catch (NoSuchMethodException e1) {
          try {
            Method method = result.getClass().getMethod("get", Object[].class);
            result = method.invoke(result, (Object) new Object[] { key });
            method = result.getClass().getMethod("get", String.class);
            result = method.invoke(result, "value");
          } catch (NoSuchMethodException e2) {
            String getterName = "get" + key.substring(0, 1).toUpperCase() + key.substring(1);
            Method method = result.getClass().getMethod(getterName);
            result = method.invoke(result);
          }
        }
      } catch (ReflectiveOperationException e) {
        throw new RuntimeException(e);
      }
    }
    return result;
  }
}
