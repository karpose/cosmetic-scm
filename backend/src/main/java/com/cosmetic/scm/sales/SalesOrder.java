package com.cosmetic.scm.sales;

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

/** 销售出库单。一期只有“已出库”一种状态：建单即按 FEFO 分配并扣库存。 */
@Entity
@Table(name = "sales_order")
@Getter
@Setter
@NoArgsConstructor
public class SalesOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String soNo;

    private Long customerId;

    private Long warehouseId;

    private LocalDate orderDate;

    private String status;

    private String createdBy;

    private LocalDateTime createdAt;

    /** 出库时的购买者名称、地址、联系方式（销售记录用，V50 起记录；之前的出库为空，按当前主数据显示）。 */
    private String buyerName;

    private String buyerAddress;

    private String buyerContact;
}
