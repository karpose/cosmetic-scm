package com.cosmetic.scm.purchase;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cosmetic.scm.domain.ShipmentStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PoShipmentRepository extends JpaRepository<PoShipment, Long> {

    List<PoShipment> findByPoIdInOrderByIdDesc(Collection<Long> poIds);

    List<PoShipment> findByStatusOrderById(ShipmentStatus status);

    @Query("select x.id from PoShipment x where x.poId = :poId and x.status = :status order by x.id")
    List<Long> findIdsByPoIdAndStatus(@Param("poId") Long poId, @Param("status") ShipmentStatus status);

    /** 只在写事务里用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from PoShipment x where x.id = :id")
    Optional<PoShipment> lockById(@Param("id") Long id);
}
