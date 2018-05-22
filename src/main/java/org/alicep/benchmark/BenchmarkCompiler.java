package org.alicep.benchmark;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.reflect.Modifier.isStatic;

import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongUnaryOperator;
import java.util.function.Predicate;

import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import com.google.common.collect.ImmutableList;

class BenchmarkCompiler {

  private static final AtomicInteger count = new AtomicInteger();

  /**
   * Returns an object that wraps a benchmark method and invokes it in a loop.
   *
   * <p>The object is freshly code-generated each call, and uses an unshared class-loader,
   * meaning any user classes will be re-JITted. Types under the java packages may still
   * cause polymorphic dispatch timing issues.
   */
  @SafeVarargs
  public static LongUnaryOperator compileBenchmark(
      ClassLoader classLoader,
      Class<?> cls,
      Method method,
      boolean forkingClasses,
      Predicate<Class<?>>... forkingCoreClassesMatching) {
    return compileBenchmark(classLoader, cls, method, null, -1, forkingClasses, forkingCoreClassesMatching);
  }

  /**
   * Returns an object that wraps a benchmark method and invokes it in a loop.
   *
   * <p>The object is freshly code-generated each call, and uses an unshared class-loader,
   * meaning any user classes will be re-JITted. Types under the java packages may still
   * cause polymorphic dispatch timing issues.
   */
  @SafeVarargs
  public static LongUnaryOperator compileBenchmark(
      ClassLoader classLoader,
      Class<?> cls,
      Method method,
      Field configurations,
      int index,
      boolean forkingClasses,
      Predicate<Class<?>>... forkingCoreClassesMatching) {
    checkArgument(cls.isAssignableFrom(method.getDeclaringClass()));
    String pkg = method.getDeclaringClass().getPackage().getName();
    if (pkg.startsWith("java.")) {
      pkg = "looper." + pkg;
    }
    String className = "Benchmark_" + count.incrementAndGet();

    String constructorParam = "";
    if (configurations != null) {
      checkArgument(isStatic(configurations.getModifiers()));
      checkArgument(index >= 0);
      String configurationName = configurations.getDeclaringClass().getName()
                  + "." + configurations.getName();
      constructorParam = configurationName + ".get(" + index + ")";
    }
    String src = "package " + pkg + ";\n"
        + "public class " + className + " implements " + LongUnaryOperator.class.getName() + " {\n"
        + "  private final " + declaration(cls) + " test =\n"
        + "      " + construct(cls) + "(" + constructorParam + ");\n"
        + "  @Override\n"
        + "  public long applyAsLong(long iterations) {\n"
        + "    long startTime = " + System.class.getName() + ".nanoTime();\n"
        + "    for (long i = 0; i < iterations; i++) {\n"
        + "      test." + method.getName() + "();\n"
        + "    }\n"
        + "    long endTime = " + System.class.getName() + ".nanoTime();\n"
        + "    return endTime - startTime;\n"
        + "  }\n"
        + "}\n";
    InMemoryJavaFileManager bytecodes = compile(pkg, className, src);
    ClassLoader forkingClassLoader = getClassLoader(classLoader, bytecodes, forkingClasses, forkingCoreClassesMatching);
    ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
    try {
      try {
        Thread.currentThread().setContextClassLoader(forkingClassLoader);
        Class<?> generatedClass = forkingClassLoader.loadClass(pkg + "." + className);
        LongUnaryOperator benchmarkLoop = (LongUnaryOperator) generatedClass.newInstance();
        return jitObfuscate(benchmarkLoop);
      } catch (EclipseCompilerBug e) {
        System.out.println("[WARN] " + e.getMessage());
        System.out.println("[WARN] Benchmarks may interfere");
        ClassLoader nonForkingClassLoader = bytecodes.getNonForkingClassLoader(classLoader);
        Thread.currentThread().setContextClassLoader(nonForkingClassLoader);
        Class<?> generatedClass = nonForkingClassLoader.loadClass(pkg + "." + className);
        return (LongUnaryOperator) generatedClass.newInstance();
      }
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    } finally {
      Thread.currentThread().setContextClassLoader(originalClassLoader);
    }
  }

  @SafeVarargs
  private static ClassLoader getClassLoader(
      ClassLoader classLoader,
      InMemoryJavaFileManager bytecodes,
      boolean forkingClasses,
      Predicate<Class<?>>... forkingCoreClassesMatching) {
    if (forkingClasses) {
      ForkingClassLoader forkingClassLoader = bytecodes.getForkingClassLoader(classLoader);
      Arrays.asList(forkingCoreClassesMatching).forEach(forkingClassLoader::forkingCoreClassesMatching);
      return forkingClassLoader;
    } else {
      return bytecodes.getNonForkingClassLoader(classLoader);
    }
  }

  private static String declaration(Class<?> cls) {
    StringBuilder declaration = new StringBuilder();
    declaration.append(cls.getName());
    if (cls.getTypeParameters().length > 0) {
      declaration.append("<?");
      for (int i = 1; i < cls.getTypeParameters().length; i++) {
        declaration.append(",?");
      }
      declaration.append(">");
    }
    return declaration.toString();
  }

  private static String construct(Class<?> cls) {
    StringBuilder construct = new StringBuilder();
    construct.append("new ").append(cls.getName());
    if (cls.getTypeParameters().length > 0) {
      construct.append("<>");
    }
    return construct.toString();
  }

  private static InMemoryJavaFileManager compile(String pkg, String className, String src) {
    URI uri = URI.create("temp://" + pkg.replace(".", "/") + "/" + className + ".java");
    JavaFileObject loopSource = new SourceObject(uri, Kind.SOURCE, src);
    StringWriter writer = new StringWriter();
    DiagnosticListener<? super JavaFileObject> diagnosticListener =
        diagnostic -> writer.write(diagnostic.toString() + "\n");
    InMemoryJavaFileManager fileManager = InMemoryJavaFileManager.create(diagnosticListener);
    CompilationTask task = ToolProvider.getSystemJavaCompiler().getTask(
        writer,
        fileManager,
        diagnosticListener,
        ImmutableList.of(),
        null,  // class names
        ImmutableList.of(loopSource));
    boolean compiled = task.call();
    if (!compiled) {
      String messages = writer.toString().trim();
      if (!messages.isEmpty()) {
        throw new IllegalStateException("Compilation failed:\n" + messages);
      }
      throw new IllegalStateException("Compilation failed");
    }
    return fileManager;
  }

  private static LongUnaryOperator jitObfuscate(LongUnaryOperator target) {
    return new RoundRobinLongUnaryOperator(
        new DelegatingLongUnaryOperator1(target),
        new DelegatingLongUnaryOperator2(target),
        new DelegatingLongUnaryOperator3(target));
  }

  private static class RoundRobinLongUnaryOperator implements LongUnaryOperator {

    private final LongUnaryOperator[] operators;
    private int index = 0;

    RoundRobinLongUnaryOperator(LongUnaryOperator... operators) {
      this.operators = operators;
    }

    @Override
    public long applyAsLong(long operand) {
      index++;
      if (index == operators.length) {
        index = 0;
      }
      return operators[index].applyAsLong(operand);
    }
  }

  private static class DelegatingLongUnaryOperator1 implements LongUnaryOperator {

    private final LongUnaryOperator delegate;

    DelegatingLongUnaryOperator1(LongUnaryOperator delegate) {
      this.delegate = delegate;
    }

    @Override
    public long applyAsLong(long operand) {
      return delegate.applyAsLong(operand);
    }
  }

  private static class DelegatingLongUnaryOperator2 implements LongUnaryOperator {

    private final LongUnaryOperator delegate;

    DelegatingLongUnaryOperator2(LongUnaryOperator delegate) {
      this.delegate = delegate;
    }

    @Override
    public long applyAsLong(long operand) {
      return delegate.applyAsLong(operand);
    }
  }

  private static class DelegatingLongUnaryOperator3 implements LongUnaryOperator {

    private final LongUnaryOperator delegate;

    DelegatingLongUnaryOperator3(LongUnaryOperator delegate) {
      this.delegate = delegate;
    }

    @Override
    public long applyAsLong(long operand) {
      return delegate.applyAsLong(operand);
    }
  }

  private static class SourceObject extends SimpleJavaFileObject {

    private final String source;

    SourceObject(URI uri, Kind kind, String source) {
      super(uri, kind);
      this.source = source;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return source;
    }
  }

  private BenchmarkCompiler() { }
}
