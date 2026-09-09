package io.github.ankitnehra6.llmgateway.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies the executor that streamed responses run on. */
@Configuration
public class StreamExecutorConfiguration {

    /**
     * One virtual thread per in-flight stream.
     *
     * <p>A stream occupies a thread for its whole lifetime while doing nothing but waiting
     * on an upstream socket. With platform threads that would cap concurrent streams at
     * the pool size and waste a megabyte of stack each; virtual threads make the cost per
     * stream small enough that no pool sizing decision is needed.
     */
    @Bean(destroyMethod = "shutdown")
    ExecutorService streamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
