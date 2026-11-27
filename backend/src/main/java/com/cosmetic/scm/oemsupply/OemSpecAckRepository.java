package com.cosmetic.scm.oemsupply;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OemSpecAckRepository extends JpaRepository<OemSpecAck, Long> {

    Optional<OemSpecAck> findByFactoryIdAndSpecId(Long factoryId, Long specId);

    List<OemSpecAck> findByFactoryIdInAndItemIdIn(Collection<Long> factoryIds, Collection<Long> itemIds);

    List<OemSpecAck> findTop100ByOrderByIdDesc();

    /** 导出用：期间内的全部确认记录。 */
    List<OemSpecAck> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderById(LocalDateTime from, LocalDateTime to);
}
