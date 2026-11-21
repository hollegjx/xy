package com.xy.manager;

import com.qcloud.cos.COS;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.CannedAccessControlList;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "xy.cover.storage", havingValue = "cos")
class CosCoverStorage {
    private final COS client;
    private final String bucket;

    CosCoverStorage(COS client, @Value("${xy.cover.cos.bucket}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    void save(String key, byte[] jpeg) throws IOException {
        var metadata = new ObjectMetadata();
        metadata.setContentType("image/jpeg");
        metadata.setContentLength(jpeg.length);
        var request = new PutObjectRequest(bucket, key, new ByteArrayInputStream(jpeg), metadata);
        request.setCannedAcl(CannedAccessControlList.Private);
        try {
            client.putObject(request);
        } catch (CosClientException unavailable) {
            throw new IOException("COS 封面写入失败");
        }
    }

    boolean exists(String key) {
        try {
            return client.doesObjectExist(bucket, key);
        } catch (CosClientException unavailable) {
            return false; // 封面不可用不影响精选列表的其余内容
        }
    }

    byte[] read(String key) throws IOException {
        try (var object = client.getObject(bucket, key)) {
            return object.getObjectContent().readAllBytes();
        } catch (CosServiceException failure) {
            if (failure.getStatusCode() == 404) throw new NoSuchFileException("无封面");
            throw new IOException("COS 封面读取失败");
        } catch (CosClientException unavailable) {
            throw new IOException("COS 封面读取失败");
        }
    }
}
