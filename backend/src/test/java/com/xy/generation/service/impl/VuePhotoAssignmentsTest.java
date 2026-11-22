package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VuePhotoAssignmentsTest {
    @Test
    void onlyPublicThemesReachTheProviderAndEveryUseKeepsItsOwnLocation() {
        var search = mock(OpenversePhotoSearch.class);
        var calls = List.of(
                ToolExecutionRequest.builder().id("private").name("findPhoto")
                        .arguments("{\"theme\":\"Alex Doe\",\"purpose\":\"作者介绍\"}").build(),
                ToolExecutionRequest.builder().id("banner").name("findPhoto")
                        .arguments("{\"theme\":\"coffee\",\"purpose\":\"Alex Doe 的页首\"}").build(),
                use("coffee"));
        var results = new VuePhotos().collect(calls, new ObjectMapper(), search, Runnable::run, () -> true).join();
        assertThat(results).extracting(c -> c.use().purpose()).containsExactly("作者介绍", "Alex Doe 的页首", "");
        assertThat(results).extracting(c -> c.use().path()).containsExactly("xy-photo.json", "xy-photo-2.json", "xy-photo-3.json");
        verify(search).findMany("coffee", 2, Set.of());
        verifyNoMoreInteractions(search);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void leavesTheOnlyAvailableOriginalForTheThemeWithNoAlternative(boolean coffeeReturnsFirst) {
        var search = mock(OpenversePhotoSearch.class);
        var catalog = Map.of("coffee", List.of("101", "102"), "nature", List.of("101"), "travel", List.of("101", "102", "103"));
        when(search.findMany(anyString(), anyInt(), anySet())).thenAnswer(i -> {
            String theme = i.getArgument(0);
            Set<String> excluded = i.getArgument(2);
            return catalog.get(theme).stream().filter(id -> !excluded.contains(id)).limit(i.<Integer>getArgument(1))
                    .map(id -> new OpenversePhotoSearch.Photo("jpeg:" + id, "https://www.flickr.com/photos/" + theme + "/" + id, "CC0")).toList();
        });
        var tasks = new ArrayDeque<Runnable>();
        var photos = new VuePhotos();
        var json = new ObjectMapper();
        var batch = photos.collect(List.of(use("coffee"), use("nature")), json, search, tasks::add, () -> true);
        assertThat(tasks).as("每个主题的首批查询均已提交").hasSize(2);
        (coffeeReturnsFirst ? tasks.removeFirst() : tasks.removeLast()).run();
        while (!tasks.isEmpty()) tasks.removeFirst().run();
        assertThat(batch.join()).extracting(c -> c.use().path() + ":" + c.photo().workId())
                .containsExactly("xy-photo.json:102", "xy-photo-2.json:101");
        var later = photos.collect(List.of(use("travel")), json, search, Runnable::run, () -> true).join();
        assertThat(later.getFirst().photo().workId()).isEqualTo("103");
        verify(search).findMany("travel", 1, Set.of("101", "102"));
    }

    private static ToolExecutionRequest use(String theme) {
        return ToolExecutionRequest.builder().id(theme).name("findPhoto").arguments("{\"theme\":\"" + theme + "\"}").build();
    }
}
