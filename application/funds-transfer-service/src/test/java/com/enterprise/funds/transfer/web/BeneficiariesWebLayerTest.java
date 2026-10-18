package com.enterprise.funds.transfer.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.funds.transfer.config.FundsProperties;
import com.enterprise.funds.transfer.domain.Actor;
import com.enterprise.funds.transfer.domain.ApiException;
import com.enterprise.funds.transfer.domain.ErrorCode;
import com.enterprise.funds.transfer.domain.Role;
import com.enterprise.funds.transfer.persistence.UserRepository;
import com.enterprise.funds.transfer.security.ActorResolver;
import com.enterprise.funds.transfer.security.SecurityConfig;
import com.enterprise.funds.transfer.service.BeneficiaryService;
import com.enterprise.funds.transfer.web.dto.BeneficiaryDto;
import com.enterprise.funds.transfer.web.dto.BeneficiaryListDto;
import com.enterprise.funds.transfer.web.dto.CreateBeneficiaryRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** HTTP contract of openapi/beneficiaries/beneficiaries-v1.yaml without a database. */
@WebMvcTest(controllers = BeneficiariesController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, ErrorFactory.class, CorrelationIdFilter.class,
        WebConfig.class, ActorResolver.class, BeneficiariesWebLayerTest.TestBeans.class})
@EnableConfigurationProperties(FundsProperties.class)
@TestPropertySource(properties = {
        "FUNDS_DB_URL=jdbc:none", "FUNDS_DB_USER=x", "FUNDS_DB_PASSWORD=x",
        "FUNDS_JWT_ISSUER_URI=https://issuer.invalid/"})
class BeneficiariesWebLayerTest {

    static class TestBeans {
        @Bean Clock clock() { return Clock.systemUTC(); }
    }

    private static final String ID = "01928f4e-7a1c-7d0e-9b53-6c8f2a1d7e90";
    private static final String BODY = "{\"accountId\":\"ACC002\",\"nickname\":\"Rent - landlord\"}";

    @Autowired MockMvc mvc;
    @MockitoBean BeneficiaryService beneficiaries;
    @MockitoBean UserRepository users;
    @MockitoBean JwtDecoder jwtDecoder;

    private final BeneficiaryDto dto = new BeneficiaryDto(ID, "ACC002", "Rent - landlord",
            Instant.parse("2026-10-18T09:00:00.000Z"));

    @BeforeEach
    void knownUser() {
        when(users.findActiveBySubject("sub-1"))
                .thenReturn(Optional.of(new Actor(UUID.randomUUID(), Role.CUSTOMER, UUID.randomUUID())));
    }

    private static RequestPostProcessor asUser() {
        return jwt().jwt(j -> j.subject("sub-1"));
    }

    private MockHttpServletRequestBuilder create(String body) {
        return post("/api/v1/beneficiaries").with(asUser()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void listIs200NoStoreWithCorrelationId() throws Exception {
        when(beneficiaries.list(any())).thenReturn(new BeneficiaryListDto(List.of(dto)));
        mvc.perform(get("/api/v1/beneficiaries").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.beneficiaries", hasSize(1)))
                .andExpect(jsonPath("$.beneficiaries[0].beneficiaryId").value(ID))
                .andExpect(jsonPath("$.beneficiaries[0].accountId").value("ACC002"))
                .andExpect(jsonPath("$.beneficiaries[0].nickname").value("Rent - landlord"))
                .andExpect(jsonPath("$.beneficiaries[0].createdAt").value("2026-10-18T09:00:00Z"));
    }

    @Test
    void theResponseCarriesExactlyTheContractFields() throws Exception {
        when(beneficiaries.get(any(), eq(UUID.fromString(ID)))).thenReturn(dto);
        mvc.perform(get("/api/v1/beneficiaries/" + ID).with(asUser()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.currency").doesNotExist());
    }

    @Test
    void createIs201WithLocation() throws Exception {
        when(beneficiaries.create(any(), eq(new CreateBeneficiaryRequest("ACC002", "Rent - landlord")), anyString()))
                .thenReturn(dto);
        mvc.perform(create(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/beneficiaries/" + ID))
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.beneficiaryId").value(ID));
    }

    @Test
    void malformedBodiesAre400AndNeverReachTheService() throws Exception {
        for (String body : List.of(
                "{\"accountId\":\"ACC002\",\"nickname\":\"x\",\"extra\":1}",  // additionalProperties: false
                "{\"accountId\":\"ACC002\",\"nickname\":123}",                // not a JSON string
                "{\"accountId\":\"ACC002\"}",                                 // nickname required
                "{\"nickname\":\"x\"}",                                       // accountId required
                "{\"accountId\":\"ACC 002\",\"nickname\":\"x\"}",             // AccountId pattern
                "not json")) {
            mvc.perform(create(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().exists("X-Correlation-Id"))
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(beneficiaries);
    }

    @Test
    void alreadyExistsIs409WithLocationOfTheExistingOne() throws Exception {
        when(beneficiaries.create(any(), any(), anyString())).thenThrow(
                new ApiException(ErrorCode.BENEFICIARY_ALREADY_EXISTS).header("Location", "/api/v1/beneficiaries/" + ID));
        mvc.perform(create(BODY))
                .andExpect(status().isConflict())
                .andExpect(header().string("Location", "/api/v1/beneficiaries/" + ID))
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.code").value("BENEFICIARY_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").value("This account is already saved as a beneficiary."));
    }

    @Test
    void businessRejectionsAre422WithTheirCode() throws Exception {
        for (ErrorCode code : List.of(ErrorCode.BENEFICIARY_OWN_ACCOUNT, ErrorCode.ACCOUNT_INACTIVE,
                ErrorCode.BENEFICIARY_LIMIT_REACHED)) {
            doThrow(new ApiException(code)).when(beneficiaries).create(any(), any(), anyString());
            mvc.perform(create(BODY))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value(code.name()));
        }
    }

    @Test
    void operatorIs403() throws Exception {
        when(beneficiaries.list(any())).thenThrow(new ApiException(ErrorCode.FORBIDDEN));
        mvc.perform(get("/api/v1/beneficiaries").with(asUser()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void deleteIs204WithCorrelationIdAndNoBody() throws Exception {
        mvc.perform(delete("/api/v1/beneficiaries/" + ID).with(asUser()))
                .andExpect(status().isNoContent())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(content().string(""));
        verify(beneficiaries).delete(any(), eq(UUID.fromString(ID)), anyString());
    }

    @Test
    void notFoundIs404() throws Exception {
        doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(beneficiaries).delete(any(), any(), anyString());
        mvc.perform(delete("/api/v1/beneficiaries/" + ID).with(asUser()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aNonUuidPathIs400() throws Exception {
        mvc.perform(get("/api/v1/beneficiaries/not-a-uuid").with(asUser()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(delete("/api/v1/beneficiaries/not-a-uuid").with(asUser()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(beneficiaries);
    }

    @Test
    void unauthenticatedIs401() throws Exception {
        mvc.perform(get("/api/v1/beneficiaries"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(delete("/api/v1/beneficiaries/" + ID)).andExpect(status().isUnauthorized());
        verifyNoInteractions(beneficiaries);
    }

    @Test
    void browsersMayPreflightADelete() throws Exception {
        mvc.perform(options("/api/v1/beneficiaries/" + ID)
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("DELETE")));
    }
}
