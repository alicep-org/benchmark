package org.alicep.benchmark;

import java.io.IOException;
import java.lang.reflect.MalformedParametersException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Predicate;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.scaffold.TypeValidation;

/**
 * Clones a classloader, returning equal but not identical classes.
 *
 * <p>Gives a clean set of types for the JIT to target, avoiding cross-benchmark contamination.
 */
class ForkingClassLoader extends ClassLoader {

  private static final String FORK_PACKAGE = "forked.";

  private static ClassLoader rootClassLoader() {
    ClassLoader classLoader = getSystemClassLoader();
    while (classLoader.getParent() != null) {
      classLoader = classLoader.getParent();
    }
    return classLoader;
  }

  private final ClassLoader original;
  private final List<Predicate<Class<?>>> corePredicates = new ArrayList<>();

  protected ForkingClassLoader(ClassLoader original) {
    super(rootClassLoader());
    this.original = original;
  }

  public ForkingClassLoader forkingCoreClassesMatching(Predicate<Class<?>> predicate) {
    corePredicates.add(predicate);
    return this;
  }

  private String rename(String cls) {
    if (cls.startsWith("java.")) {
      try {
        Class<?> outermostClass = outermostClass(cls);
        if (corePredicates.stream().anyMatch(p -> p.test(outermostClass))) {
          return FORK_PACKAGE + cls;
        }
      } catch (ClassNotFoundException e) {
        return cls;
      }
    }
    return cls;
  }

  private static Class<?> outermostClass(String cls) throws ClassNotFoundException {
    Class<?> classObject = ClassLoader.getSystemClassLoader().loadClass(cls);
    while (classObject.getEnclosingClass() != null) {
      classObject = classObject.getEnclosingClass();
    }
    return classObject;
  }

  @Override
  protected Class<?> findClass(String name) throws ClassNotFoundException {
    String originalName = name.startsWith(FORK_PACKAGE) ? name.substring(FORK_PACKAGE.length()) : name;
    Class<?> originalClass = original.loadClass(originalName);
    try {
      byte[] bytes = new ByteBuddy()
          .with(TypeValidation.DISABLED)
          .redefine(originalClass)
          .name(name)
          .visit(new SubstituteClassReferences(this::rename))
          .make()
          .getBytes();
      return super.defineClass(name, bytes, 0, bytes.length);
    } catch (IllegalStateException e) {
      if (e.getCause() instanceof MalformedParametersException) {
        MalformedParametersException cause = (MalformedParametersException) e.getCause();
        if (cause.getMessage().equals("Invalid parameter name \"\"")) {
          throw new EclipseCompilerBug("Encountered ECJ bug: https://bugs.eclipse.org/bugs/show_bug.cgi?id=516833", e);
        }
      }
      throw new AssertionError("Failed to fork " + originalName, e);
    } catch (Error | RuntimeException e) {
      throw new AssertionError("Failed to fork " + originalName, e);
    }
  }

  @Override
  protected URL findResource(String name) {
    return original.getResource(name);
  }

  @Override
  protected Enumeration<URL> findResources(String name) throws IOException {
    return original.getResources(name);
  }
}
