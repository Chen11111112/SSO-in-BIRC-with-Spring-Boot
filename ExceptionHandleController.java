package tw.edu.ntub.imd.birc.campus.activity.controller;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.InvalidPropertyException;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.util.StringUtils;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tw.edu.ntub.imd.birc.campus.activity.exception.*;
import tw.edu.ntub.imd.birc.campus.activity.exception.file.FileException;
import tw.edu.ntub.imd.birc.campus.activity.util.http.ResponseEntityBuilder;

@Log4j2
@RestControllerAdvice
public class ExceptionHandleController {
    private final Environment env;

    public ExceptionHandleController(Environment env) {
        this.env = env;
    }

    private boolean isDevEnv() {
        List<String> activeProfiles = Arrays.asList(env.getActiveProfiles());
        return !activeProfiles.contains("server") && !activeProfiles.contains("production");
    }

    @ExceptionHandler(ProjectException.class)
    public ResponseEntity<String> handleProjectException(ProjectException e) {
        return ResponseEntityBuilder.error(e).status(e.getHttpStatus()).build();
    }

    @ExceptionHandler(SsoException.class)
    public ResponseEntity<String> handleSsoException(SsoException e) {
        return ResponseEntityBuilder.error()
                .errorCode("SSO_ERROR")
                .message(e.getMessage())
                .status(e.getStatus())
                .build();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        Throwable cause = e.getCause();

        if (cause instanceof InvalidFormatException ex) {
            Class<?> targetType = ex.getTargetType();
            List<JsonMappingException.Reference> path = ex.getPath();

            String fieldName =
                    path.isEmpty() ? "unknown" : path.get(path.size() - 1).getFieldName();
            String displayName = fieldName;

            if (!path.isEmpty()) {
                Object fromObject = path.get(path.size() - 1).getFrom();
                if (fromObject != null) {
                    String schemaDesc = getFieldDescription(fromObject.getClass(), fieldName);
                    if (StringUtils.hasText(schemaDesc)) {
                        displayName = schemaDesc;
                    }
                }
            }

            boolean isNumberTarget = Number.class.isAssignableFrom(targetType) || targetType.isPrimitive();

            if (isNumberTarget) {
                String message = String.format("%s - \"%s\" 輸入內容包含非數字文字", displayName, ex.getValue());
                return ResponseEntityBuilder.error()
                        .errorCode("Common - InvalidFormat")
                        .message(message)
                        .status(HttpStatus.BAD_REQUEST)
                        .build();
            }

            if (targetType.isEnum()) {
                String validValues = Arrays.stream(targetType.getEnumConstants())
                        .map(Object::toString)
                        .collect(Collectors.joining(", "));
                String message = String.format("%s - \"%s\" 不是有效的值，有效值為：[%s]", displayName, ex.getValue(), validValues);
                return ResponseEntityBuilder.error()
                        .errorCode("Common - InvalidEnum")
                        .message(message)
                        .status(HttpStatus.BAD_REQUEST)
                        .build();
            }

            return ResponseEntityBuilder.error()
                    .errorCode("Common - InvalidRequestFormat")
                    .message(displayName + " 格式錯誤" + (isDevEnv() ? ": " + ex.getOriginalMessage() : ""))
                    .status(HttpStatus.BAD_REQUEST)
                    .build();
        }

        return ResponseEntityBuilder.error(new NullRequestBodyException(e)).build();
    }

    private String getFieldDescription(Class<?> clazz, String fieldName) {
        Field declaredField = null;
        Class<?> currentClass = clazz;

        while (currentClass != null && declaredField == null) {
            try {
                declaredField = currentClass.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                currentClass = currentClass.getSuperclass();
            }
        }

        if (declaredField != null && declaredField.isAnnotationPresent(Schema.class)) {
            Schema schema = declaredField.getAnnotation(Schema.class);
            return schema.description();
        }
        return null;
    }

    @ExceptionHandler(FileException.class)
    public ResponseEntity<String> handleFileException(FileException e) {
        return ResponseEntityBuilder.error(e).build();
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<String> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        return ResponseEntityBuilder.error()
                .errorCode("File - UploadTooLarge")
                .message("上傳檔案大小超過限制")
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .build();
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<String> handleHttpRequestMethodNotSupportedException(
            HttpServletRequest request, HttpRequestMethodNotSupportedException e) {
        return ResponseEntityBuilder.error(
                        new MethodNotSupportedException(request.getRequestURL().toString(), request.getMethod(), e))
                .build();
    }

    @ExceptionHandler(InvalidPropertyException.class)
    public ResponseEntity<String> handleInvalidPropertyException(InvalidPropertyException e) {
        return ResponseEntityBuilder.error(new ConvertPropertyException(e)).build();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> handleValidationExceptions(MethodArgumentNotValidException ex) {
        StringBuilder errors = new StringBuilder();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String errorMessage = error.getDefaultMessage();
            errors.append(errorMessage).append(", ");
        });
        String errorMessage = errors.toString();
        if (errorMessage.endsWith(", ")) {
            errorMessage = errorMessage.substring(0, errorMessage.length() - 2);
        }
        log.error("參數驗證失敗：{}", errorMessage);
        return ResponseEntityBuilder.error()
                .errorCode("Common - InvalidForm")
                .message(errorMessage)
                .status(HttpStatus.BAD_REQUEST)
                .build();
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<String> handleConstraintViolationException(ConstraintViolationException e) {
        Set<ConstraintViolation<?>> constraintViolations = e.getConstraintViolations();
        String message = constraintViolations.stream()
                .map(ConstraintViolation::getMessage)
                .findFirst()
                .orElse("輸入資料格式不正確");
        return ResponseEntityBuilder.error()
                .errorCode("Common - InvalidForm")
                .message(message)
                .status(HttpStatus.BAD_REQUEST)
                .build();
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<String> handleMissingServletRequestParameterException(
            MissingServletRequestParameterException e) {
        return ResponseEntityBuilder.error(new RequiredParameterException(e.getParameterName()))
                .build();
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<String> handleNoResourceFoundException(Exception e) {
        return ResponseEntityBuilder.error()
                .errorCode("NOT_FOUND")
                .message("找不到請求的資源")
                .status(HttpStatus.NOT_FOUND)
                .build();
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<String> handleAccessDeniedException(Exception e) {
        log.warn("權限不足被拒絕存取: {}", e.getMessage());
        return ResponseEntityBuilder.error()
                .errorCode("ACCESS_DENIED")
                .message("權限不足，拒絕存取")
                .status(HttpStatus.FORBIDDEN)
                .build();
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<String> handleAuthenticationException(AuthenticationException e) {
        log.warn("認證失敗: {}", e.getMessage());
        return ResponseEntityBuilder.error()
                .errorCode("UNAUTHORIZED")
                .message("尚未登入或憑證已失效")
                .status(HttpStatus.UNAUTHORIZED)
                .build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleException(Exception e) {
        log.error("未預期的系統錯誤: ", e);
        return ResponseEntityBuilder.error()
                .errorCode("UNKNOWN_ERROR")
                .message("系統內部錯誤，請聯絡管理員" + (isDevEnv() ? ": " + e.getMessage() : ""))
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .build();
    }
}
