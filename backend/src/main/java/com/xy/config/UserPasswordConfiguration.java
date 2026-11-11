package com.xy.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm;

@Configuration(proxyBeanMethods = false)
public class UserPasswordConfiguration {

    @Bean
    public PasswordEncoder passwordEncoder() {
        Pbkdf2PasswordEncoder encoder = new Pbkdf2PasswordEncoder(
                "", 16, 600_000, SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
        // 将参数版本写进散列前缀，避免后续调整参数时误用新参数校验旧密码。
        String encodingId = "pbkdf2-sha256-600000";
        return new DelegatingPasswordEncoder(encodingId, Map.of(encodingId, encoder));
    }
}
