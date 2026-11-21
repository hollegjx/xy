package com.xy.client;

import java.io.IOException;

/** 从本次发布的离线 HTML 快照生成 JPEG。调用方按自己的应用和版本存储结果。 */
public interface ScreenshotService {
    byte[] render(String snapshot) throws IOException;
}
