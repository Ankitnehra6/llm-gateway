package io.github.ankitnehra6.llmgateway.budget;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsageRepository extends JpaRepository<UsageRecord, Long> {

    /**
     * Tokens a tenant has spent since a point in time.
     *
     * <p>Cache hits are excluded: they cost no upstream tokens, and charging for them
     * would penalise tenants for the gateway working well. COALESCE keeps the return
     * non-null for a tenant that has never sent a request.
     */
    @Query(
            """
            select coalesce(sum(u.promptTokens + u.completionTokens), 0)
            from UsageRecord u
            where u.tenantId = :tenantId
              and u.createdAt >= :since
              and u.cacheHit = false
            """)
    long sumTokensSince(@Param("tenantId") String tenantId, @Param("since") Instant since);
}
