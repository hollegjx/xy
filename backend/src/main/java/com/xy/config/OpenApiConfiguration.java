package com.xy.config;

import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
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
