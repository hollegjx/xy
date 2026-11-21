package com.xy.user.host;

import com.xy.user.mapper.UserMapper;
import com.xy.user.service.impl.DatabaseUserIdentityLookup;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@EnableDubbo
@MapperScan(basePackageClasses = UserMapper.class)
@Import(DatabaseUserIdentityLookup.class)
@SpringBootApplication
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
