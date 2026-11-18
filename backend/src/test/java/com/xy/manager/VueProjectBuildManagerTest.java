package com.xy.manager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VueProjectBuildManagerTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"success", "failure", "cancelled", "timeout"})
    void runsOnlyTheVersionSnapshotAndCleansUpItsContainer(String outcome) throws Exception {
        var paths = new GeneratedFileManager.VueBuildPaths(Files.createDirectory(directory.resolve("source")),
                Files.createDirectory(directory.resolve("output")));
        var builder = spy(new VueProjectBuildManager(true, "docker", "xy-vue-builder:local", 0));
        Process process = mock(Process.class), cleanup = mock(Process.class);
        when(process.waitFor(100, TimeUnit.MILLISECONDS)).thenReturn(outcome.equals("success") || outcome.equals("failure"));
        when(process.exitValue()).thenReturn(outcome.equals("failure") ? 1 : 0);
        when(cleanup.waitFor(5, TimeUnit.SECONDS)).thenReturn(true);
        doReturn(process, cleanup).when(builder).start(anyList());
        if (outcome.equals("success")) builder.build(paths, () -> true);
        else assertThatThrownBy(() -> builder.build(paths, () -> !outcome.equals("cancelled"))).isInstanceOf(IOException.class);

        @SuppressWarnings("unchecked") ArgumentCaptor<List<String>> commands = ArgumentCaptor.forClass(List.class);
        verify(builder, times(2)).start(commands.capture());
        List<String> run = commands.getAllValues().getFirst();
        assertThat(run).contains("--network=none", "--read-only", "--pull=never", "--cap-drop=ALL",
                "type=bind,source=" + paths.source() + ",target=/source,readonly",
                "type=bind,source=" + paths.output() + ",target=/output");
        assertThat(commands.getAllValues().getLast()).containsExactly("docker", "rm", "--force", run.get(5));
        verify(process).destroyForcibly();
    }
}
