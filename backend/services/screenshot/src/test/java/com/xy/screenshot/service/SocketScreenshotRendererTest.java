package com.xy.screenshot.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SocketScreenshotRendererTest {
    @Test
    void aTruncatedCoverFailsAndTheNextSnapshotCanReturnItsOwnJpeg() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "jpeg", bytes);
        byte[] jpeg = bytes.toByteArray();
        try (var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(null)) {
            var path = ((UnixDomainSocketAddress) listener.getLocalAddress()).getPath();
            try {
                var provider = new ScreenshotProvider("/does-not-run-docker", path.toString());
                var worker = new FutureTask<Void>(() -> {
                    for (int i = 1; i <= 2; i++) {
                        try (var connection = listener.accept()) {
                            var received = Channels.newInputStream(connection).readAllBytes();
                            assertEquals("<p>作品 " + i + "</p>", new String(received, StandardCharsets.UTF_8));
                            Channels.newOutputStream(connection).write(i == 1 ? Arrays.copyOf(jpeg, 20) : jpeg);
                        }
                    }
                    return null;
                });
                Thread.startVirtualThread(worker);
                assertThrows(IOException.class, () -> provider.render("<p>作品 1</p>"));
                assertArrayEquals(jpeg, provider.render("<p>作品 2</p>"));
                worker.get(3, TimeUnit.SECONDS);
            } finally {
                Files.deleteIfExists(path);
            }
        }
    }
}
