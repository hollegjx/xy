package com.xy.generation.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.xy.app.model.enums.CodeGenTypeEnum;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
final class GenerationModels {
    private record Key(long owner, long app, CodeGenTypeEnum mode) {}
    record Client(StreamingChatModel model, NativeCodeStream writer) {}
    private final ObjectProvider<StreamingChatModel> models;
    private final ObjectProvider<StreamingChatModel> vueModels;
    private final Cache<Key, Client> clients = Caffeine.newBuilder().maximumSize(1000)
            .expireAfterAccess(Duration.ofMinutes(30)).build();

    GenerationModels(ObjectProvider<StreamingChatModel> models,
            @Qualifier("vueStreamingChatModel") ObjectProvider<StreamingChatModel> vueModels) {
        this.models = models;
        this.vueModels = vueModels;
    }

    Client forApp(long owner, long app, CodeGenTypeEnum mode) {
        return clients.get(new Key(owner, app, mode), key -> {
            var model = mode == CodeGenTypeEnum.VUE_PROJECT ? vueModels.getIfAvailable(models::getIfAvailable)
                    : models.getIfAvailable();
            return model == null ? null : new Client(model, mode == CodeGenTypeEnum.VUE_PROJECT ? null : new NativeCodeStream(model));
        });
    }
}
