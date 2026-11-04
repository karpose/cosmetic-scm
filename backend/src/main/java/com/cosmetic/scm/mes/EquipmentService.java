package com.cosmetic.scm.mes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.CalibrationResult;
import com.cosmetic.scm.domain.DeviationArea;
import com.cosmetic.scm.domain.DeviationLevel;
import com.cosmetic.scm.domain.EquipmentStatus;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.cosmetic.scm.qms.DeviationService;
import com.cosmetic.scm.security.CurrentUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 设备台账与校准。校准过期或停用的设备不能写进批记录（见 MesService）。
 * 校准不合格时设备自动停用，并生成重大偏差，列出上次合格以来用过它的工单，由质量部评估影响；
 * 系统不自动冻结批次，因为影响范围要人判断。
 */
@Service
@RequiredArgsConstructor
public class EquipmentService {

    /** 校准到期前多少天开始提醒。 */
    public static final int DUE_WARN_DAYS = 30;

    private final EquipmentRepository equipment;
    private final CalibrationRecordRepository calibrations;
    private final BatchRecordStepRepository steps;
    private final MfgOrderRepository orders;
    private final DeviationService deviations;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record EquipmentReq(@NotBlank(message = "请填写设备编号") @Size(max = 40) String code,
                               @NotBlank(message = "请填写设备名称") @Size(max = 100) String name,
                               @Size(max = 100) String location,
                               boolean needsCalibration,
                               @Min(1) Integer cycleMonths,
                               LocalDate nextDue) {
    }

    public record CalibrationReq(@NotNull(message = "请选择校准日期") LocalDate calibratedDate,
                                 @NotNull CalibrationResult result,
                                 @Size(max = 60) String certificateNo,
                                 LocalDate nextDue,
                                 @Size(max = 300) String note) {
    }

    public record StopReq(@NotBlank(message = "请填写停用原因") @Size(max = 300) String reason) {
    }

    public record CalibrationView(Long id, LocalDate calibratedDate, CalibrationResult result, String resultLabel,
                                  String certificateNo, LocalDate nextDue, String note, String doneBy, LocalDateTime doneAt) {
    }

    /** dueState: NONE（不需校准）/ OK / DUE（快到期）/ OVERDUE；usable：现在能不能写进批记录。 */
    public record EquipmentView(Long id, String code, String name, String location, boolean needsCalibration,
                                Integer cycleMonths, LocalDate nextDue, Long dueDaysLeft, String dueState, EquipmentStatus status,
                                String statusLabel, String stopReason, boolean usable, List<CalibrationView> calibrations) {
    }

    @Transactional(readOnly = true)
    public List<EquipmentView> list() {
        return equipment.findAllByOrderByCode().stream().map(this::toView).toList();
    }

    @Transactional
    public EquipmentView create(EquipmentReq req) {
        if (equipment.existsByCode(req.code().trim())) {
            throw new BizException("设备编号 " + req.code().trim() + " 已存在");
        }
        if (req.needsCalibration() && req.cycleMonths() == null) {
            throw new BizException("需要校准的设备请填写校准周期");
        }
        Equipment e = new Equipment();
        e.setCode(req.code().trim());
        e.setName(req.name().trim());
        e.setLocation(req.location() == null || req.location().isBlank() ? null : req.location().trim());
        e.setNeedsCalibration(req.needsCalibration());
        e.setCycleMonths(req.needsCalibration() ? req.cycleMonths() : null);
        e.setNextDue(req.needsCalibration() ? req.nextDue() : null);
        e.setStatus(EquipmentStatus.IN_USE);
        e.setCreatedBy(currentUser.usernameOrSystem());
        e.setCreatedAt(time.now());
        equipment.save(e);
        audit.log("新增设备", e.getCode(), e.getName() + (e.getNextDue() == null ? "" : "，校准到期 " + e.getNextDue()));
        return toView(e);
    }

    @Transactional
    public EquipmentView calibrate(Long id, CalibrationReq req) {
        Equipment e = require(id);
        if (!e.isNeedsCalibration()) {
            throw new BizException(e.getName() + " 不需要校准");
        }
        if (req.calibratedDate().isAfter(time.today())) {
            throw new BizException("校准日期不能晚于今天");
        }
        boolean pass = req.result() == CalibrationResult.PASS;
        if (pass && (req.certificateNo() == null || req.certificateNo().isBlank())) {
            throw new BizException("校准合格请填写证书或报告编号");
        }
        if (!pass && (req.note() == null || req.note().isBlank())) {
            throw new BizException("校准不合格请写明情况");
        }
        LocalDate next = !pass ? null
                : req.nextDue() != null ? req.nextDue() : req.calibratedDate().plusMonths(e.getCycleMonths());
        if (next != null && !next.isAfter(req.calibratedDate())) {
            throw new BizException("下次校准日期要晚于本次校准日期");
        }
        // 要在写入这次记录之前取：评估范围从上一次合格开始
        LocalDate lastPass = calibrations.findByEquipmentIdOrderByCalibratedDateDescIdDesc(id).stream()
                .filter(c -> c.getResult() == CalibrationResult.PASS).map(CalibrationRecord::getCalibratedDate).findFirst().orElse(null);
        CalibrationRecord c = new CalibrationRecord();
        c.setEquipmentId(id);
        c.setCalibratedDate(req.calibratedDate());
        c.setResult(req.result());
        c.setCertificateNo(req.certificateNo() == null || req.certificateNo().isBlank() ? null : req.certificateNo().trim());
        c.setNextDue(next);
        c.setNote(req.note() == null || req.note().isBlank() ? null : req.note().trim());
        c.setDoneBy(currentUser.usernameOrSystem());
        c.setDoneAt(time.now());
        calibrations.save(c);
        if (pass) {
            e.setNextDue(next);
            e.setStatus(EquipmentStatus.IN_USE);
            e.setStopReason(null);
        } else {
            e.setStatus(EquipmentStatus.STOPPED);
            e.setStopReason("校准不合格（" + req.calibratedDate() + "）");
            raiseDeviation(e, req, lastPass);
        }
        equipment.save(e);
        audit.log(pass ? "设备校准合格" : "设备校准不合格", e.getCode(), e.getName() + (c.getCertificateNo() == null ? "" : "，证书 " + c.getCertificateNo())
                + (next == null ? "，设备已停用" : "，下次 " + next), c.getNote());
        return toView(e);
    }

    @Transactional
    public EquipmentView stop(Long id, StopReq req) {
        Equipment e = require(id);
        e.setStatus(EquipmentStatus.STOPPED);
        e.setStopReason(req.reason().trim());
        equipment.save(e);
        audit.log("停用设备", e.getCode(), e.getName(), e.getStopReason());
        return toView(e);
    }

    @Transactional
    public EquipmentView resume(Long id) {
        Equipment e = require(id);
        e.setStatus(EquipmentStatus.IN_USE);
        e.setStopReason(null);
        equipment.save(e);
        audit.log("启用设备", e.getCode(), e.getName());
        return toView(e);
    }

    /** 批记录用：设备现在不能用时返回原因，能用返回 null。 */
    String unusableReason(Equipment e) {
        if (e.getStatus() != EquipmentStatus.IN_USE) {
            return e.getName() + " 已停用" + (e.getStopReason() == null ? "" : "：" + e.getStopReason());
        }
        if (e.isNeedsCalibration()) {
            if (e.getNextDue() == null) {
                return e.getName() + " 还没有校准记录";
            }
            if (e.getNextDue().isBefore(time.today())) {
                return e.getName() + " 的校准已于 " + e.getNextDue() + " 到期";
            }
        }
        return null;
    }

    private void raiseDeviation(Equipment e, CalibrationReq req, LocalDate lastPass) {
        List<String> used = steps.findByEquipmentId(e.getId()).stream()
                .filter(s -> s.getDoneAt() != null && (lastPass == null || !s.getDoneAt().toLocalDate().isBefore(lastPass)))
                .map(BatchRecordStep::getMoId).distinct()
                .map(moId -> orders.findById(moId).map(MfgOrder::getMoNo).orElse("—")).toList();
        String scope = used.isEmpty() ? "上次合格以来没有批记录用过这台设备。"
                : "上次合格" + (lastPass == null ? "" : "（" + lastPass + "）") + "以来用过的工单：" + String.join("、", used) + "，请评估影响。";
        String description = req.note().trim() + "。" + scope;
        deviations.create(new DeviationService.DeviationReq(req.calibratedDate(), DeviationArea.QC, DeviationLevel.MAJOR, null,
                e.getName() + "（" + e.getCode() + "）校准不合格", description.length() > 1000 ? description.substring(0, 1000) : description,
                "设备已自动停用", time.today().plusDays(15)));
    }

    private Equipment require(Long id) {
        return equipment.findById(id).orElseThrow(() -> BizException.notFound("设备"));
    }

    private EquipmentView toView(Equipment e) {
        Long days = e.getNextDue() == null ? null : ChronoUnit.DAYS.between(time.today(), e.getNextDue());
        String state = !e.isNeedsCalibration() ? "NONE" : days == null ? "OVERDUE" : days < 0 ? "OVERDUE" : days <= DUE_WARN_DAYS ? "DUE" : "OK";
        List<CalibrationView> history = calibrations.findByEquipmentIdOrderByCalibratedDateDescIdDesc(e.getId()).stream()
                .map(c -> new CalibrationView(c.getId(), c.getCalibratedDate(), c.getResult(), c.getResult().getLabel(), c.getCertificateNo(),
                        c.getNextDue(), c.getNote(), c.getDoneBy(), c.getDoneAt())).toList();
        return new EquipmentView(e.getId(), e.getCode(), e.getName(), e.getLocation(), e.isNeedsCalibration(), e.getCycleMonths(), e.getNextDue(),
                days, state, e.getStatus(), e.getStatus().getLabel(), e.getStopReason(), unusableReason(e) == null, history);
    }
}
