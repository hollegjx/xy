package com.xy.config;

import com.xy.common.ErrorCode;
import com.xy.exception.BusinessException;
import java.net.URI;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DeploymentConfiguration {

    private final Path directory;
    private final String publicUrl;

    public DeploymentConfiguration(
            @Value("${xy.deployment.directory:./published}") Path directory,
            @Value("${xy.deployment.public-url:}") String publicUrl,
            @Value("${xy.deployment.platform-origin:http://localhost:5173}") String platformOrigin) {
        this.directory = directory.toAbsolutePath().normalize();
        this.publicUrl = publicUrl.strip().replaceAll("/+$", "");
        if (!this.publicUrl.isEmpty()) {
            URI site = URI.create(this.publicUrl);
            URI platform = URI.create(platformOrigin);
            if (!isOrigin(site) || !isOrigin(platform)
                    || site.getHost().equalsIgnoreCase(platform.getHost())) {
                throw new IllegalArgumentException("发布地址必须为独立 HTTP(S) 站点，且不能与平台使用相同主机名（即使端口不同）");
            }
        }
    }

    public Path directory() {
        return directory;
    }

    public String siteUrl(String deployKey) {
        return publicUrl.isEmpty() ? null : publicUrl + "/" + deployKey + "/";
    }

    public void requireConfigured() {
        if (publicUrl.isEmpty()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "尚未配置独立静态站点，请先配置发布目录和地址");
        }
    }

    private boolean isOrigin(URI uri) {
        return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null
                && uri.getRawQuery() == null && uri.getRawFragment() == null
                && (uri.getPath().isEmpty() || "/".equals(uri.getPath()));
    }
}
