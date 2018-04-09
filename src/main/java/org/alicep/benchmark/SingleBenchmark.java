package org.alicep.benchmark;

import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.sqrt;
import static org.alicep.benchmark.Bytes.bytes;

import java.time.Duration;
import java.util.Arrays;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;

import org.junit.AssumptionViolatedException;
import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunNotifier;

import com.google.common.collect.Ordering;

class SingleBenchmark extends Runner implements Comparable<SingleBenchmark> {

  private static Duration MIN_HOT_LOOP_TIME = Duration.ofMillis(50);
  private static int MIN_MEASUREMENT_ITERATIONS = 5;
  private static final double TARGET_ERROR = 0.01;

  private static final double OUTLIER_EWMAV_WEIGHT = 0.1;
  private static final int OUTLIER_WINDOW = 20;
  private static final double CONFIDENCE_INTERVAL_99_PERCENT = 2.58;

  private final Description description;
  private final Supplier<LongUnaryOperator> hotLoopFactory;
  private final Object configuration;

  SingleBenchmark(
      Description description,
      Supplier<LongUnaryOperator> hotLoopFactory) {
    this.description = description;
    this.hotLoopFactory = hotLoopFactory;
    this.configuration = null;
  }

  SingleBenchmark(
      Description description,
      Supplier<LongUnaryOperator> hotLoopFactory,
      Object configuration) {
    this.description = description;
    this.hotLoopFactory = hotLoopFactory;
    this.configuration = configuration;
  }

  @Override
  public Description getDescription() {
    return description;
  }

  @Override
  public int compareTo(SingleBenchmark o) {
    if (config() == null) {
      return Ordering.arbitrary().compare(this, o);
    } else if (config() instanceof Comparable) {
      return Ordering.natural().compare((Comparable<?>) config(), (Comparable<?>) o.config());
    } else {
      return Ordering.natural().compare(config().toString(), o.config().toString());
    }
  }

  /**
   * Run in a single method to ensure the JIT targets the generated hot loop code only
   */
  @Override
  public void run(RunNotifier notifier) {
    notifier.fireTestStarted(description);

    try {
      // The hot loop we are timing
      LongUnaryOperator hotLoop = hotLoopFactory.get();

      if (config() == null) {
        System.out.print(description.getMethodName() + ": ");
      } else {
        System.out.print(config() + ": ");
      }
      if (System.getenv("CI") == null) {
        System.out.flush();
      }

      // Number of times to run the hot loop for
      long hotLoopIterations = 1;

      // Elapsed time (total time / iterations) for each timed iteration
      double[] timings = new double[50];

      // Elapsed time statistic sources
      double tS = 0.0;
      double tSS = 0.0;
      double id = 0.0;
      double ewma = 0.0;
      double ewmas = 0.0;

      // Memory usage across all timed iterations
      long usageBeforeRun = 0;
      long usageAfterRun;

      // Monitor the JVM for suspicious activity
      ManagementMonitor monitor = new ManagementMonitor();

      // How many timing samples we've taken
      int timingSamples = 0;

      // How many memory samples we've taken
      int memorySamples = 0;

      MemoryAllocationMonitor memoryAllocationMonitor = MemoryAllocationMonitor.get();

      do {
        if (memorySamples == 0) {
          memoryAllocationMonitor.prepareForBenchmark();
          usageBeforeRun = memoryAllocationMonitor.memoryUsed();
          monitor.start();
        }
        if (timingSamples == 0) {
          tS = 0.0;
          tSS = 0.0;
        }

        long elapsed = hotLoop.applyAsLong(hotLoopIterations);
        if (elapsed < MIN_HOT_LOOP_TIME.toNanos()) {
          // Restart if the hot loop did not take enough time running
          hotLoopIterations = hotLoopIterations + (hotLoopIterations >> 1) + 1;
          timingSamples = -1;
          memorySamples = -1;
        } else {
          // Record elapsed time if we're in the timing loop
          if (timings.length == timingSamples) {
            timings = Arrays.copyOf(timings, timings.length * 2);
          }
          double iterationTime = (double) elapsed / hotLoopIterations;

          if (timingSamples >= OUTLIER_WINDOW) {
            if (isOutlier(iterationTime, ewma / id, ewmas / id, timingSamples)) {
              continue;
            }
          }

          timings[timingSamples] = iterationTime;
          tS += iterationTime;
          tSS += iterationTime * iterationTime;
          id = updateEwmav(id, 1.0);
          ewma = updateEwmav(ewma, iterationTime);
          ewmas = updateEwmav(ewmas, iterationTime * iterationTime);

          // Remove old outliers
          // We do this as we run so that the sample error calculations do not include erroneous data
          if (timingSamples >= OUTLIER_WINDOW) {
            int firstIndex = timingSamples - OUTLIER_WINDOW;
            for (int index = timingSamples - OUTLIER_WINDOW; index >= firstIndex; index--) {
              if (isOutlier(timings, index, timingSamples - index)) {
                double value = timings[index];
                tS -= value;
                tSS -= value * value;
                for (int i = index + 1; i < timingSamples; ++i) {
                  timings[i - 1] = timings[i];
                }
                timings[timingSamples] = 0.0;
                firstIndex = Math.max(index - OUTLIER_WINDOW, 0);
                timingSamples--;
              }
            }
          }

          // Calculate ongoing sample error
          double sampleError = sqrt((tSS - tS*tS/(timingSamples + 1)) / ((timingSamples + 1) * timingSamples));
          double confidenceInterval = sampleError * CONFIDENCE_INTERVAL_99_PERCENT;
          boolean lowSampleError = confidenceInterval * timingSamples < tS * TARGET_ERROR;

          // Break out of the loop if we're confident our error is low
          boolean enoughSamples = timingSamples >= MIN_MEASUREMENT_ITERATIONS;
          if (enoughSamples && lowSampleError) {
            monitor.stop();
            usageAfterRun = memoryAllocationMonitor.memoryUsed();
            timingSamples++;
            break;
          }
        }

        timingSamples++;
        memorySamples++;
      } while (true);

      double memoryUsage = (double)
          (usageAfterRun - usageBeforeRun - memoryAllocationMonitor.approximateBaselineError()) / hotLoopIterations;
      summarize(tS, tSS, timingSamples, memoryUsage, memorySamples, monitor);
      notifier.fireTestFinished(description);
    } catch (Throwable t) {
      if (t.getClass().getName().equals(AssumptionViolatedException.class.getName())) {
        // Janky class name check because this might be thrown from a forked ClassLoader
        notifier.fireTestAssumptionFailed(new Failure(description, t));
      } else {
        System.out.print(t.getClass().getSimpleName());
        if (t.getMessage() != null) {
          System.out.print(": ");
          System.out.print(t.getMessage());
        }
        System.out.println();
        notifier.fireTestFailure(new Failure(description, t));
      }
    }
  }

  private static boolean isOutlier(double[] timings, int index, int samples) {
    double id = 0.0;
    double ewma = 0.0;
    double ewmas = 0.0;
    for (int i = 0; i < samples; ++i) {
      double value = timings[index + i + 1];
      checkState(value > 0.0);
      id = updateEwmav(id, 1.0);
      ewma = updateEwmav(ewma, value);
      ewmas = updateEwmav(ewmas, value * value);
    }
    return isOutlier(timings[index], ewma / id, ewmas / id, samples);
  }

  /**
   * Discard samples more than 3 standard deviations above the mean of the subsequent readings.
   *
   * <p>About 99.7% of points lie within this range, so this should not be biasing results too
   * significantly downwards.
   */
  private static boolean isOutlier(double value, double ewma, double ewmas, int samples) {
    double ewmasd = sqrt((ewmas - ewma*ewma) * samples/(samples - 1));
    return value > ewma + ewmasd * 3;
  }

  private static double updateEwmav(double mav, double value) {
    return OUTLIER_EWMAV_WEIGHT * value + (1 - OUTLIER_EWMAV_WEIGHT) * mav;
  }

  private static void summarize(
      double tS,
      double tSS,
      int iterations,
      double memoryUsage,
      int memorySamples,
      ManagementMonitor monitor) {
    String timeSummary = summarizeTime(tS, tSS, iterations);
    String memorySummary = summarizeMemory(memoryUsage, memorySamples);
    System.out.println(timeSummary + ", " + memorySummary);
    monitor.printIfChanged(System.out);
  }

  private static String summarizeTime(double tS, double tSS, int iterations) {
    double total = tS;
    double mean = total / iterations;
    double sd = sqrt((tSS - tS*tS/iterations) / (iterations - 1));
    String timeSummary = BenchmarkRunner.formatNanos(mean) + " (±" + BenchmarkRunner.formatNanos(sd * CONFIDENCE_INTERVAL_99_PERCENT) + ")";
    return timeSummary;
  }

  private static String summarizeMemory(double memoryUsage, int memorySamples) {
    return bytes((long) memoryUsage/memorySamples).toString();
  }

  public Object config() {
    return configuration;
  }
}
