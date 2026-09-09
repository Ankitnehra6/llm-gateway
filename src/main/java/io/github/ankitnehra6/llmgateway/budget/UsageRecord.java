package io.github.ankitnehra6.llmgateway.budget;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One row per served request, recording what it cost.
 *
 * <p>An append-only ledger rather than a running total on the tenant row. Totals can
 * always be derived from the ledger, but a ledger cannot be derived from a total: keeping
 * the individual rows is what makes per-model cost attribution, usage disputes and
 * retrospective budget changes answerable at all.
 */
@Entity
@Table(
        name = "usage_records",
        indexes = {
            // Every budget check is "sum tokens for this tenant since T", so the index
            // has to lead with tenant and then time.
            @Index(name = "idx_usage_tenant_time", columnList = "tenant_id, created_at")
        })
public class UsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "model", nullable = false, length = 128)
    private String model;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    /** Whether the answer came from cache, in which case no upstream tokens were spent. */
    @Column(name = "cache_hit", nullable = false)
    private boolean cacheHit;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UsageRecord() {
        // Required by JPA.
    }

    public UsageRecord(
            String tenantId,
            String provider,
            String model,
            int promptTokens,
            int completionTokens,
            boolean cacheHit,
            long latencyMs) {
        this.tenantId = tenantId;
        this.provider = provider;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.cacheHit = cacheHit;
        this.latencyMs = latencyMs;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public int getTotalTokens() {
        return promptTokens + completionTokens;
    }

    public boolean isCacheHit() {
        return cacheHit;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
