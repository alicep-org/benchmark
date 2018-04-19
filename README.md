# Java Benchmark Utilities

Benchmark your code to nanosecond and byte precision with ease, in JUnit, in your IDE, with zero setup.

[![Build Status](https://travis-ci.org/alicep-org/benchmark.svg?branch=master)](https://travis-ci.org/alicep-org/benchmark)
[![Download](https://api.bintray.com/packages/alicep-org/maven/benchmark/images/download.svg)](https://bintray.com/alicep-org/maven/benchmark/_latestVersion)

## Byte-precision memory usage

`MemoryAssertions` provides a fluent API for testing how much memory a method allocates or returns, to byte precision for small (<1KB) sizes, by watching Eden space usage or Old Gen space during multiple executions.

```
assertThatRunning(() -> null).makesNoStackAllocations();
assertThatRunning(() -> new byte[5]).allocates(bytes(24));
assertThatRunning(() -> Arrays.copyOf(new long[15], 20)
    .returnsObjectConsuming(bytes(16 + Long.BYTES * 20)));
```

`MemGauge` gives direct access to the memory calculation algorithms used by `MemoryAssertions`:

```
// Round up to a multiple of 4 and add 16 bits of header (object header + size)
assertEquals(bytes(24), objectSize(() -> new byte[5]));
// Allocates two byte[5]
assertEquals(bytes(48), memoryConsumption(() -> {
  byte[] bytes = new byte[5];
  bytes[2] = 3;
  // Return the result to ensure HotSpot does not optimise away the allocations
  return Arrays.copyOf(bytes, 5);
});
```

Currently assumes a parallel sweep garbage collector and uses Sun internal classes; YMMV as to whether this works in your JVM.

## Nanosecond-precision benchmarks

Using in-JVM source compilation and bytecode rewriting, the JUnit4-compatible `BenchmarkRunner` avoids most of the pitfalls of microbenchmarks, so you can focus on optimising your code.

```

@RunWith(BenchmarkRunner.class)
public class DummyBenchmark {

    @Configuration
    public static final List<Integer> sizes = Arrays.asList(2, 3, 10, 100);

    private final String[] items;

    public DummyBenchmark(int size) {
        // Create the list of items in the constructor as we're interested in benchmarking
        // list creation, not Integer.toString.
        items = new String[size];
        setAll(items, i -> Integer.toString(i));
    }

    @Benchmark("Create a list of strings")
    public void createList() {
        List<String> list = new ArrayList<>();
        for (String item : items) {
            list.add(item);
        }
        assertEquals(items.length, list.size());
    }
}
```

Running this benchmark will produce output like the following:

```
Create a list of strings
------------------------
2: 24.1 ns (±1.54 ns), 56B
  * 18 PS Scavenge collections over 20.0 ns
3: 27.9 ns (±1.46 ns), 56B
  * 15 PS Scavenge collections over 14.0 ns
10: 56.4 ns (±4.23 ns), 56B
  * 28 PS Scavenge collections over 26.0 ns
100: 567 ns (±36.9 ns), 1.38kB
  * 32 PS Scavenge collections over 32.0 ns</pre>
```

The range shows the variation in timings encountered when running the test; the sample error of the mean will be around 1% to 99% confidence, for this particular JIT run and background machine load.

Memory usage is calculated using the same method as `MemoryAssertions`, above.
