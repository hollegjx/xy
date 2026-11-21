package com.xy.client.dubbo;

import com.xy.client.ScreenshotService;
import java.io.IOException;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;

/** 只在应用选择远程截图时创建。连接失败只影响当次后台截图。 */
public final class DubboScreenshotClient implements ScreenshotService, AutoCloseable {
    private final DubboBootstrap bootstrap;
    private final ReferenceConfig<ScreenshotService> reference = new ReferenceConfig<>();

    public DubboScreenshotClient(String address) {
        if (address == null || !address.startsWith("tri://")) {
            throw new IllegalArgumentException("截图服务需要 tri:// 地址");
        }
        var application = new ApplicationConfig("xy-app");
        application.setQosEnable(false);
        reference.setInterface(ScreenshotService.class);
        reference.setUrl(address);
        reference.setScope("remote");
        reference.setTimeout(22_000);
        reference.setRetries(0);
        reference.setCheck(false);
        reference.setInit(false);
        reference.setLazy(true);
        bootstrap = DubboBootstrap.newInstance()
                .application(application).registry(new RegistryConfig("N/A")).reference(reference).start();
    }

    @Override
    public byte[] render(String snapshot) throws IOException {
        return reference.get().render(snapshot);
    }

    @Override
    public void close() {
        bootstrap.destroy();
    }
}
