package com.enterprise.funds.transfer.web;

import com.enterprise.funds.transfer.domain.Actor;
import com.enterprise.funds.transfer.service.BeneficiaryService;
import com.enterprise.funds.transfer.web.dto.BeneficiaryDto;
import com.enterprise.funds.transfer.web.dto.BeneficiaryListDto;
import com.enterprise.funds.transfer.web.dto.CreateBeneficiaryRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Implements openapi/beneficiaries/beneficiaries-v1.yaml. Thin: all rules live in the service. */
@RestController
@RequestMapping(path = "/api/v1/beneficiaries", produces = MediaType.APPLICATION_JSON_VALUE)
public class BeneficiariesController {

    private final BeneficiaryService beneficiaries;

    public BeneficiariesController(BeneficiaryService beneficiaries) {
        this.beneficiaries = beneficiaries;
    }

    @GetMapping
    public ResponseEntity<BeneficiaryListDto> list(Actor actor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(beneficiaries.list(actor));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BeneficiaryDto> create(Actor actor, @Valid @RequestBody CreateBeneficiaryRequest request) {
        BeneficiaryDto created = beneficiaries.create(actor, request, CorrelationIdFilter.current());
        return ResponseEntity.created(URI.create("/api/v1/beneficiaries/" + created.beneficiaryId())).body(created);
    }

    @GetMapping("/{beneficiaryId}")
    public ResponseEntity<BeneficiaryDto> get(Actor actor, @PathVariable UUID beneficiaryId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(beneficiaries.get(actor, beneficiaryId));
    }

    @DeleteMapping("/{beneficiaryId}")
    public ResponseEntity<Void> delete(Actor actor, @PathVariable UUID beneficiaryId) {
        beneficiaries.delete(actor, beneficiaryId, CorrelationIdFilter.current());
        return ResponseEntity.noContent().build();
    }
}
