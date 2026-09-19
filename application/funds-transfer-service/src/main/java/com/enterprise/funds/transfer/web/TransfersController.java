package com.enterprise.funds.transfer.web;

import com.enterprise.funds.transfer.domain.Actor;
import com.enterprise.funds.transfer.service.TransferService;
import com.enterprise.funds.transfer.web.dto.CreateTransferRequest;
import com.enterprise.funds.transfer.web.dto.TransferDto;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Implements openapi/transfers/transfers-v1.yaml. Thin: all rules live in the service. */
@RestController
@RequestMapping(path = "/api/v1/transfers", produces = MediaType.APPLICATION_JSON_VALUE)
public class TransfersController {

    private final TransferService transfers;

    public TransfersController(TransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TransferDto> create(
            Actor actor,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request) {
        var outcome = transfers.create(actor, idempotencyKey, request, CorrelationIdFilter.current());
        ResponseEntity.BodyBuilder response = ResponseEntity
                .created(URI.create("/api/v1/transfers/" + outcome.transferId()));
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.transfer());
    }

    @GetMapping("/{transferId}")
    public TransferDto get(Actor actor, @PathVariable UUID transferId) {
        return TransferDto.from(transfers.get(actor, transferId, CorrelationIdFilter.current()));
    }

    @PostMapping("/{transferId}/cancel")
    public TransferDto cancel(Actor actor, @PathVariable UUID transferId) {
        return TransferDto.from(transfers.cancel(actor, transferId, CorrelationIdFilter.current()));
    }
}
