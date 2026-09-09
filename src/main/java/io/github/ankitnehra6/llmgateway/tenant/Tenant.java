package io.github.ankitnehra6.llmgateway.tenant;

/**
 * An authenticated caller.
 *
 * @param id stable identifier, used as the budget key and in logs
 * @param name human-readable label
 * @param tokenLimit tokens this tenant may spend per budget period
 */
public record Tenant(String id, String name, long tokenLimit) {

    public Tenant {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("tenant id is required");
        }
    }
}
