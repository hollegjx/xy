package com.xy;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MainApplication {

    public static void main(String[] args) {
        var application = new SpringApplication(MainApplication.class);
        // 即使部署用 spring.config.location 完全替换了内置配置，单体也不启动 Dubbo 注册。
        application.setDefaultProperties(Map.of("dubbo.enabled", "false"));
        application.run(args);
    }
}
