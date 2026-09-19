package com.enterprise.funds.transfer.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

final class JdbcSupport {

    private JdbcSupport() {}

    static UUID uuid(ResultSet rs, String column) throws SQLException {
        return Ids.fromBytes(rs.getBytes(column));
    }

    static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }

    static MapSqlParameterSource params() {
        return new MapSqlParameterSource();
    }

    /** Binds a UUID as RAW(16); null stays null. */
    static MapSqlParameterSource id(MapSqlParameterSource p, String name, UUID value) {
        return p.addValue(name, value == null ? null : Ids.toBytes(value), java.sql.Types.BINARY);
    }
}
