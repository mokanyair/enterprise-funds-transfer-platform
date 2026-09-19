package com.enterprise.funds.transfer.persistence;

import com.enterprise.funds.transfer.domain.RiskDecision;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RiskRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public RiskRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID transferId, RiskDecision decision, OffsetDateTime now) {
        var p = JdbcSupport.params();
        JdbcSupport.id(p, "id", Ids.newId());
        JdbcSupport.id(p, "transfer", transferId);
        p.addValue("decision", decision.outcome().name()).addValue("reason", decision.reason())
                .addValue("score", decision.score()).addValue("now", now);
        jdbc.update("""
                INSERT INTO RISK_ASSESSMENTS (ASSESSMENT_ID, TRANSFER_ID, DECISION, REASON, RISK_SCORE, ASSESSED_AT)
                VALUES (:id, :transfer, :decision, :reason, :score, :now)
                """, p);
    }
}
