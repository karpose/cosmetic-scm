package com.cosmetic.scm.master;

import java.util.List;

import com.cosmetic.scm.domain.ItemType;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, Long> {

    boolean existsByCode(String code);

    List<Item> findByItemTypeOrderByCode(ItemType itemType);
}
