package com.cosmetic.scm.recall;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 召回通知：每个收货客户一行，记录通知时间和通知人。 */
@Entity
@Table(name = "recall_notice")
@Getter
@Setter
@NoArgsConstructor
public class RecallNotice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long recallId;

    private Long customerId;

    private BigDecimal shippedQty;

    private LocalDateTime notifiedAt;

    private String notifiedBy;

    private String note;
}
