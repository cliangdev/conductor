package com.conductor.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The advice's status mapping, exercised over a standalone MockMvc with a stub controller — the
 * lightest context that still proves the real HTTP status a client receives, and no Spring context
 * to cache or fragment.
 */
class GlobalExceptionHandlerTest {

    private final BoomController controller = new BoomController();
    private MockMvc mockMvc;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger handlerLogger;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        handlerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void responseStatusExceptionYieldsItsOwnStatusAndReason() throws Exception {
        controller.toThrow = new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Connection not found in project");

        mockMvc.perform(get("/boom"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.detail").value("Connection not found in project"));
    }

    @Test
    void responseStatusExceptionWithBadRequestYields400() throws Exception {
        controller.toThrow = new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Invalid gcpProjectId format");

        mockMvc.perform(get("/boom"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Invalid gcpProjectId format"));
    }

    @Test
    void responseStatusExceptionWithServerErrorYieldsThatServerError() throws Exception {
        controller.toThrow = new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "GCP Billing connector is not available");

        mockMvc.perform(get("/boom"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value("GCP Billing connector is not available"));
    }

    @Test
    void errorResponseExceptionYieldsItsOwnStatus() throws Exception {
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        body.setDetail("Already linked");
        controller.toThrow = new ErrorResponseException(HttpStatus.CONFLICT, body, null);

        mockMvc.perform(get("/boom"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Already linked"));
    }

    @Test
    void deliberateClientErrorIsNotLoggedAsAnUnexpectedServerError() throws Exception {
        controller.toThrow = new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Connection not found in project");

        mockMvc.perform(get("/boom")).andExpect(status().isNotFound());

        List<ILoggingEvent> events = List.copyOf(logAppender.list);
        assertThat(events).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.ERROR));
        assertThat(events).noneMatch(e -> e.getFormattedMessage().contains("Unexpected error"));
        assertThat(events).allMatch(e -> e.getThrowableProxy() == null);
    }

    @Test
    void serverSideResponseStatusExceptionIsLoggedWithItsStackTrace() throws Exception {
        controller.toThrow = new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "GCP Billing connector is not available");

        mockMvc.perform(get("/boom")).andExpect(status().isServiceUnavailable());

        assertThat(logAppender.list)
                .anyMatch(e -> e.getLevel() == Level.ERROR && e.getThrowableProxy() != null);
    }

    @Test
    void unexpectedRuntimeExceptionStillYields500() throws Exception {
        controller.toThrow = new IllegalStateException("kaboom");

        mockMvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred. Please try again."));

        assertThat(logAppender.list)
                .anyMatch(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains("Unexpected error"));
    }

    @RestController
    static class BoomController {

        private RuntimeException toThrow;

        @GetMapping("/boom")
        String boom() {
            throw toThrow;
        }
    }
}
