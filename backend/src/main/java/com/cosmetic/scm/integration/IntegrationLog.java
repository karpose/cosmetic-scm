package com.cosmetic.scm.integration;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 对接日志：每次接口调用一行。 */
@Entity
@Table(name = "integration_log")
@Getter
@Setter
@NoArgsConstructor
public class IntegrationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String direction;

    private String platform;

    private String action;

    private String ref;

    private boolean success;

    private String message;

    private LocalDateTime createdAt;
}
