package org.alicep.benchmark;

import static java.util.Arrays.setAll;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.alicep.benchmark.BenchmarkRunner.Benchmark;
import org.alicep.benchmark.BenchmarkRunner.Configuration;
import org.junit.runner.RunWith;

@RunWith(BenchmarkRunner.class)
public class DummyBenchmark {

    @Configuration
    public static final List<Integer> sizes = Arrays.asList(2, 3, 10, 100);

    private final String[] items;

    public DummyBenchmark(int size) {
        assumeTrue("Don't run on CI", System.getenv("CI") == null);
        // Create the list of items in the constructor as we're interested in benchmarking
        // list creation, not Integer.toString.
        items = new String[size];
        setAll(items, i -> Integer.toString(i));
    }

    /**
     * Running this benchmark will produce output like the following:
     *
     * <pre> Create a list of strings
     * ------------------------
     * 2: 24.1 ns (±1.54 ns), 57B
     *   * 18 PS Scavenge collections over 20.0 ns
     * 3: 27.9 ns (±1.46 ns), 58B
     *   * 15 PS Scavenge collections over 14.0 ns
     * 10: 56.4 ns (±4.23 ns), 59B
     *   * 28 PS Scavenge collections over 26.0 ns
     * 100: 567 ns (±36.9 ns), 1.44kB
     *   * 32 PS Scavenge collections over 32.0 ns</pre>
     *
     * <p>The range shows the variation in timings encountered when running the test; the
     * sample error of the mean will be around 1% to 99% confidence, for this particular
     * JIT run and background machine load.
     *
     * <p>Note that memory sizes are approximate. For byte-precision memory usage figures,
     * take a look at {@link MemGauge#measureMemoryUsage}.
     */
    @Benchmark("Create a list of strings")
    public void createList() {
        List<String> list = new ArrayList<>();
        for (String item : items) {
            list.add(item);
        }
        assertEquals(items.length, list.size());
    }
}
