package com.enterprise.funds.transfer.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.enterprise.funds.transfer.notifications.NotificationCategory;
import com.enterprise.funds.transfer.persistence.UserRepository;
import com.enterprise.funds.transfer.security.ActorResolver;
import com.enterprise.funds.transfer.security.SecurityConfig;
import com.enterprise.funds.transfer.service.NotificationService;
import com.enterprise.funds.transfer.web.dto.NotificationDto;
import com.enterprise.funds.transfer.web.dto.NotificationPageDto;
import com.enterprise.funds.transfer.web.dto.UnreadCountDto;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** HTTP contract of openapi/notifications/notifications-v1.yaml without a database. */
@WebMvcTest(controllers = NotificationsController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, ErrorFactory.class, CorrelationIdFilter.class,
        WebConfig.class, ActorResolver.class, NotificationsWebLayerTest.TestBeans.class})
@EnableConfigurationProperties(FundsProperties.class)
@TestPropertySource(properties = {
        "FUNDS_DB_URL=jdbc:none", "FUNDS_DB_USER=x", "FUNDS_DB_PASSWORD=x",
        "FUNDS_JWT_ISSUER_URI=https://issuer.invalid/"})
class NotificationsWebLayerTest {

    static class TestBeans {
        @Bean Clock clock() { return Clock.systemUTC(); }
    }

    private static final String ID = "01928f4e-7a1c-7d0e-9b53-6c8f2a1d7e91";

    @Autowired MockMvc mvc;
    @MockitoBean NotificationService notifications;
    @MockitoBean UserRepository users;
    @MockitoBean JwtDecoder jwtDecoder;

    private final NotificationDto dto = new NotificationDto(ID, "TRANSACTION", "TRANSFER_RECEIVED", "TRANSFER",
            "01928f4e-7a1c-7d0e-9b53-6c8f2a1d7e92",
            new ObjectMapper().createObjectNode().put("amount", "25.00").put("currency", "USD"),
            Instant.parse("2026-10-18T09:00:00Z"), Instant.parse("2026-10-18T09:00:00.250Z"), null);

    @BeforeEach
    void knownUser() {
        when(users.findActiveBySubject("sub-1"))
                .thenReturn(Optional.of(new Actor(UUID.randomUUID(), Role.CUSTOMER, UUID.randomUUID())));
    }

    private static RequestPostProcessor asUser() {
        return jwt().jwt(j -> j.subject("sub-1"));
    }

    @Test
    void listIs200NoStoreWithTheContractShape() throws Exception {
        when(notifications.list(any(), eq(0), eq(20), isNull(), eq(false)))
                .thenReturn(new NotificationPageDto(List.of(dto), 0, 20, 1, 1, 1));
        mvc.perform(get("/api/v1/notifications").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].length()").value(9))
                .andExpect(jsonPath("$.items[0].type").value("TRANSFER_RECEIVED"))
                .andExpect(jsonPath("$.items[0].params.amount").value("25.00"))
                .andExpect(jsonPath("$.items[0].readAt").value(nullValue()))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-10-18T09:00:00.250Z"))
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void filtersAndPagingAreBound() throws Exception {
        when(notifications.list(any(), anyInt(), anyInt(), any(), anyBoolean()))
                .thenReturn(new NotificationPageDto(List.of(), 3, 50, 0, 0, 0));
        mvc.perform(get("/api/v1/notifications?page=3&size=50&category=SECURITY&unreadOnly=true").with(asUser()))
                .andExpect(status().isOk());
        verify(notifications).list(any(), eq(3), eq(50), eq(NotificationCategory.SECURITY), eq(true));
    }

    @Test
    void badQueryParametersAre400AndNeverReachTheService() throws Exception {
        for (String q : List.of("size=0", "size=51", "page=-1", "category=MARKETING", "unreadOnly=maybe", "page=x")) {
            mvc.perform(get("/api/v1/notifications?" + q).with(asUser()))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().exists("X-Correlation-Id"))
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(notifications);
    }

    @Test
    void unreadCountIs200NoStore() throws Exception {
        when(notifications.unreadCount(any())).thenReturn(new UnreadCountDto(3));
        mvc.perform(get("/api/v1/notifications/unread-count").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.unreadCount").value(3));
    }

    @Test
    void markReadIs204WithNoBody() throws Exception {
        mvc.perform(post("/api/v1/notifications/" + ID + "/read").with(asUser()))
                .andExpect(status().isNoContent())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(content().string(""));
        verify(notifications).markRead(any(), eq(UUID.fromString(ID)));
    }

    @Test
    void markReadOfAnUnknownIdIs404AndANonUuidIs400() throws Exception {
        doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(notifications).markRead(any(), any());
        mvc.perform(post("/api/v1/notifications/" + ID + "/read").with(asUser()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(post("/api/v1/notifications/not-a-uuid/read").with(asUser()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void markAllReadIs204() throws Exception {
        mvc.perform(post("/api/v1/notifications/read-all").with(asUser()))
                .andExpect(status().isNoContent());
        verify(notifications).markAllRead(any());
    }

    @Test
    void operatorIs403() throws Exception {
        when(notifications.unreadCount(any())).thenThrow(new ApiException(ErrorCode.FORBIDDEN));
        mvc.perform(get("/api/v1/notifications/unread-count").with(asUser()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void anonymousIs401() throws Exception {
        mvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(notifications);
    }
}
