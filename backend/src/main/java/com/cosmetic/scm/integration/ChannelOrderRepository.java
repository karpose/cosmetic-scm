package com.cosmetic.scm.integration;

import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.ChannelOrderStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelOrderRepository extends JpaRepository<ChannelOrder, Long> {

    List<ChannelOrder> findTop300ByOrderByIdDesc();

    Optional<ChannelOrder> findByPlatformAndPlatformOrderNo(String platform, String platformOrderNo);

    List<ChannelOrder> findByStatus(ChannelOrderStatus status);
}
