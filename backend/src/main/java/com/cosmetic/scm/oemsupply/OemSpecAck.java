package com.cosmetic.scm.oemsupply;

import java.time.LocalDateTime;

import com.cosmetic.scm.domain.SpecAckMethod;

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

/** 受托厂确认收到一版物料验收标准（门户确认或线下交接）。只增不改。 */
@Entity
@Table(name = "oem_spec_ack")
@Getter
@Setter
@NoArgsConstructor
public class OemSpecAck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long factoryId;

    private Long specId;

    private Long itemId;

    private Integer specVersion;

    @Enumerated(EnumType.STRING)
    private SpecAckMethod method;

    private String receiver;

    private String note;

    private String createdBy;

    private LocalDateTime createdAt;
}
