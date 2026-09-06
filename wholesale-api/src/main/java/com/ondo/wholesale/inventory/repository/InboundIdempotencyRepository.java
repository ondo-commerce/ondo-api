package com.ondo.wholesale.inventory.repository;

import com.ondo.wholesale.inventory.domain.InboundIdempotency;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboundIdempotencyRepository
        extends JpaRepository<InboundIdempotency, InboundIdempotency.Key> {
}
