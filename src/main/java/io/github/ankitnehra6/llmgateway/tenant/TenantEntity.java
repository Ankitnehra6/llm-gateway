package io.github.ankitnehra6.llmgateway.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Persistent tenant record. */
@Entity
@Table(name = "tenants")
public class TenantEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 64)
    private String id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /**
     * The API key is stored hashed, never in plaintext. A leaked database dump should not
     * hand over working credentials for every tenant.
     */
    @Column(name = "api_key_hash", nullable = false, unique = true, length = 64)
    private String apiKeyHash;

    @Column(name = "token_limit", nullable = false)
    private long tokenLimit;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TenantEntity() {
        // Required by JPA.
    }

    public TenantEntity(String id, String name, String apiKeyHash, long tokenLimit) {
        this.id = id;
        this.name = name;
        this.apiKeyHash = apiKeyHash;
        this.tokenLimit = tokenLimit;
        this.createdAt = Instant.now();
    }

    public Tenant toTenant() {
        return new Tenant(id, name, tokenLimit);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public long getTokenLimit() {
        return tokenLimit;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
