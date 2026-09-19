package com.enterprise.funds.transfer.web.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of POST /transfers. Unknown properties are rejected (contract: additionalProperties false). */
public record CreateTransferRequest(
        @JsonDeserialize(using = StrictStringDeserializer.class) @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,32}$") String sourceAccountId,
        @JsonDeserialize(using = StrictStringDeserializer.class) @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,32}$") String destinationAccountId,
        @JsonDeserialize(using = StrictStringDeserializer.class) @NotNull @Pattern(regexp = "^(0|[1-9][0-9]{0,14})(\\.[0-9]{1,4})?$") String amount,
        @JsonDeserialize(using = StrictStringDeserializer.class) @NotBlank String currency,
        @JsonDeserialize(using = StrictStringDeserializer.class) @Size(max = 140) String reference) {}
