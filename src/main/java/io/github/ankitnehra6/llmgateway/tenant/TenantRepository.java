package io.github.ankitnehra6.llmgateway.tenant;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<TenantEntity, String> {

    Optional<TenantEntity> findByApiKeyHash(String apiKeyHash);
}
