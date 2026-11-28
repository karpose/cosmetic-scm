package com.cosmetic.scm.transport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 成品的运输条件，由质量部门按标签和质量标准设定。一个成品一条。 */
@Entity
@Table(name = "transport_req")
@Getter
@Setter
@NoArgsConstructor
public class TransportReq {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long itemId;

    private BigDecimal tempMin;

    private BigDecimal tempMax;

    private boolean monitorRequired;

    private String handlingNote;

    private LocalDate effectiveFrom;

    private boolean active;

    private String createdBy;

    private LocalDateTime createdAt;

    private String updatedBy;

    private LocalDateTime updatedAt;
}
