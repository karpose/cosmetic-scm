package com.cosmetic.scm.plm;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 某人确认已阅读某一版受控文件。只增不改。 */
@Entity
@Table(name = "doc_ack")
@Getter
@Setter
@NoArgsConstructor
public class DocAck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long revisionId;

    private String username;

    private LocalDateTime ackedAt;
}
