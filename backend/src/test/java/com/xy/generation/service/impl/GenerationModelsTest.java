package com.xy.generation.service.impl;

import com.xy.app.model.enums.CodeGenTypeEnum;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GenerationModelsTest {
    @Test
    @SuppressWarnings("unchecked")
    void reusesClientForSameApplicationAndKeepsOtherApplicationsSeparate() {
        ObjectProvider<StreamingChatModel> provider = mock(ObjectProvider.class);
        var a = mock(StreamingChatModel.class);
        var b = mock(StreamingChatModel.class);
        when(provider.getIfAvailable()).thenReturn(a, b);
        ObjectProvider<StreamingChatModel> vue = mock(ObjectProvider.class);
        var reasoningModel = mock(StreamingChatModel.class);
        when(vue.getIfAvailable(any())).thenReturn(reasoningModel);
        var models = new GenerationModels(provider, vue, false);
        var first = models.forApp(7, 55, CodeGenTypeEnum.HTML);
        assertThat(models.forApp(7, 55, CodeGenTypeEnum.HTML)).isSameAs(first);
        var second = models.forApp(8, 56, CodeGenTypeEnum.HTML);
        assertThat(second.writer()).isNotSameAs(first.writer());
        assertThat(second.model()).isSameAs(b);
        var vueClient = models.forApp(7, 55, CodeGenTypeEnum.VUE_PROJECT);
        assertThat(vueClient.model()).isSameAs(reasoningModel);
        assertThat(models.forApp(7, 55, CodeGenTypeEnum.VUE_PROJECT)).isSameAs(vueClient);
        verify(vue).getIfAvailable(any());
        verify(provider, times(2)).getIfAvailable();
    }
}
