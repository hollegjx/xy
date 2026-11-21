package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.app.model.vo.AppVO;
import com.xy.app.service.AppService;
import com.xy.exception.BusinessException;
import com.xy.generation.model.dto.GenerationRequest;
import com.xy.manager.GeneratedFileManager;
import com.xy.manager.VueProjectBuildManager;
import com.xy.user.model.entity.User;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GenerationServiceImplTest {

    @Test
    void rejectsGenerationWhenTheModelIsDisabledBeforeSavingMessages() throws Exception {
        AppService apps = mock(AppService.class);
        GenerationPersistence persistence = mock(GenerationPersistence.class);
        GeneratedFileManager files = mock(GeneratedFileManager.class);
        Executor executor = mock(Executor.class);
        var unavailable = new StaticListableBeanFactory().getBeanProvider(StreamingChatModel.class);
        GenerationServiceImpl service = new GenerationServiceImpl(apps, persistence, files,
                new GenerationModels(unavailable, unavailable), executor,
                mock(ScheduledExecutorService.class),
                new ClassPathResource("prompts/single-html.txt"), new ClassPathResource("prompts/multi-file.txt"),
                new ObjectMapper(), mock(GenerationMemory.class), mock(ArtworkProviders.class), mock(VueProjectBuildManager.class), mock(OpenversePhotoSearch.class), 180, 40);
        User user = new User();
        user.setId(42L);
        when(apps.getAppById(55, user)).thenReturn(
                new AppVO("55", "42", "摄影作品集", "摄影作品集", "html", null, null, null));

        assertThatThrownBy(() -> service.generate(55, new GenerationRequest("摄影作品集"), user))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getCode()).isEqualTo(50300);
                    assertThat(error.getMessage()).isEqualTo("请先配置并启用模型服务");
                });
        verifyNoInteractions(persistence, files, executor);
    }
}
