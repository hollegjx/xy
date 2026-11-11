package com.xy.health.service.impl;

import com.xy.health.model.vo.HealthVO;
import com.xy.health.service.HealthService;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HealthServiceImpl implements HealthService {

    private final JdbcTemplate jdbcTemplate;

    public HealthServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public HealthVO check() {
        String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        if (database == null || database.isBlank()) {
            throw new DataAccessResourceFailureException("当前连接未选择数据库");
        }
        return new HealthVO("UP", database);
    }
}
