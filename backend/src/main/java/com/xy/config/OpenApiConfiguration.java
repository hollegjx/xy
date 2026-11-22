package com.xy.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
    @Bean
    @Profile("gateway-docs")
    OpenAPI gatewayDocument(@Value("${server.servlet.context-path:}") String contextPath) {
        return new OpenAPI().servers(List.of(new Server().url(contextPath.isEmpty() ? "/" : contextPath)));
    }

    @Bean
    OpenApiCustomizer nullableObjectReferences() {
        return api -> {
            for (Schema<?> model : api.getComponents().getSchemas().values()) {
                if (model.getProperties() == null) continue;
                for (var property : model.getProperties().values()) {
                    // OpenAPI 3.1 中 $ref 与 type:null 并列取交集；实际协议需要“对象或 null”。
                    if (property.get$ref() == null || !Set.of("null").equals(property.getTypes())) continue;
                    String target = property.get$ref();
                    property.set$ref(null);
                    property.setTypes(null);
                    property.setAnyOf(List.of(new Schema<>().$ref(target), new JsonSchema().types(Set.of("null"))));
                }
            }
        };
    }
}
