package org.alicep.benchmark;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.getOnlyElement;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static org.junit.runner.Description.createTestDescription;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.ParentRunner;
import org.junit.runners.model.FrameworkField;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.TestClass;

import com.google.common.collect.ImmutableMap;

public class BenchmarkRunner extends ParentRunner<Runner> {

  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface Benchmark {
    String value() default "";
  }

  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface InterferenceWarning {
    String value() default "This test tends to be unreliable";
  }

  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.FIELD)
  public @interface Configuration { }

  private final List<Runner> benchmarks;

  private static List<Runner> getBenchmarks(TestClass testClass) throws InitializationError {
    try {
      List<FrameworkMethod> methods = testClass.getAnnotatedMethods(Benchmark.class);
      testClass.getOnlyConstructor();
      FrameworkField configurationsField = getOnlyElement(testClass.getAnnotatedFields(Configuration.class), null);
      if (configurationsField != null) {
        return configuredBenchmarks(testClass, methods, configurationsField);
      } else {
        return unconfiguredBenchmarks(testClass, methods);
      }
    } catch (RuntimeException | IllegalAccessException e) {
      throw new InitializationError(e);
    }
  }

  @TargetError
  private static Description createSingleBenchmarkDescription(
      TestClass cls,
      FrameworkMethod method,
      Object configuration) {
    String name = method.getName() + ((configuration != null) ? " [" + configuration + "]" : "");
    return createTestDescription(cls.getName(), name);
  }

  private static List<Runner> unconfiguredBenchmarks(TestClass testClass, List<FrameworkMethod> methods) {
    List<Runner> benchmarks = new ArrayList<>();
    for (FrameworkMethod method : methods) {
      Description description = createSingleBenchmarkDescription(testClass, method, null);
      Supplier<LongUnaryOperator> hotLoopFactory = () -> BenchmarkCompiler.compileBenchmark(
          testClass.getJavaClass(),
          method.getMethod(),
          BenchmarkRunner::isCoreCollection);
      benchmarks.add(new SingleBenchmark(description, hotLoopFactory));
    }
    return benchmarks;
  }

  private static List<Runner> configuredBenchmarks(
      TestClass testClass,
      List<FrameworkMethod> methods,
      FrameworkField configurationsField) throws IllegalAccessException, InitializationError {
    List<?> configurations = (List<?>) configurationsField.get(null);
    List<Runner> benchmarks = new ArrayList<>();
    for (FrameworkMethod method : methods) {
      benchmarks.add(new ParameterisedMethodBenchmark(
          testClass, method, configurationsField, configurations));
    }
    return benchmarks;
  }

  public BenchmarkRunner(Class<?> testClass) throws InitializationError {
    super(testClass);
    benchmarks = getBenchmarks(getTestClass());
  }

  @Override
  protected List<Runner> getChildren() {
    return benchmarks;
  }

  @Override
  protected Description describeChild(Runner benchmark) {
    return benchmark.getDescription();
  }

  @Override
  public void run(RunNotifier notifier) {
    // Ensure the management monitors don't set themselves off mid-test if they've never run before
    ManagementMonitor monitor = new ManagementMonitor();
    monitor.stop();
    monitor.printIfChanged(new PrintStream(new ByteArrayOutputStream()));

    super.run(notifier);
  }

  @Override
  protected void runChild(Runner benchmark, RunNotifier notifier) {
    benchmark.run(notifier);
  }

  static class ParameterisedMethodBenchmark extends ParentRunner<SingleBenchmark> {
    private final FrameworkMethod method;
    private final List<SingleBenchmark> flavours;

    ParameterisedMethodBenchmark(
        TestClass testClass,
        FrameworkMethod method,
        FrameworkField configurationsField,
        List<?> configurations) throws InitializationError {
      super(testClass.getJavaClass());
      this.method = method;
      this.flavours = IntStream.iterate(0, i -> ++i)
          .limit(configurations.size())
          .mapToObj(index -> singleBenchmark(testClass, method, configurationsField, configurations, index))
          .sorted()
          .collect(toList());
    }

    private static SingleBenchmark singleBenchmark(
        TestClass testClass,
        FrameworkMethod method,
        FrameworkField configurationsField,
        List<?> configurations,
        int index) {
      Object configuration = configurations.get(index);
      Description description = createSingleBenchmarkDescription(testClass, method, configuration);
      Supplier<LongUnaryOperator> hotLoopFactory = () -> BenchmarkCompiler.compileBenchmark(
          testClass.getJavaClass(),
          method.getMethod(),
          configurationsField.getField(),
          index,
          BenchmarkRunner::isCoreCollection);
      return new SingleBenchmark(
          description,
          hotLoopFactory,
          configurations.get(index));
    }

    @Override
    protected String getName() {
      return getTestClass().getName() + "#" + method.getName();
    }

    @Override
    public void run(RunNotifier notifier) {
      String title = title();
      System.out.println(title);
      System.out.println(Stream.generate(() -> "-").limit(title.length()).collect(joining()));
      InterferenceWarning interferenceWarning = method.getAnnotation(InterferenceWarning.class);
      if (interferenceWarning != null && getDescription().getChildren().size() > 1) {
        System.out.println(" ** " + interferenceWarning.value() + " **");
        System.out.println("    Run in isolation for trustworthy results");
      }
      MemoryAllocationMonitor.get().warnIfMonitoringDisabled();
      super.run(notifier);
      System.out.println();
    }

    @Override
    protected void runChild(SingleBenchmark flavour, RunNotifier notifier) {
      flavour.run(notifier);
    }

    @Override
    protected Description describeChild(SingleBenchmark child) {
      return child.getDescription();
    }

    @Override
    protected List<SingleBenchmark> getChildren() {
      return flavours;
    }

    private String title() {
      Benchmark benchmark = method.getAnnotation(Benchmark.class);
      if (!benchmark.value().isEmpty()) {
        return benchmark.value();
      } else {
        return method.getName();
      }
    }
  }

  private static boolean isCoreCollection(Class<?> cls) {
    boolean isInJavaUtil = cls.getPackage().getName().equals("java.util");
    boolean isClass = !cls.isInterface();
    boolean isCollection = Map.class.isAssignableFrom(cls) || Set.class.isAssignableFrom(cls);
    return isInJavaUtil && isClass && isCollection;
  }

  private static final Map<Integer, String> SCALES = ImmutableMap.<Integer, String>builder()
      .put(0, "s")
      .put(-3, "ms")
      .put(-6, "μs")
      .put(-9, "ns")
      .put(-12, "ps")
      .build();

  static String formatNanos(double nanos) {
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

    if (SCALES.containsKey(scale)) {
      return String.format("%s %s", significand, SCALES.get(scale));
    } else {
      return String.format("%se%d s", significand, scale);
    }
  }
}
