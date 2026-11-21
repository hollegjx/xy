package com.xy.config;

import com.xy.manager.AiModelMetrics;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;

@Configuration
public class AiModelConfiguration {
    private final Optional<AiModelMetrics> metrics;

    public AiModelConfiguration(Optional<AiModelMetrics> metrics) {
        this.metrics = metrics;
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    @ConditionalOnProperty(prefix = "xy.ai", name = "enabled", havingValue = "true")
    public ChatModel modeRoutingModel(@Value("${xy.ai.base-url}") String baseUrl,
            @Value("${xy.ai.api-key}") String apiKey, @Value("${xy.ai.model-name}") String modelName,
            @Value("${xy.ai.routing-model-name:}") String routingModelName) {
        String classifier = routingModelName.isBlank() ? modelName : routingModelName.strip();
        var builder = OpenAiChatModel.builder().baseUrl(baseUrl).apiKey(apiKey).modelName(classifier)
                .timeout(Duration.ofSeconds(45)).maxRetries(0).maxCompletionTokens(512)
                .logRequests(false).logResponses(false);
        metrics.ifPresent(m -> builder.listeners(m.listener("routing", classifier)));
        return builder.build();
    }

    @Bean
    @Primary
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    @ConditionalOnProperty(prefix = "xy.ai", name = "enabled", havingValue = "true")
    public StreamingChatModel streamingChatModel(
            @Value("${xy.ai.base-url}") String baseUrl,
            @Value("${xy.ai.api-key}") String apiKey,
            @Value("${xy.ai.model-name}") String modelName,
            @Value("${xy.ai.timeout-seconds:180}") long timeoutSeconds,
            @Value("${xy.ai.max-completion-tokens:16384}") int maxCompletionTokens,
            @Value("${xy.ai.quality-json-schema:false}") boolean jsonSchema) {
        return streamingModel("native", baseUrl, apiKey, modelName, timeoutSeconds, maxCompletionTokens, jsonSchema);
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    @ConditionalOnProperty(prefix = "xy.ai", name = "enabled", havingValue = "true")
    public StreamingChatModel vueStreamingChatModel(@Value("${xy.ai.base-url}") String baseUrl,
            @Value("${xy.ai.api-key}") String apiKey, @Value("${xy.ai.model-name}") String defaultName,
            @Value("${xy.ai.vue-model-name:}") String vueName, @Value("${xy.ai.timeout-seconds:180}") long timeoutSeconds,
            @Value("${xy.ai.vue-max-completion-tokens:${xy.ai.max-completion-tokens:16384}}") int maxCompletionTokens,
            @Value("${xy.ai.quality-json-schema:false}") boolean jsonSchema) {
        return streamingModel("vue", baseUrl, apiKey, vueName.isBlank() ? defaultName : vueName.strip(), timeoutSeconds, maxCompletionTokens, jsonSchema);
    }

    private StreamingChatModel streamingModel(String client, String baseUrl, String apiKey, String modelName,
            long timeoutSeconds, int maxCompletionTokens, boolean jsonSchema) {
        var builder = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl).apiKey(apiKey).modelName(modelName)
                .timeout(Duration.ofSeconds(timeoutSeconds)).maxCompletionTokens(maxCompletionTokens)
                .logRequests(false).logResponses(false);
        metrics.ifPresent(m -> builder.listeners(m.listener(client, modelName)));
        if (!jsonSchema) return builder.build();
        // 仅向 SDK 声明网关支持该能力；普通源码或文件工具请求不会被强制设为 JSON。
        return new OpenAiStreamingChatModel(builder.strictJsonSchema(true)) {
            @Override public Set<Capability> supportedCapabilities() { return Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA); }
        };
    }

    @Bean(destroyMethod = "close")
    public ExecutorService generationExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService generationHeartbeatScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
