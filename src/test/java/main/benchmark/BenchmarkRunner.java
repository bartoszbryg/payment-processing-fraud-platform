package main.benchmark;

import org.junit.jupiter.api.Test;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Standalone entry point for running fraud-detection JMH benchmarks.
 *
 * Usage from project root:
 *
 *   mvn test -Pbenchmark
 *
 *   mvn test -Pbenchmark -Djmh.rf=json -Djmh.rff=benchmark-results.json
 *
 *   mvn test -Pbenchmark -Djmh.include=GraphServiceBenchmark
 *
 *   mvn test -Pbenchmark -Djmh.rf=csv -Djmh.rff=benchmark-results.csv
 */
public class BenchmarkRunner {

    @Test
    public void runBenchmarks() throws RunnerException {
        main(new String[0]);
    }

    public static void main(String[] args) throws RunnerException {
        String include = System.getProperty("jmh.include", ".*Benchmark");
        String rf = System.getProperty("jmh.rf", "text");
        String rff = System.getProperty("jmh.rff", "benchmark-results.txt");

        int warmupIter = Integer.parseInt(System.getProperty("jmh.wi", "3"));
        int measureIter = Integer.parseInt(System.getProperty("jmh.i", "5"));
        int forks = Integer.parseInt(System.getProperty("jmh.f", "1"));

        Options options = new OptionsBuilder()
            .include(include)
            .warmupIterations(warmupIter)
            .measurementIterations(measureIter)
            .forks(forks)
            .resultFormat(ResultFormatType.valueOf(rf.toUpperCase()))
            .result(rff)
            .build();

        new Runner(options).run();
    }
}