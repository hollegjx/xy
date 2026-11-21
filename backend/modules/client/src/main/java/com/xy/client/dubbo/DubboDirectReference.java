package com.xy.client.dubbo;

import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;

/** 用 Triple 直连独立的 XY 服务；首次调用才连接，不重试已发出的请求。 */
public final class DubboDirectReference<T> implements AutoCloseable {
    private final DubboBootstrap bootstrap;
    private final ReferenceConfig<T> reference = new ReferenceConfig<>();

    public DubboDirectReference(Class<T> service, String address, int timeoutMillis) {
        if (address == null || !address.startsWith("tri://")) {
            throw new IllegalArgumentException("内部服务需要 tri:// 地址");
        }
        var application = new ApplicationConfig("xy-app");
        application.setQosEnable(false);
        reference.setInterface(service);
        // Dubbo 会把普通 IPv4 回环主机名替换为网卡地址；映射字面量仍由 JDK 作为 IPv4 回环连接。
        reference.setUrl(address.replaceFirst("(?i)^tri://(?:127\\.0\\.0\\.1|localhost)(?=[:/?#]|$)",
                "tri://[::ffff:127.0.0.1]"));
        reference.setScope("remote");
        reference.setTimeout(timeoutMillis);
        reference.setRetries(0);
        reference.setCheck(false);
        reference.setInit(false);
        reference.setLazy(true);
        bootstrap = DubboBootstrap.newInstance()
                .application(application).registry(new RegistryConfig("N/A")).reference(reference).start();
    }

    public T service() {
        return reference.get();
    }

    @Override
    public void close() {
        bootstrap.destroy();
    }
}
