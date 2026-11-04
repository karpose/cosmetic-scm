package com.cosmetic.scm.production;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.MoType;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MfgOrderRepository extends JpaRepository<MfgOrder, Long> {

    List<MfgOrder> findTop300ByOrderByIdDesc();

    List<MfgOrder> findAllByOrderByIdDesc();

    Optional<MfgOrder> findByMoNo(String moNo);

    List<MfgOrder> findByStatusIn(Collection<MoStatus> statuses);

    List<MfgOrder> findByFactoryIdAndMoTypeOrderByIdDesc(Long factoryId, MoType moType);
}
