package com.xy.generation;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import redis.embedded.RedisServer;

public final class GenerationTestRedis {
    private GenerationTestRedis() {}

    public static RedisServer start(Path directory) throws IOException {
        int port;
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        var redis = RedisServer.newRedisServer().bind("127.0.0.1").port(port)
                .setting("dir \"" + directory + "\"").setting("save \"\"").setting("appendonly no").build();
        redis.start();
        return redis;
    }
}
