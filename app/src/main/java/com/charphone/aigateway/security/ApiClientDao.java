package com.charphone.aigateway.security;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * api_client 資料存取（aigateway DB）。
 */
@Repository
public class ApiClientDao {

    private final JdbcTemplate jdbcTemplate;

    public ApiClientDao(@Qualifier("gatewayJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ApiClient> findByKeyHash(String apiKeyHash) {
        return jdbcTemplate.query(
                "SELECT id, client_name, api_key_hash, enabled, role, rate_limit_daily, rate_limit_per_min "
                        + "FROM api_client WHERE api_key_hash = ?",
                rs -> {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs));
                    }
                    return Optional.empty();
                },
                apiKeyHash);
    }

    public Optional<ApiClient> findById(int id) {
        return jdbcTemplate.query(
                "SELECT id, client_name, api_key_hash, enabled, role, rate_limit_daily, rate_limit_per_min "
                        + "FROM api_client WHERE id = ?",
                rs -> {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs));
                    }
                    return Optional.empty();
                },
                id);
    }

    public List<ApiClient> findAll() {
        return jdbcTemplate.query(
                "SELECT id, client_name, api_key_hash, enabled, role, rate_limit_daily, rate_limit_per_min, "
                        + "created_at FROM api_client ORDER BY id",
                rs -> {
                    List<ApiClient> list = new java.util.ArrayList<>();
                    while (rs.next()) {
                        list.add(mapRow(rs));
                    }
                    return list;
                });
    }

    /** 新增 API Key，回傳 generated id。 */
    public int insert(String clientName, String apiKeyHash, String role, int rateLimitDaily, int rateLimitPerMin) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(conn -> {
            var ps = conn.prepareStatement(
                    "INSERT INTO api_client (client_name, api_key_hash, role, rate_limit_daily, rate_limit_per_min) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    new String[] { "id" });
            ps.setString(1, clientName);
            ps.setString(2, apiKeyHash);
            ps.setString(3, role);
            ps.setInt(4, rateLimitDaily);
            ps.setInt(5, rateLimitPerMin);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().intValue();
    }

    public void update(int id, Boolean enabled, Integer rateLimitDaily, Integer rateLimitPerMin) {
        StringBuilder sql = new StringBuilder("UPDATE api_client SET ");
        java.util.List<Object> params = new java.util.ArrayList<>();
        java.util.List<String> sets = new java.util.ArrayList<>();
        if (enabled != null) {
            sets.add("enabled = ?");
            params.add(enabled ? 1 : 0);
        }
        if (rateLimitDaily != null) {
            sets.add("rate_limit_daily = ?");
            params.add(rateLimitDaily);
        }
        if (rateLimitPerMin != null) {
            sets.add("rate_limit_per_min = ?");
            params.add(rateLimitPerMin);
        }
        if (sets.isEmpty()) return;
        sql.append(String.join(", ", sets)).append(" WHERE id = ?");
        params.add(id);
        jdbcTemplate.update(sql.toString(), params.toArray());
    }

    public void delete(int id) {
        jdbcTemplate.update("DELETE FROM api_client WHERE id = ?", id);
    }

    private ApiClient mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApiClient(
                rs.getInt("id"),
                rs.getString("client_name"),
                rs.getString("api_key_hash"),
                rs.getInt("enabled") == 1,
                rs.getString("role"),
                rs.getInt("rate_limit_daily"),
                rs.getInt("rate_limit_per_min"));
    }
}
