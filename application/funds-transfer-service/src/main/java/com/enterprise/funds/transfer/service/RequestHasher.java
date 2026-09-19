package com.enterprise.funds.transfer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * SHA-256 of the canonical request (contract, section 4): object keys sorted; amount compared by decimal
 * value ("250.00" equals "250.0"); an absent reference equals null; every other string exact, whitespace
 * included. The hash is what decides "same payload" for idempotency.
 */
@Component
public class RequestHasher {

    private final ObjectMapper canonical = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public byte[] hash(String sourceAccountId, String destinationAccountId, BigDecimal amount,
                       String currency, String reference) {
        Map<String, Object> fields = new TreeMap<>();
        fields.put("amount", amount.stripTrailingZeros().toPlainString());
        fields.put("currency", currency);
        fields.put("destinationAccountId", destinationAccountId);
        fields.put("reference", reference);
        fields.put("sourceAccountId", sourceAccountId);
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.writeValueAsBytes(fields));
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot hash request", e);
        }
    }

    /** For tests and diagnostics only. */
    static String debugString(byte[] hash) {
        return new String(java.util.HexFormat.of().formatHex(hash).getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);
    }
}
