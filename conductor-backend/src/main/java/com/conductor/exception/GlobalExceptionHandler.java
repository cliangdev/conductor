package com.conductor.exception;

import com.conductor.knowledge.page.FrontmatterException;
import com.conductor.knowledge.page.KnowledgeConflictException;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ClassCastException.class)
    public ProblemDetail handleClassCastException(ClassCastException e) {
        log.error("ClassCastException in controller — likely wrong auth token type: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setType(URI.create("about:blank"));
        problem.setDetail("Internal authentication configuration error");
        return problem;
    }

    @ExceptionHandler(FirebaseAuthenticationException.class)
    public ProblemDetail handleFirebaseAuthException(FirebaseAuthenticationException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(URI.create("about:blank"));
        problem.setDetail("Invalid Firebase token");
        return problem;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDeniedException(AccessDeniedException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create("about:blank"));
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler({EntityNotFoundException.class, NoSuchElementException.class})
    public ProblemDetail handleNotFoundException(RuntimeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("about:blank"));
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusinessException(BusinessException e) {
        log.warn("BusinessException: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("about:blank"));
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflictException(ConflictException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("about:blank"));
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler(UnprocessableEntityException.class)
    public ProblemDetail handleUnprocessableEntityException(UnprocessableEntityException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(URI.create("about:blank"));
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler(FrontmatterException.class)
    public ProblemDetail handleFrontmatterException(FrontmatterException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(URI.create("about:blank"));
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler(KnowledgeConflictException.class)
    public ProblemDetail handleKnowledgeConflictException(KnowledgeConflictException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("about:blank"));
        problem.setDetail(e.getMessage());
        List<Map<String, Object>> conflicts = e.conflicts().stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("path", c.path());
                    m.put("currentVersion", c.currentVersion());
                    m.put("currentContent", c.currentContent());
                    return m;
                })
                .collect(Collectors.toList());
        problem.setProperty("conflicts", conflicts);
        return problem;
    }

    @ExceptionHandler(InviteExpiredException.class)
    public ProblemDetail handleInviteExpiredException(InviteExpiredException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.GONE);
        problem.setType(URI.create("about:blank"));
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler(ForbiddenException.class)
    public ProblemDetail handleForbiddenException(ForbiddenException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create("about:blank"));
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler(CliNotReachableException.class)
    public ProblemDetail handleCliNotReachableException(CliNotReachableException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
        problem.setType(URI.create("about:blank"));
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler(StorageUploadException.class)
    public ProblemDetail handleStorageUploadException(StorageUploadException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setType(URI.create("about:blank"));
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler(FileTooLargeException.class)
    public ProblemDetail handleFileTooLargeException(FileTooLargeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.PAYLOAD_TOO_LARGE);
        problem.setType(URI.create("about:blank"));
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler(DiscordWebhookException.class)
    public ProblemDetail handleDiscordWebhookException(DiscordWebhookException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
        problem.setType(URI.create("about:blank"));
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler(CredentialEncryptionException.class)
    public ProblemDetail handleCredentialEncryptionException(CredentialEncryptionException e) {
        log.error("Credential encryption error", e);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setType(URI.create("about:blank"));
        problem.setDetail("Credential encryption service unavailable. Please try again.");
        return problem;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(NoResourceFoundException e) {
        // Unmapped path (e.g. a retired endpoint like the old /issues surface). Spring throws
        // NoResourceFoundException, which would otherwise fall through to the catch-all as a 500 — an
        // unmapped route is a client error, so surface 404.
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("about:blank"));
        problem.setDetail("Resource not found");
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableMessage(HttpMessageNotReadableException e) {
        // Malformed/unbindable request body (e.g. a JSON array where an object is expected). A body the
        // server can't parse is a client error — 400, not the catch-all 500.
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("about:blank"));
        problem.setDetail("Malformed or unreadable request body");
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpectedException(Exception e) {
        log.error("Unexpected error", e);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setType(URI.create("about:blank"));
        problem.setDetail("An unexpected error occurred. Please try again.");
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationException(MethodArgumentNotValidException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("about:blank"));
        problem.setDetail("Validation failed");

        List<Map<String, String>> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of(
                        "field", fe.getField(),
                        "message", defaultMessage(fe)))
                .collect(Collectors.toList());

        problem.setProperty("fieldErrors", fieldErrors);
        return problem;
    }

    private String defaultMessage(FieldError fe) {
        return fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value";
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        // Wrong HTTP verb for an existing path (e.g. PUT on a PATCH-only resource) is a client error — 405,
        // not the framework default that was surfacing as 500.
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.METHOD_NOT_ALLOWED);
        problem.setType(URI.create("about:blank"));
        problem.setDetail(e.getMessage());
        return problem;
    }
}
