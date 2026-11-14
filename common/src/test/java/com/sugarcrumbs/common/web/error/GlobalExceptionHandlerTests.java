package com.sugarcrumbs.common.web.error;

import com.sugarcrumbs.common.exception.ConflictException;
import com.sugarcrumbs.common.exception.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every failure a controller can produce maps to the right status and the
 * {@link ApiError} shape, and nothing internal (SQL, exception messages,
 * stack traces) reaches the client. Uses a standalone MockMvc: no Spring
 * context, no database.
 */
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTests {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator())
            .build();

    // ---- application exceptions ----

    @Test
    void notFoundExceptionIs404WithItsMessage() throws Exception {
        expectApiError(mvc.perform(get("/test/not-found")), 404, "Not Found", "/test/not-found")
                .andExpect(jsonPath("$.message").value("item 42 not found"));
    }

    @Test
    void conflictExceptionIs409WithItsMessage() throws Exception {
        expectApiError(mvc.perform(get("/test/conflict")), 409, "Conflict", "/test/conflict")
                .andExpect(jsonPath("$.message").value("category name already used"));
    }

    @Test
    void domainRuleViolationIs400WithItsMessage() throws Exception {
        expectApiError(mvc.perform(get("/test/illegal-state")), 400, "Bad Request", "/test/illegal-state")
                .andExpect(jsonPath("$.message").value("only PENDING bookings can be confirmed"));
    }

    // ---- concurrency and data integrity: 409, generic message ----

    @Test
    void optimisticLockFailureIs409() throws Exception {
        expectApiError(mvc.perform(get("/test/optimistic-lock")), 409, "Conflict", "/test/optimistic-lock")
                .andExpect(jsonPath("$.message", containsString("changed by another request")))
                .andExpect(content().string(not(containsString("Item"))));
    }

    @Test
    void pessimisticLockFailureIs409() throws Exception {
        expectApiError(mvc.perform(get("/test/lock-timeout")), 409, "Conflict", "/test/lock-timeout");
    }

    @Test
    void dataIntegrityViolationIs409WithoutLeakingSql() throws Exception {
        expectApiError(mvc.perform(get("/test/integrity")), 409, "Conflict", "/test/integrity")
                .andExpect(jsonPath("$.message").value("the request conflicts with existing data"))
                .andExpect(content().string(not(containsString("uk_categories_name"))))
                .andExpect(content().string(not(containsString("INSERT"))));
    }

    // ---- Spring MVC request errors: 4xx, never 500 ----

    @Test
    void invalidBodyIs400WithEveryRejectedField() throws Exception {
        expectApiError(mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"quantity\":0}")), 400, "Bad Request", "/test/body")
                .andExpect(jsonPath("$.message").value("validation failed"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'quantity')]").exists());
    }

    @Test
    void malformedJsonIs400() throws Exception {
        expectApiError(mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": ")), 400, "Bad Request", "/test/body");
    }

    @Test
    void pathVariableOfTheWrongTypeIs400() throws Exception {
        expectApiError(mvc.perform(get("/test/items/not-a-uuid")), 400, "Bad Request", "/test/items/not-a-uuid");
    }

    @Test
    void missingRequestParameterIs400() throws Exception {
        expectApiError(mvc.perform(get("/test/search")), 400, "Bad Request", "/test/search")
                .andExpect(jsonPath("$.message", containsString("q")));
    }

    @Test
    void requestParameterOfTheWrongTypeIs400() throws Exception {
        expectApiError(mvc.perform(get("/test/page").param("size", "lots")), 400, "Bad Request", "/test/page");
    }

    @Test
    void missingMultipartFileIs400() throws Exception {
        expectApiError(mvc.perform(multipart("/test/upload").param("other", "x")), 400, "Bad Request", "/test/upload");
    }

    @Test
    void uploadOverTheSizeLimitIs413() throws Exception {
        expectApiError(mvc.perform(get("/test/too-large")), 413, "Content Too Large", "/test/too-large");
    }

    @Test
    void unknownPathIs404() throws Exception {
        expectApiError(mvc.perform(get("/test/does-not-exist")), 404, "Not Found", "/test/does-not-exist");
    }

    @Test
    void unsupportedMethodIs405() throws Exception {
        expectApiError(mvc.perform(put("/test/not-found")), 405, "Method Not Allowed", "/test/not-found");
    }

    @Test
    void unsupportedContentTypeIs415() throws Exception {
        expectApiError(mvc.perform(post("/test/body").contentType(MediaType.TEXT_PLAIN).content("hi")),
                415, "Unsupported Media Type", "/test/body");
    }

    // ---- security exceptions ----

    @Test
    void badCredentialsAre401WithTheirMessageAndABearerChallenge() throws Exception {
        expectApiError(mvc.perform(get("/test/bad-credentials")), 401, "Unauthorized", "/test/bad-credentials")
                .andExpect(jsonPath("$.message").value("invalid email or password"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    @Test
    void otherAuthenticationFailuresAre401WithAGenericMessage() throws Exception {
        expectApiError(mvc.perform(get("/test/unauthenticated")), 401, "Unauthorized", "/test/unauthenticated")
                .andExpect(jsonPath("$.message").value("a valid access token is required"))
                .andExpect(content().string(not(containsString("Jwt expired"))));
    }

    @Test
    void accessDeniedIs403() throws Exception {
        expectApiError(mvc.perform(get("/test/forbidden")), 403, "Forbidden", "/test/forbidden");
    }

    // ---- unexpected exceptions ----

    @Test
    void unexpectedExceptionIs500WithAGenericMessage() throws Exception {
        expectApiError(mvc.perform(get("/test/boom")), 500, "Internal Server Error", "/test/boom")
                .andExpect(jsonPath("$.message").value("an unexpected error occurred"))
                .andExpect(content().string(not(containsString("secret detail"))))
                .andExpect(content().string(not(containsString("NullPointerException"))))
                .andExpect(content().string(not(containsString("at com.sugarcrumbs"))));
    }

    @Test
    void unexpectedExceptionIsLoggedAtWarnWithItsStackTrace(CapturedOutput output) throws Exception {
        mvc.perform(get("/test/boom"));

        assertThat(output.getOut())
                .contains("WARN")
                .contains("Unhandled exception on GET /test/boom")
                .contains("java.lang.NullPointerException: secret detail")
                .contains("at com.sugarcrumbs.common.web.error.GlobalExceptionHandlerTests$FailingController");
    }

    @Test
    void clientErrorsAreNotLoggedAsWarnings(CapturedOutput output) throws Exception {
        mvc.perform(get("/test/not-found"));
        mvc.perform(get("/test/items/not-a-uuid"));

        assertThat(output.getOut()).doesNotContain("WARN");
    }

    // ---- helpers ----

    private static ResultActions expectApiError(ResultActions result, int status, String error, String path)
            throws Exception {
        return result
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.error").value(error))
                .andExpect(jsonPath("$.message", notNullValue()))
                .andExpect(jsonPath("$.path").value(path))
                .andExpect(jsonPath("$.timestamp", notNullValue()));
    }

    private static LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }

    record SampleRequest(@NotBlank String name, @Positive int quantity) {
    }

    @RestController
    static class FailingController {

        @GetMapping("/test/bad-credentials")
        void badCredentials() {
            throw new BadCredentialsException("invalid email or password");
        }

        @GetMapping("/test/unauthenticated")
        void unauthenticated() {
            throw new InsufficientAuthenticationException("Jwt expired at 2026-01-01T00:00:00Z");
        }

        @GetMapping("/test/forbidden")
        void forbidden() {
            throw new AccessDeniedException("Access Denied");
        }

        @GetMapping("/test/not-found")
        void notFound() {
            throw new NotFoundException("item 42 not found");
        }

        @GetMapping("/test/conflict")
        void conflict() {
            throw new ConflictException("category name already used");
        }

        @GetMapping("/test/illegal-state")
        void illegalState() {
            throw new IllegalStateException("only PENDING bookings can be confirmed");
        }

        @GetMapping("/test/optimistic-lock")
        void optimisticLock() {
            throw new ObjectOptimisticLockingFailureException("Item", UUID.randomUUID());
        }

        @GetMapping("/test/lock-timeout")
        void lockTimeout() {
            throw new CannotAcquireLockException("could not obtain lock on row in relation \"working_hours\"");
        }

        @GetMapping("/test/integrity")
        void integrity() {
            throw new DataIntegrityViolationException("could not execute statement",
                    new SQLException("INSERT INTO categories ... violates unique constraint \"uk_categories_name\""));
        }

        @PostMapping(path = "/test/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        void body(@Valid @RequestBody SampleRequest request) {
        }

        @GetMapping("/test/items/{id}")
        void item(@PathVariable UUID id) {
        }

        @GetMapping("/test/search")
        void search(@RequestParam String q) {
        }

        @GetMapping("/test/page")
        void page(@RequestParam int size) {
        }

        @PostMapping("/test/upload")
        void upload(@RequestPart("file") MultipartFile file) {
        }

        @GetMapping("/test/too-large")
        void tooLarge() {
            throw new MaxUploadSizeExceededException(10 * 1024 * 1024);
        }

        @GetMapping("/test/boom")
        void boom() {
            throw new NullPointerException("secret detail");
        }
    }
}
