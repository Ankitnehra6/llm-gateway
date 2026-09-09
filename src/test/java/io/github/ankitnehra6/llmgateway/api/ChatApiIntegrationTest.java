package io.github.ankitnehra6.llmgateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.ankitnehra6.llmgateway.TestcontainersConfiguration;
import io.github.ankitnehra6.llmgateway.budget.UsageRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end coverage of the public API against a real Postgres and Redis.
 *
 * <p>Runs the actual Flyway migrations, so the entity mappings and the schema are verified
 * against each other rather than assumed to agree — the failure this catches (a column
 * renamed in one place only) is invisible to unit tests and fatal at startup in
 * production.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ChatApiIntegrationTest {

    private static final String FREE_KEY = "demo-key-free";
    private static final String PRO_KEY = "demo-key-pro";

    @Autowired private MockMvc mvc;
    @Autowired private UsageRepository usage;

    /**
     * Every test gets a unique prompt. The semantic cache is live in this suite and its
     * state is shared across tests in the same context, so a fixed prompt would make one
     * test's answer another test's cache hit and the assertions would depend on ordering.
     */
    private static String uniquePrompt(String label) {
        return label + " " + UUID.randomUUID();
    }

    private static String body(String prompt) {
        return """
                {
                  "model": "gpt-4o-mini",
                  "messages": [{"role": "user", "content": "%s"}]
                }
                """
                .formatted(prompt);
    }

    @Test
    void servesACompletionForAnAuthenticatedTenant() throws Exception {
        mvc.perform(
                        post("/v1/chat/completions")
                                .header("Authorization", "Bearer " + PRO_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(uniquePrompt("hello gateway"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isNotEmpty())
                .andExpect(jsonPath("$.model").value("gpt-4o-mini"))
                // The first provider in the chain should answer when it is healthy.
                .andExpect(jsonPath("$.gateway.provider").value("primary"))
                .andExpect(jsonPath("$.gateway.cache_hit").value(false))
                .andExpect(jsonPath("$.gateway.failed_over").value(false))
                .andExpect(jsonPath("$.usage.total_tokens").isNumber())
                .andExpect(header().exists("X-Budget-Remaining"));
    }

    @Test
    void acceptsTheApiKeyHeaderAsWellAsBearer() throws Exception {
        mvc.perform(
                        post("/v1/chat/completions")
                                .header("X-API-Key", PRO_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(uniquePrompt("via x-api-key"))))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsAnUnknownKey() throws Exception {
        mvc.perform(
                        post("/v1/chat/completions")
                                .header("Authorization", "Bearer not-a-real-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("hello")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void rejectsAMissingKey() throws Exception {
        mvc.perform(
                        post("/v1/chat/completions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("hello")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAnEmptyMessageList() throws Exception {
        mvc.perform(
                        post("/v1/chat/completions")
                                .header("Authorization", "Bearer " + PRO_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"model\":\"gpt-4o-mini\",\"messages\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"));
    }

    @Test
    void rejectsAnInvalidRole() throws Exception {
        mvc.perform(
                        post("/v1/chat/completions")
                                .header("Authorization", "Bearer " + PRO_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"messages":[{"role":"wizard","content":"hi"}]}
                                        """))
                .andExpect(status().isBadRequest());
    }

    /** Every served request has to leave a ledger row, or budgets mean nothing. */
    @Test
    void recordsUsageForEveryServedRequest() throws Exception {
        long before = usage.count();

        mvc.perform(
                        post("/v1/chat/completions")
                                .header("Authorization", "Bearer " + PRO_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(uniquePrompt("accounted for"))))
                .andExpect(status().isOk());

        assertThat(usage.count()).isEqualTo(before + 1);
    }

    /** The second identical request must be served from cache without touching upstream. */
    @Test
    void servesARepeatedPromptFromCache() throws Exception {
        String prompt = uniquePrompt("what is the capital of France");

        mvc.perform(
                        post("/v1/chat/completions")
                                .header("Authorization", "Bearer " + PRO_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(prompt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateway.cache_hit").value(false));

        mvc.perform(
                        post("/v1/chat/completions")
                                .header("Authorization", "Bearer " + PRO_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(prompt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateway.cache_hit").value(true))
                .andExpect(jsonPath("$.gateway.cache_similarity").value(org.hamcrest.Matchers.greaterThan(0.94)));
    }

    /**
     * A cache hit costs no upstream tokens, so it must not be charged against the budget.
     * Charging for it would penalise a tenant for the gateway working well.
     */
    @Test
    void aCacheHitDoesNotConsumeBudget() throws Exception {
        String prompt = uniquePrompt("does a cache hit cost anything");

        mvc.perform(
                post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + PRO_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(prompt)));

        long usedAfterFirst = usedTokens();

        mvc.perform(
                        post("/v1/chat/completions")
                                .header("Authorization", "Bearer " + PRO_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(prompt)))
                .andExpect(jsonPath("$.gateway.cache_hit").value(true));

        assertThat(usedTokens())
                .as("a cache hit must not increase billable usage")
                .isEqualTo(usedAfterFirst);
    }

    private long usedTokens() throws Exception {
        String json =
                mvc.perform(get("/v1/usage").header("Authorization", "Bearer " + PRO_KEY))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(json).read("$.used", Integer.class).longValue();
    }

    // --- streaming ---------------------------------------------------------------

    @Test
    void streamsWhenAskedTo() throws Exception {
        String json =
                """
                {
                  "model": "gpt-4o-mini",
                  "stream": true,
                  "messages": [{"role": "user", "content": "%s"}]
                }
                """
                        .formatted(uniquePrompt("stream this back to me"));

        var result =
                mvc.perform(
                                post("/v1/chat/completions")
                                        .header("Authorization", "Bearer " + PRO_KEY)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json))
                        .andExpect(request().asyncStarted())
                        .andReturn();

        // An SseEmitter never "sets" an async result the way a Callable does — it writes
        // to the response from another thread and then completes. asyncDispatch would
        // wait forever, so poll the captured response until the stream ends instead.
        String body = awaitStream(result);

        // Shaped for OpenAI streaming clients: many delta chunks, then the sentinel.
        assertThat(body).contains("chat.completion.chunk");
        assertThat(body).contains("\"delta\"");
        assertThat(body).endsWith("data:[DONE]\n\n");
        assertThat(body.split("data:").length)
                .as("a stream should arrive in several chunks, not one")
                .isGreaterThan(3);
    }

    /** Waits for a streamed response to reach its terminating sentinel. */
    private static String awaitStream(org.springframework.test.web.servlet.MvcResult result)
            throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String body = result.getResponse().getContentAsString();
            if (body.contains("[DONE]")) {
                return body;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(
                "stream did not finish within 10s; got: " + result.getResponse().getContentAsString());
    }

    /**
     * Without gateway metadata on the terminating chunk, a streamed cache hit is
     * indistinguishable from a streamed upstream call — the console would label every
     * stream "upstream" and quietly lie about it.
     */
    @Test
    void aStreamedCacheHitSaysSo() throws Exception {
        String prompt = uniquePrompt("stream me from cache");
        String json =
                """
                {"model":"gpt-4o-mini","stream":true,"messages":[{"role":"user","content":"%s"}]}
                """
                        .formatted(prompt);

        var first =
                mvc.perform(
                                post("/v1/chat/completions")
                                        .header("Authorization", "Bearer " + PRO_KEY)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json))
                        .andReturn();
        assertThat(awaitStream(first)).contains("\"cache_hit\":false");

        var second =
                mvc.perform(
                                post("/v1/chat/completions")
                                        .header("Authorization", "Bearer " + PRO_KEY)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json))
                        .andReturn();

        String body = awaitStream(second);
        assertThat(body).contains("\"cache_hit\":true");
        assertThat(body).contains("\"cache_similarity\"");
    }

    // --- console stats ------------------------------------------------------------

    @Test
    void reportsGatewayStatsForTheConsole() throws Exception {
        mvc.perform(
                post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + PRO_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(uniquePrompt("populate the stats"))));

        mvc.perform(get("/v1/gateway/stats").header("Authorization", "Bearer " + PRO_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cache.enabled").value(true))
                .andExpect(jsonPath("$.cache.similarity_threshold").value(0.95))
                .andExpect(jsonPath("$.cache.misses").value(org.hamcrest.Matchers.greaterThan(0)))
                // Both configured providers, in failover order.
                .andExpect(jsonPath("$.providers.length()").value(2))
                .andExpect(jsonPath("$.providers[0].name").value("primary"))
                .andExpect(jsonPath("$.providers[0].circuit_state").value("closed"))
                .andExpect(jsonPath("$.tenant.id").value("demo-pro"))
                .andExpect(jsonPath("$.default_model").value("gpt-4o-mini"));
    }

    /** Operational state is not something an anonymous caller should be able to read. */
    @Test
    void statsRequireAValidKey() throws Exception {
        mvc.perform(get("/v1/gateway/stats")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/gateway/stats").header("Authorization", "Bearer nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void servesTheConsole() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("LLM Gateway")));
    }

    @Test
    void rejectsAnUnauthenticatedStreamRequest() throws Exception {
        mvc.perform(
                        post("/v1/chat/completions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"stream":true,"messages":[{"role":"user","content":"hi"}]}
                                        """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reportsUsageForATenant() throws Exception {
        mvc.perform(get("/v1/usage").header("Authorization", "Bearer " + FREE_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(5000))
                .andExpect(jsonPath("$.enforced").value(true));
    }

    /**
     * The free demo tenant has a deliberately small allowance so the budget path is
     * reachable in a test without generating a realistic volume of traffic.
     */
    @Test
    void stopsATenantThatRunsOutOfBudget() throws Exception {
        int rejected = 0;
        for (int i = 0; i < 12; i++) {
            // Each request must be a distinct prompt. Repeating one would hit the cache
            // from the second request onward, and cache hits deliberately do not consume
            // budget — so the tenant would never run out and this would test nothing.
            String longPrompt = "budget probe " + i + " " + "x".repeat(4000);

            int status =
                    mvc.perform(
                                    post("/v1/chat/completions")
                                            .header("Authorization", "Bearer " + FREE_KEY)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(body(longPrompt)))
                            .andReturn()
                            .getResponse()
                            .getStatus();
            if (status == 429) {
                rejected++;
            }
        }

        assertThat(rejected)
                .as("the free tier's 5000-token budget must eventually reject a request")
                .isPositive();
    }
}
