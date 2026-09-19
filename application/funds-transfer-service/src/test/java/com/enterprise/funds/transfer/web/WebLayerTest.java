package com.enterprise.funds.transfer.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.funds.transfer.domain.Actor;
import com.enterprise.funds.transfer.domain.ApiException;
import com.enterprise.funds.transfer.domain.ErrorCode;
import com.enterprise.funds.transfer.domain.Role;
import com.enterprise.funds.transfer.persistence.UserRepository;
import com.enterprise.funds.transfer.security.ActorResolver;
import com.enterprise.funds.transfer.security.SecurityConfig;
import com.enterprise.funds.transfer.service.AccountService;
import com.enterprise.funds.transfer.service.TransferService;
import com.enterprise.funds.transfer.service.TransferService.CreateOutcome;
import com.enterprise.funds.transfer.web.dto.BalanceDto;
import com.enterprise.funds.transfer.web.dto.TransferDto;
import com.enterprise.funds.transfer.web.dto.TransferPageDto;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Checks the HTTP contract without a database: status codes, error body shape, and that EVERY response, success or
 * error, carries X-Correlation-Id (the rule the OpenAPI lint enforces on the spec, verified here on real responses).
 */
@WebMvcTest(controllers = {TransfersController.class, AccountsController.class})
@Import({SecurityConfig.class, GlobalExceptionHandler.class, ErrorFactory.class, CorrelationIdFilter.class,
        WebConfig.class, ActorResolver.class, WebLayerTest.TestBeans.class})
@TestPropertySource(properties = {
        "FUNDS_DB_URL=jdbc:none", "FUNDS_DB_USER=x", "FUNDS_DB_PASSWORD=x",
        "FUNDS_JWT_ISSUER_URI=https://issuer.invalid/"})
class WebLayerTest {

    static class TestBeans {
        @Bean Clock clock() { return Clock.systemUTC(); }
    }

    private static final String GOOD_KEY = "5b0c3f7e-8a1d-4c2b-9e6f-1a2b3c4d5e6f";
    private static final String BODY =
            "{\"sourceAccountId\":\"ACC001\",\"destinationAccountId\":\"ACC002\",\"amount\":\"250.00\","
                    + "\"currency\":\"USD\",\"reference\":\"Invoice payment\"}";

    @Autowired MockMvc mvc;
    @MockitoBean TransferService transfers;
    @MockitoBean AccountService accounts;
    @MockitoBean UserRepository users;
    @MockitoBean JwtDecoder jwtDecoder;

    private final UUID transferId = UUID.fromString("0b6f4e1e-3c1a-4d0e-9b53-6c8f2a1d7e90");
    private final TransferDto dto = new TransferDto(transferId.toString(), "ACC001", "ACC002", "250.00", "USD",
            "COMPLETED", Instant.parse("2026-09-18T10:15:30.123Z"), Instant.parse("2026-09-18T10:15:30.410Z"));

    @BeforeEach
    void knownUser() {
        when(users.findActiveBySubject("sub-1"))
                .thenReturn(Optional.of(new Actor(UUID.randomUUID(), Role.CUSTOMER, UUID.randomUUID())));
    }

    private static RequestPostProcessor asUser() {
        return jwt().jwt(j -> j.subject("sub-1"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create(String body) {
        return post("/api/v1/transfers").with(asUser()).header("Idempotency-Key", GOOD_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    // ---- create: success and headers

    @Test
    void createReturns201WithLocationBodyAndCorrelationId() throws Exception {
        when(transfers.create(any(), eq(GOOD_KEY), any(), anyString())).thenReturn(new CreateOutcome(dto, transferId, false));
        mvc.perform(create(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/transfers/" + transferId))
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.transferId").value(transferId.toString()))
                .andExpect(jsonPath("$.amount").value("250.00"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.createdAt").value("2026-09-18T10:15:30.123Z"));
    }

    @Test
    void aReplayIsMarkedIdempotentReplayed() throws Exception {
        when(transfers.create(any(), any(), any(), anyString())).thenReturn(new CreateOutcome(dto, transferId, true));
        mvc.perform(create(BODY)).andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void aValidInboundCorrelationIdIsEchoed() throws Exception {
        when(transfers.create(any(), any(), any(), anyString())).thenReturn(new CreateOutcome(dto, transferId, false));
        mvc.perform(create(BODY).header("X-Correlation-Id", "client-abc.123"))
                .andExpect(header().string("X-Correlation-Id", "client-abc.123"));
    }

    @Test
    void anInvalidInboundCorrelationIdIsReplacedNotEchoed() throws Exception {
        when(transfers.create(any(), any(), any(), anyString())).thenReturn(new CreateOutcome(dto, transferId, false));
        mvc.perform(create(BODY).header("X-Correlation-Id", "bad value with spaces\t"))
                .andExpect(header().string("X-Correlation-Id", not(containsString(" "))))
                .andExpect(header().exists("X-Correlation-Id"));
    }

    // ---- errors carry the standard body and the same correlation id in header and body

    @Test
    void errorBodyRepeatsTheCorrelationIdFromTheHeader() throws Exception {
        when(transfers.create(any(), any(), any(), anyString())).thenThrow(new ApiException(ErrorCode.INSUFFICIENT_FUNDS));
        var result = mvc.perform(create(BODY).header("X-Correlation-Id", "trace-42"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("X-Correlation-Id", "trace-42"))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.correlationId").value("trace-42"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentType()).contains("application/json");
    }

    @Test
    void inProgressConflictCarriesRetryAfter() throws Exception {
        when(transfers.create(any(), any(), any(), anyString()))
                .thenThrow(new ApiException(ErrorCode.IDEMPOTENCY_IN_PROGRESS).header("Retry-After", "1"));
        mvc.perform(create(BODY)).andExpect(status().isConflict()).andExpect(header().string("Retry-After", "1"))
                .andExpect(header().exists("X-Correlation-Id")).andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"));
    }

    @Test
    void expiredKeyIsA409WithLocation() throws Exception {
        when(transfers.create(any(), any(), any(), anyString())).thenThrow(
                new ApiException(ErrorCode.IDEMPOTENCY_KEY_EXPIRED).header("Location", "/api/v1/transfers/" + transferId));
        mvc.perform(create(BODY)).andExpect(status().isConflict())
                .andExpect(header().string("Location", "/api/v1/transfers/" + transferId))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_EXPIRED"));
    }

    @Test
    void aReplayedRejectionIsMarkedReplayed() throws Exception {
        when(transfers.create(any(), any(), any(), anyString()))
                .thenThrow(new ApiException(ErrorCode.LIMIT_EXCEEDED).header("Idempotent-Replayed", "true"));
        mvc.perform(create(BODY)).andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Idempotent-Replayed", "true")).andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void forbiddenAndNotFoundUseTheStandardBody() throws Exception {
        when(transfers.create(any(), any(), any(), anyString())).thenThrow(new ApiException(ErrorCode.FORBIDDEN));
        mvc.perform(create(BODY)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        when(transfers.get(any(), any(), anyString())).thenThrow(new ApiException(ErrorCode.NOT_FOUND));
        mvc.perform(get("/api/v1/transfers/" + transferId).with(asUser()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void anUnexpectedFailureIsA500ThatLeaksNothing() throws Exception {
        when(transfers.create(any(), any(), any(), anyString()))
                .thenThrow(new IllegalStateException("ORA-00942: table FUNDS_OWNER.SECRET does not exist"));
        mvc.perform(create(BODY)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.message", not(containsString("ORA-"))))
                .andExpect(jsonPath("$.message", not(containsString("SECRET"))));
    }

    // ---- 400s

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(create("{not json")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST")).andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void anUnknownPropertyIs400() throws Exception {
        mvc.perform(create(BODY.replace("}", ",\"userId\":\"someone-else\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void aBadAmountFormatIs400WithTheFieldNamed() throws Exception {
        mvc.perform(create(BODY.replace("250.00", "-5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("amount"));
    }

    @Test
    void aJsonNumberIsNeverAcceptedAsAnAmount() throws Exception {
        for (String number : List.of("250.00", "250.10", "250", "1e2")) {
            mvc.perform(create(BODY.replace("\"250.00\"", number)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        org.mockito.Mockito.verifyNoInteractions(transfers); // rejected before any business code runs
    }

    @Test
    void aMissingRequiredFieldIs400() throws Exception {
        mvc.perform(create(BODY.replace("\"currency\":\"USD\",", "")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0].field").value("currency"));
    }

    @Test
    void aMalformedTransferIdIs400() throws Exception {
        mvc.perform(get("/api/v1/transfers/not-a-uuid").with(asUser())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST")).andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void pageSizeAboveTheMaximumIs400() throws Exception {
        mvc.perform(get("/api/v1/accounts/ACC001/transfers?size=101").with(asUser())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/accounts/ACC001/transfers?page=-1").with(asUser())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/accounts/ACC001/transfers?status=POSTED").with(asUser())).andExpect(status().isBadRequest());
    }

    @Test
    void anAccountIdWithIllegalCharactersIs400() throws Exception {
        mvc.perform(get("/api/v1/accounts/bad$id/balance").with(asUser())).andExpect(status().isBadRequest());
    }

    // ---- authentication

    @Test
    void noTokenIs401WithStandardBodyAndCorrelationId() throws Exception {
        mvc.perform(post("/api/v1/transfers").header("Idempotency-Key", GOOD_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void aValidTokenForAnUnknownUserIs401NotA404() throws Exception {
        mvc.perform(get("/api/v1/transfers/" + transferId).with(jwt().jwt(j -> j.subject("nobody"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void theHealthEndpointNeedsNoToken() throws Exception {
        // permitted by the security chain; the endpoint itself is not part of this slice, so 404 (not 401) proves the rule
        mvc.perform(get("/actuator/health")).andExpect(status().is(org.hamcrest.Matchers.not(401)));
    }

    // ---- reads

    @Test
    void balanceMatchesTheContractShape() throws Exception {
        when(accounts.balance(any(), eq("ACC001"), anyString())).thenReturn(new BalanceDto("ACC001", "USD", "1250.0000",
                "1250.0000", Instant.parse("2026-09-18T10:15:30.123Z")));
        mvc.perform(get("/api/v1/accounts/ACC001/balance").with(asUser())).andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.availableBalance").value("1250.0000"))
                .andExpect(jsonPath("$.ledgerBalance").value("1250.0000"));
    }

    @Test
    void historyUsesDefaultsAndReturnsAPage() throws Exception {
        when(accounts.history(any(), eq("ACC001"), eq(0), eq(20), isNull(), anyString()))
                .thenReturn(new TransferPageDto(List.of(dto), 0, 20, 1, 1));
        mvc.perform(get("/api/v1/accounts/ACC001/transfers").with(asUser())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void cancelReturnsTheTransfer() throws Exception {
        when(transfers.cancel(any(), eq(transferId), anyString())).thenThrow(new ApiException(ErrorCode.TRANSFER_NOT_CANCELLABLE));
        mvc.perform(post("/api/v1/transfers/" + transferId + "/cancel").with(asUser()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TRANSFER_NOT_CANCELLABLE"))
                .andExpect(header().exists("X-Correlation-Id"));
    }
}
