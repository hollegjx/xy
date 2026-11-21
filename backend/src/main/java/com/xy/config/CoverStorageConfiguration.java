package com.xy.config;

import com.qcloud.cos.COS;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.region.Region;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "xy.cover.storage", havingValue = "cos")
public class CoverStorageConfiguration {
    @Bean(destroyMethod = "shutdown")
    COS coverCos(@Value("${xy.cover.cos.region}") String region, @Value("${xy.cover.cos.secret-id}") String id,
            @Value("${xy.cover.cos.secret-key}") String key) {
        var config = new ClientConfig(new Region(region));
        config.setHttpProtocol(HttpProtocol.https);
        config.setConnectionTimeout(2000);
        config.setSocketTimeout(5000);
        return new COSClient(new BasicCOSCredentials(id, key), config);
    }
}
