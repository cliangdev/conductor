package com.conductor.integration.ingest;

import com.conductor.integration.DimensionState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads and upserts {@code channel_dimension_state} (see V155). */
@Component
public class ChannelDimensionStateStore {

    private static final String UPSERT = """
            INSERT INTO channel_dimension_state (connection_id, dimension_key, state, observed_at)
            VALUES (:connectionId, :dimensionKey, CAST(:state AS jsonb), now())
            ON CONFLICT (connection_id, dimension_key)
            DO UPDATE SET state = EXCLUDED.state, observed_at = EXCLUDED.observed_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ChannelDimensionStateStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.objectMapper = objectMapper;
    }

    @Transactional
    public int upsert(String connectionId, List<DimensionState> states) {
        if (states == null || states.isEmpty()) return 0;
        SqlParameterSource[] batch = new SqlParameterSource[states.size()];
        for (int i = 0; i < batch.length; i++) {
            DimensionState s = states.get(i);
            try {
                batch[i] = new MapSqlParameterSource()
                        .addValue("connectionId", connectionId)
                        .addValue("dimensionKey", s.dimensionKey())
                        .addValue("state", objectMapper.writeValueAsString(s.state()));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Unserializable dimension state for " + s.dimensionKey(), e);
            }
        }
        jdbc.batchUpdate(UPSERT, batch);
        return batch.length;
    }

    /** dimension key to state, for keys starting with {@code keyPrefix} across the given connections. */
    @Transactional(readOnly = true)
    public Map<String, Map<String, Object>> find(Collection<String> connectionIds, String keyPrefix) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        if (connectionIds == null || connectionIds.isEmpty()) return out;
        jdbc.query("""
                SELECT dimension_key, state::text FROM channel_dimension_state
                 WHERE connection_id IN (:ids) AND left(dimension_key, :len) = :prefix
                """,
                new MapSqlParameterSource().addValue("ids", connectionIds).addValue("len", keyPrefix.length())
                        .addValue("prefix", keyPrefix),
                rs -> {
                    try {
                        out.putIfAbsent(rs.getString(1), objectMapper.readValue(rs.getString(2),
                                new TypeReference<Map<String, Object>>() {}));
                    } catch (JsonProcessingException e) {
                        // an unreadable row is skipped; the metrics still show
                    }
                });
        return out;
    }
}
