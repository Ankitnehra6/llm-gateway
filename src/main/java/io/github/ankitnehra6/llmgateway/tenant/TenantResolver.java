package io.github.ankitnehra6.llmgateway.tenant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maps an API key to the tenant that owns it.
 *
 * <p>Keys are looked up by SHA-256 hash so plaintext credentials are never stored. This is
 * a lookup key, not a password: the hash is unsalted and unstretched deliberately, because
 * it must be computable in one step on every request. That is safe here only because API
 * keys are long random strings rather than user-chosen secrets — a leaked hash of a
 * 256-bit random key is not brute-forceable, whereas a leaked hash of a password is.
 */
@Service
public class TenantResolver {

    private final TenantRepository repository;

    public TenantResolver(TenantRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<Tenant> resolve(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        return repository.findByApiKeyHash(hash(apiKey)).map(TenantEntity::toTenant);
    }

    /** Hashes an API key for storage or lookup. */
    public static String hash(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the platform; its absence is unrecoverable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
