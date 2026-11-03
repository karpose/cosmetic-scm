package com.cosmetic.scm.integration;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelOrderLineRepository extends JpaRepository<ChannelOrderLine, Long> {

    List<ChannelOrderLine> findByOrderIdIn(Collection<Long> orderIds);
}
