package com.xy.client.dubbo;

import com.xy.client.ScreenshotService;
import java.io.IOException;

/** 只在应用选择远程截图时创建。连接失败只影响当次后台截图。 */
public final class DubboScreenshotClient implements ScreenshotService, AutoCloseable {
    private final DubboDirectReference<ScreenshotService> reference;

    public DubboScreenshotClient(String address) {
        reference = new DubboDirectReference<>(ScreenshotService.class, address, 22_000);
    }

    @Override
    public byte[] render(String snapshot) throws IOException {
        return reference.service().render(snapshot);
    }

    @Override
    public void close() {
        reference.close();
    }
}
