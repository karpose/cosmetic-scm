package com.cosmetic.scm.oemportal;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 申报里受托厂代采原料的批号。 */
@Entity
@Table(name = "oem_report_material")
@Getter
@Setter
@NoArgsConstructor
public class OemReportMaterial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long reportId;

    private Long itemId;

    private String extBatchNo;

    private BigDecimal qty;
}
