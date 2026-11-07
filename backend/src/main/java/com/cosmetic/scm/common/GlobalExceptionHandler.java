package com.cosmetic.scm.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 统一错误返回：{ "message": "..." } */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ApiError(String message) {
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiError> biz(BizException e) {
        return ResponseEntity.status(e.getStatus()).body(new ApiError(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        return ResponseEntity.badRequest().body(new ApiError(fe == null ? "提交的数据不完整" : describe(fe)));
    }

    /** 注解里写了中文说明的（如“请选择产品”）直接显示；默认提示（如“不能为空”）要加上是哪一项。不能把 lines[0].qty 这种字段名给用户看。 */
    private static String describe(FieldError fe) {
        boolean custom = fe.contains(jakarta.validation.ConstraintViolation.class)
                && !fe.unwrap(jakarta.validation.ConstraintViolation.class).getMessageTemplate().startsWith("{");
        if (custom) {
            return fe.getDefaultMessage();
        }
        java.util.regex.Matcher row = LINE_FIELD.matcher(fe.getField());
        String prefix = row.find() ? "第 " + (Integer.parseInt(row.group(1)) + 1) + " 行" : "";
        String last = fe.getField().substring(fe.getField().lastIndexOf('.') + 1).replaceAll("\\[\\d+]", "");
        return prefix + FIELD_LABELS.getOrDefault(last, last) + "：" + fe.getDefaultMessage();
    }

    private static final java.util.regex.Pattern LINE_FIELD = java.util.regex.Pattern.compile("\\[(\\d+)]");

    private static final java.util.Map<String, String> FIELD_LABELS = java.util.Map.ofEntries(
            java.util.Map.entry("unitPrice", "单价"), java.util.Map.entry("taxRate", "税率"), java.util.Map.entry("leadDays", "交货天数"), java.util.Map.entry("validUntil", "报价有效期"), java.util.Map.entry("deadline", "截止日期"), java.util.Map.entry("invoiceNo", "发票号"), java.util.Map.entry("invoiceDate", "发票日期"), java.util.Map.entry("amount", "金额"), java.util.Map.entry("payDate", "付款日期"), java.util.Map.entry("periodTo", "对账截止日期"), java.util.Map.entry("supplierIds", "供应商"), java.util.Map.entry("rfqLineId", "询价行"), java.util.Map.entry("adjustment", "调整金额"), java.util.Map.entry("adjustmentNote", "调整原因"), java.util.Map.entry("referenceNo", "付款凭证号"), java.util.Map.entry("title", "标题"),
            java.util.Map.entry("inciName", "INCI 名称"), java.util.Map.entry("maxPct", "浓度上限"), java.util.Map.entry("evaluator", "评估人"), java.util.Map.entry("docDate", "日期"), java.util.Map.entry("docType", "资料类型"), java.util.Map.entry("claim", "宣称内容"), java.util.Map.entry("catalog", "类别"), java.util.Map.entry("source", "来源"), java.util.Map.entry("cnName", "中文名"), java.util.Map.entry("casNo", "CAS 号"),
            java.util.Map.entry("promisedDate", "交期"), java.util.Map.entry("shipDate", "发货日期"), java.util.Map.entry("extBatchNo", "批号"),
            java.util.Map.entry("mfgDate", "生产日期"), java.util.Map.entry("poLineId", "采购行"),
            java.util.Map.entry("code", "编码"), java.util.Map.entry("name", "名称"), java.util.Map.entry("username", "账号"),
            java.util.Map.entry("displayName", "姓名"), java.util.Map.entry("password", "密码"), java.util.Map.entry("unit", "单位"),
            java.util.Map.entry("inci", "INCI 名称"), java.util.Map.entry("qty", "数量"), java.util.Map.entry("itemId", "物料"),
            java.util.Map.entry("batchId", "批次"), java.util.Map.entry("itemType", "物料类型"), java.util.Map.entry("kind", "类别"),
            java.util.Map.entry("licenseNo", "资质编号"), java.util.Map.entry("licenseScope", "许可范围"), java.util.Map.entry("contact", "联系人"),
            java.util.Map.entry("address", "地址"), java.util.Map.entry("channel", "渠道"), java.util.Map.entry("filingNo", "备案编号"),
            java.util.Map.entry("shelfMonths", "保质期"), java.util.Map.entry("minShelfPct", "剩余效期接收标准"), java.util.Map.entry("supplyMode", "供料方式"),
            java.util.Map.entry("qtyPer", "单耗"), java.util.Map.entry("lines", "明细"), java.util.Map.entry("warehouseId", "仓库"),
            java.util.Map.entry("reason", "原因"), java.util.Map.entry("note", "说明"), java.util.Map.entry("area", "区域"),
            java.util.Map.entry("level", "等级"), java.util.Map.entry("sourceType", "来源"), java.util.Map.entry("actionType", "措施类型"),
            java.util.Map.entry("disposition", "处置方式"), java.util.Map.entry("decision", "结论"), java.util.Map.entry("filingType", "备案类型"),
            java.util.Map.entry("orderNo", "订单号"), java.util.Map.entry("sku", "SKU"), java.util.Map.entry("platform", "平台"),
            java.util.Map.entry("orders", "订单"), java.util.Map.entry("roles", "角色"), java.util.Map.entry("email", "邮箱"), java.util.Map.entry("severity", "严重程度"));

    /** 地址写错、参数缺失或类型不对是调用方的问题，不是系统错误，不该进错误日志。 */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiError> noResource(org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND).body(new ApiError("接口不存在"));
    }

    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> badParam(Exception e) {
        return ResponseEntity.badRequest().body(new ApiError("请求参数不正确"));
    }

    /** 客户端发错了请求格式，不是系统故障；否则会落到兜底处理，返回 500 和“系统内部错误”。 */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> badMediaType(Exception e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(new ApiError("提交的数据格式不正确"));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> badMethod(Exception e) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED).body(new ApiError("不支持的请求方法"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.badRequest().body(new ApiError("文件太大，单个附件不能超过 20 MB"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new ApiError("提交的数据格式不正确"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> denied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError("你的角色没有执行此操作的权限"));
    }

    /** 乐观锁冲突、数据库死锁、等锁超时都属于这一类：不是系统故障，重试即可。 */
    @ExceptionHandler(org.springframework.dao.ConcurrencyFailureException.class)
    public ResponseEntity<ApiError> conflict(org.springframework.dao.ConcurrencyFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("数据刚被其他人修改，请刷新后重试"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> integrity(DataIntegrityViolationException e) {
        log.warn("数据完整性冲突: {}", e.getMostSpecificCause().getMessage());
        String cause = String.valueOf(e.getMostSpecificCause().getMessage());
        if (cause.contains("Data too long") || cause.contains("Out of range") || cause.contains("Data truncation")
                || cause.contains("Value too long") || cause.contains("out of range")) {
            return ResponseEntity.badRequest().body(new ApiError("内容超出了允许的长度或数值范围，请改短或改小后重试"));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("数据冲突，可能是编码或单号重复，请检查后重试"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> other(Exception e) {
        log.error("未处理的异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError("系统内部错误，请联系管理员"));
    }
}
