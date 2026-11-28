package com.cosmetic.scm.transport;

import com.cosmetic.scm.domain.TransportDocType;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 运输记录包含的一张单据（出库单或委托单）。dupKey 在记录作废后清空，一张单据只能在一条有效记录里。 */
@Entity
@Table(name = "transport_doc")
@Getter
@Setter
@NoArgsConstructor
public class TransportDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long recordId;

    @Enumerated(EnumType.STRING)
    private TransportDocType docType;

    private Long docId;

    private String dupKey;

    public static String key(TransportDocType type, Long id) {
        return type.name() + ":" + id;
    }
}
