package com.enterprise.funds.transfer.persistence;

import com.enterprise.funds.transfer.domain.Actor;
import com.enterprise.funds.transfer.domain.Role;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public UserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Maps the verified token subject to an active user. Disabled or unknown subjects yield empty. */
    public Optional<Actor> findActiveBySubject(String idpSubject) {
        List<Actor> rows = jdbc.query(
                "SELECT USER_ID, CUSTOMER_ID, ROLE FROM APP_USERS WHERE IDP_SUBJECT = :subject AND STATUS = 'ACTIVE'",
                JdbcSupport.params().addValue("subject", idpSubject),
                (rs, i) -> new Actor(
                        JdbcSupport.uuid(rs, "USER_ID"), Role.valueOf(rs.getString("ROLE")),
                        JdbcSupport.uuid(rs, "CUSTOMER_ID")));
        return rows.stream().findFirst();
    }
}
