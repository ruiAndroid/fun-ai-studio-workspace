package fun.ai.studio.common;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.List;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<?>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        BindingResult bindingResult = ex.getBindingResult();
        List<FieldError> fieldErrors = bindingResult.getFieldErrors();
        String message = fieldErrors.stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Result.error(message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<?>> handleIllegalArgumentException(IllegalArgumentException e) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Result.error(e.getMessage()));
    }

    @ExceptionHandler({MaxUploadSizeExceededException.class, MultipartException.class})
    public ResponseEntity<Result<?>> handleUploadTooLarge(Exception e) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Result.error(413, "上传文件过大：请压缩后重试，或联系管理员提高上传上限（当前建议 <= 200MB）。"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<?>> handleException(Exception e) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Result.error("系统错误：" + e.getMessage()));
    }
} 