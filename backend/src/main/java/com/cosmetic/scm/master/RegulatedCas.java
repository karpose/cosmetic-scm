package com.cosmetic.scm.master;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 法规目录条目的一个有效 CAS 号（导入时从 CAS 列解析，校验位不对的不写进来）。 */
@Entity
@Table(name = "regulated_cas")
@Getter
@Setter
@NoArgsConstructor
public class RegulatedCas {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long substanceId;

    private String cas;
}
