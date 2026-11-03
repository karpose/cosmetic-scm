package com.cosmetic.scm.dealer;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.DealerOrderStatus;

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

/** 经销商在门户提交的订货单；品牌方审核通过时生成销售出库单。 */
@Entity
@Table(name = "dealer_order")
@Getter
@Setter
@NoArgsConstructor
public class DealerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String orderNo;

    private Long customerId;

    @Enumerated(EnumType.STRING)
    private DealerOrderStatus status;

    private LocalDate requestedDate;

    private String note;

    private Long soId;

    private String reviewNote;

    private String reviewedBy;

    private LocalDateTime reviewedAt;

    private String createdBy;

    private LocalDateTime createdAt;
}
