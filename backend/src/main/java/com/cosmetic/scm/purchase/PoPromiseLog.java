package com.cosmetic.scm.purchase;

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

/** 供应商每次回复交期的记录，只增不改。 */
@Entity
@Table(name = "po_promise_log")
@Getter
@Setter
@NoArgsConstructor
public class PoPromiseLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long poId;

    private LocalDate promisedDate;

    private String note;

    private String createdBy;

    private LocalDateTime createdAt;
}
