package com.garment.factory.common;

import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务校验失败：400 + 中文业务提示
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(e.getMessage()));
    }

    /**
     * 状态冲突 / 并发竞争失败 / 重复交接接收：409 + 中文提示
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail(e.getMessage()));
    }

    /**
     * 乐观锁冲突（量体记录并发编辑等）：统一 409，前端提示刷新后重试，
     * 避免后提交的人不知情地覆盖先提交的版本。
     */
    @ExceptionHandler({org.springframework.orm.ObjectOptimisticLockingFailureException.class,
            org.springframework.dao.OptimisticLockingFailureException.class,
            jakarta.persistence.OptimisticLockException.class})
    public ResponseEntity<ApiResponse<Void>> handleOptimistic(Exception e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail("数据刚被他人修改或删除，请刷新后基于最新版本重试"));
    }

    /**
     * 参数注解校验失败：400 + 字段名与提示
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(message.isEmpty() ? "请求参数校验失败" : message));
    }

    /**
     * 请求体格式错误：400
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail("请求体格式有误，请检查 JSON 字段类型"));
    }

    /**
     * 唯一约束冲突：400（避免在页面上抛出非人话的外键报错）
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleIntegrity(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail("存在唯一性约束冲突，请检查编号、手机号是否重复"));
    }

    /**
     * 悲观锁等待超时（下单与编辑/删除竞争）：按并发冲突 409 处理，
     * 让操作人刷新重试，而不是落到 500。
     */
    @ExceptionHandler({org.springframework.dao.CannotAcquireLockException.class,
            org.springframework.dao.PessimisticLockingFailureException.class})
    public ResponseEntity<ApiResponse<Void>> handleLock(Exception e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail("数据正被其他操作占用（下单/编辑/删除并发），请刷新后重试"));
    }

    /**
     * 兜底：500
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.fail("服务异常：" + detail));
    }
}
