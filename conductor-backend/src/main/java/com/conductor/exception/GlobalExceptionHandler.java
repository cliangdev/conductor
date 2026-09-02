package com.conductor.exception;

import com.conductor.agent.AgentReferencedByWorkflowsException;
import com.conductor.conversation.AgentNotAddressableException;
import com.conductor.conversation.ConversationBusyException;
import com.conductor.conversation.ConversationNotFoundException;
import com.conductor.knowledge.page.FrontmatterException;
import com.conductor.knowledge.page.KnowledgeConflictException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponseException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.ValueInstantiationException;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
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

    @ExceptionHandler(AgentReferencedByWorkflowsException.class)
    public ProblemDetail handleAgentReferencedByWorkflowsException(AgentReferencedByWorkflowsException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("about:blank"));
        problem.setDetail(e.getMessage());
        List<Map<String, Object>> conflicts = e.references().stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("workflowId", r.workflowId());
                    m.put("workflowName", r.workflowName());
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

    @ExceptionHandler(ConversationNotFoundException.class)
    public ProblemDetail handleConversationNotFoundException(ConversationNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("about:blank"));
        problem.setDetail(e.getMessage());
        return problem;
    }

    /**
     * {@link AgentNotAddressableException#isAmbiguous()} picks the status: an ambiguous name match is a
     * 409 (the caller's request is well-formed but underspecified -- add the slug to resolve it
     * deterministically), while every other case (including the plain not-found and the default-to-CEO
     * case) is a 404 (nothing in the project matches at all).
     */
    @ExceptionHandler(AgentNotAddressableException.class)
    public ProblemDetail handleAgentNotAddressableException(AgentNotAddressableException e) {
        HttpStatus status = e.isAmbiguous() ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND;
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create("about:blank"));
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler(ConversationBusyException.class)
    public ProblemDetail handleConversationBusyException(ConversationBusyException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
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

    private static final String UNREADABLE_BODY_DETAIL = "Malformed or unreadable request body";

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableMessage(HttpMessageNotReadableException e) {
        // Malformed/unbindable request body (e.g. a JSON array where an object is expected). A body the
        // server can't parse is a client error — 400, not the catch-all 500.
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("about:blank"));
        problem.setDetail(unreadableBodyDetail(e.getCause()));
        return problem;
    }

    /**
     * A rejected enum token is the one unreadable-body case worth naming — the body parsed fine and the
     * caller only needs the accepted values. Generated DTO enums reject via a {@code @JsonCreator} that
     * throws, which Jackson reports as a {@link ValueInstantiationException}; enums without one fail
     * Jackson's own coercion and arrive as an {@link InvalidFormatException}, the only shape that also
     * carries the rejected value (by the time the other is built the parser has moved past the token).
     * These are the Jackson 3 ({@code tools.jackson}) types — the stack Spring Boot 4's message
     * converters read request bodies with, regardless of the Jackson 2 {@code ObjectMapper} services use.
     */
    private static String unreadableBodyDetail(Throwable cause) {
        Class<?> enumType;
        Object rejected;
        if (cause instanceof InvalidFormatException e) {
            enumType = e.getTargetType();
            rejected = e.getValue();
        } else if (cause instanceof ValueInstantiationException e) {
            enumType = e.getType().getRawClass();
            rejected = null;
        } else {
            return UNREADABLE_BODY_DETAIL;
        }
        if (enumType == null || !enumType.isEnum()) {
            return UNREADABLE_BODY_DETAIL;
        }
        String field = ((JacksonException) cause).getPath().stream()
                .map(JacksonException.Reference::getPropertyName)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("."));
        String accepted = Arrays.stream(enumType.getEnumConstants())
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
        return "Invalid value"
                + (rejected != null ? " '" + rejected + "'" : "")
                + (field.isEmpty() ? "" : " for field '" + field + "'")
                + " — must be one of: " + accepted;
    }

    /**
     * Exceptions that carry the status their thrower intended — {@code ResponseStatusException} and every
     * other {@link ErrorResponseException}. Without this they fall through to the catch-all below, which
     * logs a stack trace as an "Unexpected error" and answers 500, discarding both the status and the
     * reason (a 404 for a cross-project connection was being served as a 500). Handling the parent
     * {@code ErrorResponseException} covers {@code ResponseStatusException} and its siblings in one place;
     * the exceptions named by the handlers above still match those, since Spring picks the closest
     * handler. Other {@code ErrorResponse} implementors that are not {@code ErrorResponseException}
     * subclasses (e.g. {@code NoResourceFoundException}, {@code HttpRequestMethodNotSupportedException})
     * already have dedicated handlers above.
     *
     * <p>A deliberate 4xx is a client error, not a server fault, so it logs at WARN without a stack trace;
     * a 5xx still gets the full trace.
     */
    @ExceptionHandler(ErrorResponseException.class)
    public ProblemDetail handleErrorResponseException(ErrorResponseException e) {
        HttpStatusCode status = e.getStatusCode();
        String detail = e.getBody().getDetail();
        if (status.is5xxServerError()) {
            log.error("ErrorResponseException: {} {}", status.value(), detail, e);
        } else {
            log.warn("ErrorResponseException: {} {}", status.value(), detail);
        }
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create("about:blank"));
        problem.setDetail(detail);
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

    /**
     * A {@code @Validated}-interface query-param constraint (e.g. the generated {@code MemoryApi}'s
     * {@code @Max} on {@code limit}) fails validation before the controller method runs, and without this
     * handler falls through to the catch-all {@link #handleUnexpectedException} as a 500 -- a rejected
     * query param is a client error, same reasoning as {@link #handleValidationException} for request
     * bodies. One entry per violated parameter, following that method's {@code fieldErrors} shape.
     */
    /**
     * The generated API interfaces are {@code @Validated}, so a violated query-param constraint (e.g.
     * {@code MemoryApi}'s {@code @Max} on {@code limit}) surfaces as a {@code
     * ConstraintViolationException} from the AOP method-validation interceptor -- a client error, not the
     * 500 the catch-all would return. (Spring's own handler-method validation path raises {@link
     * HandlerMethodValidationException} instead; {@link #handleHandlerMethodValidationException} covers
     * that shape.)
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolationException(ConstraintViolationException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("about:blank"));
        problem.setDetail("Validation failed");
        List<Map<String, String>> fieldErrors = e.getConstraintViolations().stream()
                .map(v -> Map.of(
                        "field", v.getPropertyPath() != null ? v.getPropertyPath().toString() : "",
                        "message", v.getMessage() != null ? v.getMessage() : "Invalid value"))
                .collect(Collectors.toList());
        problem.setProperty("fieldErrors", fieldErrors);
        return problem;
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleHandlerMethodValidationException(HandlerMethodValidationException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("about:blank"));
        problem.setDetail("Validation failed");

        List<Map<String, String>> fieldErrors = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> Map.of(
                                "field", parameterName(result),
                                "message", error.getDefaultMessage() != null ? error.getDefaultMessage() : "Invalid value")))
                .collect(Collectors.toList());

        problem.setProperty("fieldErrors", fieldErrors);
        return problem;
    }

    private String parameterName(org.springframework.validation.method.ParameterValidationResult result) {
        String name = result.getMethodParameter().getParameterName();
        return name != null ? name : "";
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
