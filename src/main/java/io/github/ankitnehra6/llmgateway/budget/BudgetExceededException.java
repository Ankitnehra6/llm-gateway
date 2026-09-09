package io.github.ankitnehra6.llmgateway.budget;

/** Raised when a tenant has spent its allowance for the current period. */
public class BudgetExceededException extends RuntimeException {

    private final String tenantId;
    private final long used;
    private final long limit;

    public BudgetExceededException(String tenantId, long used, long limit) {
        super("tenant %s has used %d of %d tokens this period".formatted(tenantId, used, limit));
        this.tenantId = tenantId;
        this.used = used;
        this.limit = limit;
    }

    public String tenantId() {
        return tenantId;
    }

    public long used() {
        return used;
    }

    public long limit() {
        return limit;
    }

    public long remaining() {
        return Math.max(0, limit - used);
    }
}
