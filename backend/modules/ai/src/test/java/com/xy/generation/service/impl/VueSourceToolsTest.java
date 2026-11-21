package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class VueSourceToolsTest {
    private final VueSourceTools.Draft draft = mock(VueSourceTools.Draft.class);
    private final VueSourceTools tools = new VueSourceTools(new ObjectMapper(), draft);

    @Test
    void readsTheSuppliedDraftAndDoesNotSendSourceTextInProgress() throws Exception {
        when(draft.read("src/Card.vue")).thenReturn("<template>本轮草稿</template>");
        var reply = tools.execute(call("readFile", """
                {"relativePath":"src/Card.vue"}
                """));
        assertThat(reply.receipt()).isEqualTo("<template>本轮草稿</template>");
        assertThat(reply.progress()).isEqualTo("已读取：src/Card.vue");
    }

    @Test
    void rejectsNonTextContentBeforeItCanChangeTheDraft() {
        assertThatThrownBy(() -> tools.execute(call("writeFile", """
                {"relativePath":"src/Card.vue","content":42}
                """))).isInstanceOf(VueSourceTools.Rejected.class).hasMessage(VueSourceTools.FILE_ERROR);
        verifyNoInteractions(draft);
    }

    @Test
    void keepsTheUsefulFragmentErrorButDoesNotExposeStorageErrors() throws Exception {
        when(draft.replace("src/Card.vue", "原片段", "新片段")).thenReturn(false);
        assertThatThrownBy(() -> tools.execute(call("replaceFragment", """
                {"relativePath":"src/Card.vue","oldText":"原片段","newText":"新片段"}
                """))).isInstanceOf(VueSourceTools.Rejected.class).hasMessageContaining("未找到或匹配多处");
        when(draft.read("src/Card.vue")).thenThrow(new IOException("内部存储细节"));
        assertThatThrownBy(() -> tools.execute(call("readFile", """
                {"relativePath":"src/Card.vue"}
                """))).isInstanceOf(VueSourceTools.Rejected.class).hasMessage(VueSourceTools.FILE_ERROR);
    }

    private static ToolExecutionRequest call(String name, String args) {
        return ToolExecutionRequest.builder().name(name).arguments(args).build();
    }
}
