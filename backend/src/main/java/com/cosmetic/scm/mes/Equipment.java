package com.cosmetic.scm.mes;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.EquipmentStatus;

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

/** 生产和检验设备。校准过期或停用的设备不能用于批记录。 */
@Entity
@Table(name = "equipment")
@Getter
@Setter
@NoArgsConstructor
public class Equipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;

    private String name;

    private String location;

    private boolean needsCalibration;

    private Integer cycleMonths;

    private LocalDate nextDue;

    @Enumerated(EnumType.STRING)
    private EquipmentStatus status;

    private String stopReason;

    private String createdBy;

    private LocalDateTime createdAt;
}
