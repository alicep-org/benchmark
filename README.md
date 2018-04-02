# Java Benchmark Utilities

Benchmark your code to nanosecond and byte precision with ease.

## Resident memory usage

`MemGauge.measureMemoryUsage` lets you determine the memory consumed by an object or collection of objects to byte precision in a fraction of a second. By triggering repeated GC cycles and watching the notifications, with a few tricks to overcome the JVM's intransigence, you can not only discover but unit test your memory usage.

```
// Round up to a multiple of 4 and add 16 bits of header (object header + size)
assertEquals(bytes(24), measureMemoryUsage(i -> new byte[5]));
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
2: 24.1 ns (±1.54 ns), 57B
  * 18 PS Scavenge collections over 20.0 ns
3: 27.9 ns (±1.46 ns), 58B
  * 15 PS Scavenge collections over 14.0 ns
10: 56.4 ns (±4.23 ns), 59B
  * 28 PS Scavenge collections over 26.0 ns
100: 567 ns (±36.9 ns), 1.44kB
  * 32 PS Scavenge collections over 32.0 ns</pre>
```

The range shows the variation in timings encountered when running the test; the sample error of the mean will be around 1% to 99% confidence, for this particular JIT run and background machine load.

Note that memory sizes are approximate. For byte-precision memory usage figures, see above.