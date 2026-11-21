package com.xy.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;

@Configuration
public class AiModelConfiguration {

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    @ConditionalOnProperty(prefix = "xy.ai", name = "enabled", havingValue = "true")
    public ChatModel modeRoutingModel(@Value("${xy.ai.base-url}") String baseUrl,
            @Value("${xy.ai.api-key}") String apiKey, @Value("${xy.ai.model-name}") String modelName,
            @Value("${xy.ai.routing-model-name:}") String routingModelName) {
        String classifier = routingModelName.isBlank() ? modelName : routingModelName.strip();
        return OpenAiChatModel.builder().baseUrl(baseUrl).apiKey(apiKey).modelName(classifier)
                .timeout(Duration.ofSeconds(45)).maxRetries(0).maxCompletionTokens(512)
                .logRequests(false).logResponses(false).build();
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    @ConditionalOnProperty(prefix = "xy.ai", name = "enabled", havingValue = "true")
    public StreamingChatModel streamingChatModel(
            @Value("${xy.ai.base-url}") String baseUrl,
            @Value("${xy.ai.api-key}") String apiKey,
            @Value("${xy.ai.model-name}") String modelName,
            @Value("${xy.ai.timeout-seconds:180}") long timeoutSeconds,
            @Value("${xy.ai.max-completion-tokens:16384}") int maxCompletionTokens) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl).apiKey(apiKey).modelName(modelName)
                .timeout(Duration.ofSeconds(timeoutSeconds)).maxCompletionTokens(maxCompletionTokens)
                .logRequests(false).logResponses(false).build();
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
