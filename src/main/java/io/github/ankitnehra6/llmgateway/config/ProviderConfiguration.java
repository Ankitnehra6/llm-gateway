package io.github.ankitnehra6.llmgateway.config;

import io.github.ankitnehra6.llmgateway.provider.EchoProvider;
import io.github.ankitnehra6.llmgateway.provider.LlmProvider;
import io.github.ankitnehra6.llmgateway.routing.ProviderChain;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds the failover chain from configuration. */
@Configuration
public class ProviderConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ProviderConfiguration.class);

    @Bean
    ProviderChain providerChain(GatewayProperties properties) {
        List<GatewayProperties.ProviderConfig> configured = properties.providers();

        if (configured == null || configured.isEmpty()) {
            // A gateway with no upstream is not useful, but failing to start is worse
            // than starting degraded and saying so: it turns a config typo into a crash
            // loop. Every request will fail loudly with AllProvidersFailedException.
            log.warn("no providers configured; every request will fail until one is added");
            return new ProviderChain(List.of());
        }

        List<LlmProvider> providers = new ArrayList<>(configured.size());
        for (GatewayProperties.ProviderConfig config : configured) {
            providers.add(build(config));
        }

        log.info(
                "provider failover chain: {}",
                providers.stream().map(LlmProvider::name).toList());
        return new ProviderChain(providers);
    }

    private LlmProvider build(GatewayProperties.ProviderConfig config) {
        if (config.name() == null || config.name().isBlank()) {
            throw new IllegalStateException("every configured provider needs a name");
        }

        return switch (config.type()) {
            case ECHO ->
                    new EchoProvider(config.name(), config.latency(), config.failureRate());
        };
    }
}
