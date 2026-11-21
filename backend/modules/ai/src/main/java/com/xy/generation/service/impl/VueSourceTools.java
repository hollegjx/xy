package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import java.io.IOException;
import java.util.List;

/** Vue 源码工具。调用方只交给它本轮草稿，不向模型提供用户、应用或版本的选择权。 */
public final class VueSourceTools {
    public interface Draft {
        List<String> list() throws IOException;
        String read(String path) throws IOException;
        void write(String path, String content) throws IOException;
        boolean replace(String path, String before, String after) throws IOException;
        void delete(String path) throws IOException;
    }

    public record Reply(String receipt, String progress) {}

    public static final String FILE_ERROR = "Vue 工程文件操作失败，请检查工具参数和文件路径后重试";
    private static final String NO_MATCH = "原代码片段未找到或匹配多处，未替换，请重试";
    public static final List<ToolSpecification> SPECIFICATIONS = List.copyOf(ToolSpecifications.toolSpecificationsFrom(Actions.class));

    public static final class Rejected extends Exception {
        private Rejected(String message) { super(message); }
    }

    private final ObjectMapper mapper;
    private final Draft draft;

    public VueSourceTools(ObjectMapper mapper, Draft draft) {
        this.mapper = mapper;
        this.draft = draft;
    }

    public Reply execute(ToolExecutionRequest call) throws Rejected {
        var spec = SPECIFICATIONS.stream().filter(item -> item.name().equals(call.name())).findFirst()
                .orElseThrow(() -> new Rejected(FILE_ERROR));
        try {
            var args = mapper.readTree(call.arguments());
            if (args == null || !args.isObject() || spec.parameters() != null
                    && !spec.parameters().required().stream().allMatch(k -> args.path(k).isTextual()))
                throw new Rejected(FILE_ERROR);
            var actions = new Actions();
            var result = new DefaultToolExecutor(actions, call).executeWithContext(call, InvocationContext.builder().build());
            if (actions.unmatched) throw new Rejected(NO_MATCH);
            if (result.isError()) throw new Rejected(FILE_ERROR);
            return new Reply(result.resultText(), actions.progress);
        } catch (IOException | RuntimeException invalid) {
            throw new Rejected(FILE_ERROR);
        }
    }

    private final class Actions {
        private String progress;
        private boolean unmatched;

        @Tool("列出本次 Vue 草稿 src 内已有的源码路径，无需参数")
        public String listFiles() throws IOException {
            progress = "已列出工程文件";
            return mapper.writeValueAsString(draft.list());
        }

        @Tool("读取 src 源文件，或按工程清单中的照片路径读取只读署名")
        public String readFile(@P("相对路径") String relativePath) throws IOException {
            progress = "已读取：" + relativePath;
            return draft.read(relativePath);
        }

        @Tool("写入本次工程 src 中的完整源码，不可修改启动入口 src/main.js")
        public String writeFile(@P("相对路径") String relativePath, @P("完整源码") String content) throws IOException {
            draft.write(relativePath, content);
            progress = "已写入：" + relativePath;
            return "文件已保存：" + relativePath;
        }

        @Tool("局部修改源文件，仅替换原文中唯一匹配的片段")
        public String replaceFragment(@P("相对路径") String relativePath, @P("原文片段") String oldText,
                @P("替换后的片段") String newText) throws IOException {
            if (!draft.replace(relativePath, oldText, newText)) {
                unmatched = true;
                return NO_MATCH;
            }
            return progress = "已局部修改：" + relativePath;
        }

        @Tool("删除不再使用的非入口 .vue 组件，须先清理引用")
        public String deleteComponent(@P("相对路径") String relativePath) throws IOException {
            draft.delete(relativePath);
            return progress = "已删除组件：" + relativePath;
        }
    }
}
