package com.enterprise.funds.transfer.web;

import com.enterprise.funds.transfer.domain.ApiException;
import com.enterprise.funds.transfer.web.dto.ErrorDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** Builds the standard error body and writes it for code that runs outside Spring MVC (the security filters). */
@Component
public class ErrorFactory {

    private final Clock clock;
    private final ObjectMapper mapper;

    public ErrorFactory(Clock clock, ObjectMapper mapper) {
        this.clock = clock;
        this.mapper = mapper;
    }

    public ErrorDto body(ApiException e) {
        List<ErrorDto.Detail> details = e.details().stream()
                .map(d -> new ErrorDto.Detail(d.field(), d.issue())).toList();
        return new ErrorDto(e.code().name(), e.getMessage(), CorrelationIdFilter.current(),
                Instant.now(clock).truncatedTo(ChronoUnit.MILLIS), details);
    }

    public void write(HttpServletResponse response, ApiException e) throws IOException {
        response.setStatus(e.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        e.headers().forEach(response::setHeader);
        mapper.writeValue(response.getOutputStream(), body(e));
    }
}
