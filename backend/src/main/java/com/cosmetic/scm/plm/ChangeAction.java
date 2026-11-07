package com.cosmetic.scm.plm;

import java.time.LocalDate;
import java.time.LocalDateTime;

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

/** 变更的一项行动。全部完成才能关闭变更。 */
@Entity
@Table(name = "change_action")
@Getter
@Setter
@NoArgsConstructor
public class ChangeAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long changeId;

    private Integer lineNo;

    private String content;

    private String owner;

    private LocalDate dueDate;

    private boolean done;

    private String doneNote;

    private String doneBy;

    private LocalDateTime doneAt;
}
