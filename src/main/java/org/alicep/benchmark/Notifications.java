package org.alicep.benchmark;

import static java.util.Arrays.stream;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;

import javax.management.ListenerNotFoundException;

class Notifications {

  public static Object addNotificationListener(GarbageCollectorMXBean bean, Consumer<Object> notificationConsumer) {
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

  public static boolean isCollectionNotification(Object notification) {
    return "com.sun.management.gc.notification".equals(Notifications.get(notification, "type"));
  }

  public static long getCollectionId(Object notification) {
    return (long) Notifications.get(notification, "userData", "gcInfo", "id");
  }

  public static long getFreedMemory(Object notification, String pool) {
    long before = (long) Notifications.get(notification, "userData", "gcInfo", "memoryUsageBeforeGc", pool, "used");
    long after = (long) Notifications.get(notification, "userData", "gcInfo", "memoryUsageAfterGc", pool, "used");
    return before - after;
  }

  public static void removeNotificationListener(GarbageCollectorMXBean bean, Object listener)
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
}
