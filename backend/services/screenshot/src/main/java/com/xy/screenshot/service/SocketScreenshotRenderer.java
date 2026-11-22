package com.xy.screenshot.service;

import com.xy.client.ScreenshotService;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 每次发布使用一个本地套接字：写完快照后半关闭，继续读取这一页的 JPEG。 */
final class SocketScreenshotRenderer implements ScreenshotService {
    private final UnixDomainSocketAddress address;

    SocketScreenshotRenderer(String path) {
        this.address = UnixDomainSocketAddress.of(path);
    }

    @Override
    public byte[] render(String snapshot) throws IOException {
        try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            var request = new FutureTask<byte[]>(() -> {
                channel.connect(address);
                Channels.newOutputStream(channel).write(snapshot.getBytes(StandardCharsets.UTF_8));
                channel.shutdownOutput();
                byte[] jpeg = Channels.newInputStream(channel).readAllBytes();
                if (jpeg.length < 4 || jpeg[0] != (byte) 0xff || jpeg[1] != (byte) 0xd8
                        || jpeg[jpeg.length - 2] != (byte) 0xff || jpeg[jpeg.length - 1] != (byte) 0xd9) {
                    throw new IOException("截图服务没有返回完整 JPEG");
                }
                return jpeg;
            });
            Thread.startVirtualThread(request);
            try {
                return request.get(16, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("封面截图中断", interrupted);
            } catch (TimeoutException timeout) {
                throw new IOException("封面截图超时", timeout);
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof IOException io) throw io;
                throw new IOException("封面截图失败", failed.getCause());
            }
        } // 超时或中断时关闭通道，同时唤醒正在等待渲染器的线程。
    }
}
