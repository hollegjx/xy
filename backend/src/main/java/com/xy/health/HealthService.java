package com.xy.health;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HealthService {

    private final JdbcTemplate jdbcTemplate;

    public HealthService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public HealthVO check() {
        String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        if (database == null || database.isBlank()) {
            throw new DataAccessResourceFailureException("当前连接未选择数据库");
        }
        return new HealthVO("UP", database);
    }
}
