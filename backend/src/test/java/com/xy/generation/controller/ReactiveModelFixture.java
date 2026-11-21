package com.xy.generation.controller;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

/** 同一批可控模型回执供两种 SDK 入口使用；取消订阅也必须停止替身。 */
final class ReactiveModelFixture {
    static Flow.Publisher<ChatModelStreamingEvent> publish(StreamingChatModel mock, ChatRequest request) {
        return JdkFlowAdapter.publisherToFlowPublisher(Flux.create(sink -> {
            var handle = new AtomicReference<StreamingHandle>();
            sink.onCancel(() -> {
                var current = handle.getAndSet(null);
                if (current != null) current.cancel();
            });
            mock.chat(request, new StreamingChatResponseHandler() {
                @Override public void onPartialResponse(String text) { sink.next(new PartialResponse(text)); }
                @Override public void onPartialResponse(PartialResponse part, PartialResponseContext context) {
                    handle.set(context.streamingHandle());
                    sink.next(part);
                }
                @Override public void onPartialToolCall(PartialToolCall part, PartialToolCallContext context) {
                    handle.set(context.streamingHandle());
                    if (part != null) sink.next(part);
                }
                @Override public void onCompleteResponse(ChatResponse response) {
                    sink.next(new CompleteResponse(response));
                    sink.complete();
                }
                @Override public void onError(Throwable error) { sink.error(error); }
            });
        }));
    }
    private ReactiveModelFixture() {}
}
