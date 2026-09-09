package io.github.ankitnehra6.llmgateway.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the application clock.
 *
 * <p>Injected rather than calling {@code Instant.now()} inline so budget periods can be
 * tested by moving time instead of sleeping through it. {@code @ConditionalOnMissingBean}
 * lets a test replace it without excluding this configuration.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
