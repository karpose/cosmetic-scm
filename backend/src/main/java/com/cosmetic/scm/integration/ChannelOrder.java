package com.cosmetic.scm.integration;

import java.time.LocalDateTime;

import com.cosmetic.scm.domain.ChannelOrderStatus;

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

/** 电商 ERP 推送的订单，同一平台同一订单号只收一次。 */
@Entity
@Table(name = "channel_order")
@Getter
@Setter
@NoArgsConstructor
public class ChannelOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String platform;

    private String platformOrderNo;

    private Long customerId;

    private Long warehouseId;

    @Enumerated(EnumType.STRING)
    private ChannelOrderStatus status;

    private Long soId;

    private String error;

    private int attempts;

    private LocalDateTime orderedAt;

    private LocalDateTime receivedAt;

    private LocalDateTime updatedAt;
}
