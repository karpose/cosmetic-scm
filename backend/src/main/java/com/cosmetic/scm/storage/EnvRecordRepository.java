package com.cosmetic.scm.storage;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface EnvRecordRepository extends JpaRepository<EnvRecord, Long> {

    boolean existsByDupKey(String dupKey);

    /** 区域已锁住后再锁记录（作废），路径带区域 id。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from EnvRecord r where r.id = :id and r.areaId = :areaId")
    Optional<EnvRecord> lockByIdAndAreaId(@Param("id") Long id, @Param("areaId") Long areaId);

    /** 导入查重：这段时间里已有的有效记录的时间。 */
    @Query("select r.recordedAt from EnvRecord r where r.areaId = :areaId and r.voided = false and r.recordedAt between :from and :to")
    List<LocalDateTime> takenTimes(@Param("areaId") Long areaId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    Optional<EnvRecord> findFirstByAreaIdAndVoidedFalseOrderByRecordedAtDescIdDesc(Long areaId);

    List<EnvRecord> findTop1000ByRecordedOnBetweenOrderByRecordedAtDescIdDesc(LocalDate from, LocalDate to);

    List<EnvRecord> findTop1000ByAreaIdAndRecordedOnBetweenOrderByRecordedAtDescIdDesc(Long areaId, LocalDate from, LocalDate to);

    long countByRecordedOnBetween(LocalDate from, LocalDate to);

    long countByAreaIdAndRecordedOnBetween(Long areaId, LocalDate from, LocalDate to);

    /** 趋势图：一个区域一段时间的有效记录，按时间先后。 */
    List<EnvRecord> findTop20000ByAreaIdAndVoidedFalseAndRecordedOnBetweenOrderByRecordedAtAsc(Long areaId, LocalDate from, LocalDate to);

    /** 导出：一段时间的全部记录（含作废的）。 */
    List<EnvRecord> findByRecordedOnBetweenOrderByAreaIdAscRecordedAtAscIdAsc(LocalDate from, LocalDate to);

    List<EnvRecord> findByExcursionIdAndVoidedFalseOrderByRecordedAtAsc(Long excursionId);

    /** 按区域、日期分组：有效记录数、超限数。自查和区域页的漏记、超限都用它，不整表读。 */
    @Query("select r.areaId, r.recordedOn, count(r), sum(case when r.inRange = false then 1 else 0 end) from EnvRecord r "
            + "where r.voided = false and r.recordedOn between :from and :to group by r.areaId, r.recordedOn")
    List<Object[]> dayStats(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 各区域最近一条有效记录的时间。 */
    @Query("select r.areaId, max(r.recordedAt) from EnvRecord r where r.voided = false and r.areaId in :ids group by r.areaId")
    List<Object[]> lastRecordedAt(@Param("ids") Collection<Long> ids);
}
