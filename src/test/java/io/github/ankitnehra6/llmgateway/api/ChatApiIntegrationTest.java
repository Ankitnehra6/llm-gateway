package io.github.ankitnehra6.llmgateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.ankitnehra6.llmgateway.TestcontainersConfiguration;
import io.github.ankitnehra6.llmgateway.budget.UsageRepository;
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
                                .content(body("hello gateway")))
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
                                .content(body("via x-api-key")))
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
                                .content(body("accounted for")))
                .andExpect(status().isOk());

        assertThat(usage.count()).isEqualTo(before + 1);
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
        String longPrompt = "x".repeat(4000); // ~1000 tokens per request

        int rejected = 0;
        for (int i = 0; i < 12; i++) {
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
