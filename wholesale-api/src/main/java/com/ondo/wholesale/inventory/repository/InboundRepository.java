package com.ondo.wholesale.inventory.repository;

import com.ondo.wholesale.inventory.domain.Inbound;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboundRepository extends JpaRepository<Inbound, Long> {
}
