package com.cosmetic.scm.salesreturn;

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

/** 销售退货单。退回的货进入退货隔离区，等待质量人员逐行处置。 */
@Entity
@Table(name = "sales_return")
@Getter
@Setter
@NoArgsConstructor
public class SalesReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String returnNo;

    private Long customerId;

    private Long warehouseId;

    private Long recallId;

    private LocalDate returnDate;

    private String reason;

    private String createdBy;

    private LocalDateTime createdAt;
}
