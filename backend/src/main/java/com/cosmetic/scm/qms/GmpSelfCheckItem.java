package com.cosmetic.scm.qms;

import java.time.LocalDateTime;

import com.cosmetic.scm.domain.SelfCheckResult;

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

/** 自查里的一个检查项目。项目内容在 {@link GmpChecklist}，这里只存结果。 */
@Entity
@Table(name = "gmp_self_check_item")
@Getter
@Setter
@NoArgsConstructor
public class GmpSelfCheckItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long checkId;

    private Integer itemNo;

    @Enumerated(EnumType.STRING)
    private SelfCheckResult result;

    /** 发现的问题；不适用时写原因。 */
    private String finding;

    /** 查看了哪些记录、文件。 */
    private String evidence;

    private Long capaId;

    private String checkedBy;

    private LocalDateTime checkedAt;
}
