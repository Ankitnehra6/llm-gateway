package io.github.ankitnehra6.llmgateway.budget;

import io.github.ankitnehra6.llmgateway.config.GatewayProperties;
import io.github.ankitnehra6.llmgateway.tenant.Tenant;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enforces and records per-tenant token spend.
 *
 * <p>The check is deliberately a pre-flight estimate rather than a reservation. Holding a
 * token reservation across an upstream call would need a two-phase commit against a
 * provider that has no notion of one, and the failure mode — a crashed gateway leaving
 * phantom reservations that lock a tenant out — is worse than the one it prevents.
 *
 * <p>The accepted consequence is that concurrent requests can overshoot a limit slightly:
 * several may pass the check before any of them records its usage. That is bounded by the
 * cost of the requests already in flight, which for a token budget is an acceptable
 * overshoot. A hard financial cap would need the reservation.
 */
@Service
public class BudgetService {

    private final UsageRepository usage;
    private final GatewayProperties properties;
    private final Clock clock;

    public BudgetService(UsageRepository usage, GatewayProperties properties, Clock clock) {
        this.usage = usage;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Rejects the request if the tenant is already over budget.
     *
     * @throws BudgetExceededException if the allowance for the current period is spent
     */
    @Transactional(readOnly = true)
    public BudgetStatus checkAffordable(Tenant tenant) {
        GatewayProperties.Budget budget = properties.budget();
        long limit = effectiveLimit(tenant, budget);

        if (!budget.enabled()) {
            return new BudgetStatus(0, limit, false);
        }

        Instant since = Instant.now(clock).minus(budget.period());
        long used = usage.sumTokensSince(tenant.id(), since);

        if (used >= limit) {
            throw new BudgetExceededException(tenant.id(), used, limit);
        }
        return new BudgetStatus(used, limit, true);
    }

    /** Records what a served request actually cost. */
    @Transactional
    public void record(UsageRecord record) {
        usage.save(record);
    }

    /** Current spend for a tenant, without throwing when over. */
    @Transactional(readOnly = true)
    public BudgetStatus status(Tenant tenant) {
        GatewayProperties.Budget budget = properties.budget();
        long limit = effectiveLimit(tenant, budget);
        Instant since = Instant.now(clock).minus(budget.period());
        return new BudgetStatus(usage.sumTokensSince(tenant.id(), since), limit, budget.enabled());
    }

    /** A tenant's own limit wins; zero or unset falls back to the configured default. */
    private long effectiveLimit(Tenant tenant, GatewayProperties.Budget budget) {
        return tenant.tokenLimit() > 0 ? tenant.tokenLimit() : budget.defaultTokenLimit();
    }

    /**
     * @param used tokens spent in the current period
     * @param limit the allowance
     * @param enforced whether exceeding the limit actually blocks requests
     */
    public record BudgetStatus(long used, long limit, boolean enforced) {

        public long remaining() {
            return Math.max(0, limit - used);
        }
    }
}
