package com.xy.config;

import com.xy.exception.BusinessException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentConfigurationTest {

    @Test
    void unconfiguredHostingCannotReturnAPublicationSuccess() {
        var configuration = new DeploymentConfiguration(Path.of("published"), "", "http://localhost:5173");
        assertThat(configuration.siteUrl("abc")).isNull();
        assertThatThrownBy(configuration::requireConfigured).isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未配置独立静态站点");
    }

    @Test
    void changingOnlyThePortDoesNotIsolatePlatformCookies() {
        assertThatThrownBy(() -> new DeploymentConfiguration(Path.of("published"),
                "http://localhost:8102", "http://localhost:5173")).isInstanceOf(IllegalArgumentException.class);
        var configuration = new DeploymentConfiguration(Path.of("published"),
                "http://127.0.0.1:8102/", "http://localhost:5173");
        configuration.requireConfigured();
        assertThat(configuration.siteUrl("abc")).isEqualTo("http://127.0.0.1:8102/abc/");
    }
}
