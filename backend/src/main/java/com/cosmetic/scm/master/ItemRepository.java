package com.cosmetic.scm.master;

import java.util.List;

import com.cosmetic.scm.domain.ItemType;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, Long> {

    boolean existsByCode(String code);

    List<Item> findByItemTypeOrderByCode(ItemType itemType);

    /** 只在写事务里用。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select x from Item x where x.id = :id")
    java.util.Optional<Item> lockById(@org.springframework.data.repository.query.Param("id") Long id);
}
