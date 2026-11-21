package com.xy.generation.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;

/** 一批素材复用生成执行器；会话停止时同时唤醒汇总方并中断供应方调用。 */
final class WorkflowAssetTasks implements Executor {
    private final Executor executor;
    private final List<FutureTask<Void>> workers = new ArrayList<>();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();

    WorkflowAssetTasks(Executor executor) { this.executor = executor; }

    @Override public void execute(Runnable work) {
        var worker = new FutureTask<Void>(work, null);
        synchronized (this) {
            if (stopped.isDone()) throw new CancellationException();
            workers.add(worker);
        }
        executor.execute(worker);
    }

    void await(List<? extends CompletableFuture<?>> results) {
        var ready = CompletableFuture.allOf(results.toArray(CompletableFuture<?>[]::new));
        CompletableFuture.anyOf(ready, stopped).join();
    }

    synchronized void stop() {
        stopped.complete(null);
        // CompletableFuture.cancel(true) 不会中断正在运行的 HTTP / 图像 SDK 调用。
        workers.forEach(worker -> worker.cancel(true));
    }
}
