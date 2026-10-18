package com.enterprise.funds.transfer.web.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Body of POST /beneficiaries. Unknown properties are rejected (contract: additionalProperties false). The
 * nickname is trimmed and checked by the service, because its length and blank rules apply after trimming.
 */
public record CreateBeneficiaryRequest(
        @JsonDeserialize(using = StrictStringDeserializer.class) @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,32}$") String accountId,
        @JsonDeserialize(using = StrictStringDeserializer.class) @NotNull String nickname) {}
