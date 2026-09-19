package com.enterprise.funds.transfer.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/** Standard error body (contract: code, message, correlationId, timestamp, optional details). */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorDto(String code, String message, String correlationId, Instant timestamp, List<Detail> details) {
    public record Detail(String field, String issue) {}
}
