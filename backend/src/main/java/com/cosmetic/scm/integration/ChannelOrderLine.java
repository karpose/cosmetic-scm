package com.cosmetic.scm.integration;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 电商订单明细，SKU 按物料编码匹配。 */
@Entity
@Table(name = "channel_order_line")
@Getter
@Setter
@NoArgsConstructor
public class ChannelOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long orderId;

    private String sku;

    private Long itemId;

    private BigDecimal qty;
}
