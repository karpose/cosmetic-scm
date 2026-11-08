package com.cosmetic.scm.master;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 法规目录条目的一个别名。对照键和 INCI 一样用 RegulatoryService.key 折叠。 */
@Entity
@Table(name = "regulated_alias")
@Getter
@Setter
@NoArgsConstructor
public class RegulatedAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long substanceId;

    private String aliasKey;
}
