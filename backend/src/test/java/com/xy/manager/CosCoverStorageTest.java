package com.xy.manager;

import com.qcloud.cos.COS;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.CannedAccessControlList;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.PutObjectRequest;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CosCoverStorageTest {
    @Test
    void writesPrivateVersionedCoverAndReadsItFromAnotherBackend(@TempDir Path dir) throws Exception {
        COS cos = mock(COS.class);
        var store = Optional.of(new CosCoverStorage(cos, "bucket-test"));
        var publisher = new GeneratedFileManager(dir.resolve("a"), store);
        var reader = new GeneratedFileManager(dir.resolve("b"), store);
        byte[] jpeg = {1, 2, 3};
        publisher.saveCover(7, 55, 101, jpeg);
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(cos).putObject(request.capture());
        String key = "xy/covers/7/55/101.jpg";
        assertThat(request.getValue().getKey()).isEqualTo(key);
        assertThat(request.getValue().getCannedAcl()).isEqualTo(CannedAccessControlList.Private);
        assertThat(request.getValue().getInputStream().readAllBytes()).containsExactly(jpeg);
        var stored = new COSObject();
        stored.setObjectContent(new com.qcloud.cos.model.COSObjectInputStream(new ByteArrayInputStream(jpeg),
                new org.apache.http.client.methods.HttpGet()));
        when(cos.getObject("bucket-test", key)).thenReturn(stored);
        when(cos.doesObjectExist("bucket-test", key)).thenReturn(true);
        assertThat(reader.hasCover(7, 55, 101)).isTrue();
        assertThat(reader.readCover(7, 55, 101)).containsExactly(jpeg);
        assertThat(reader.hasCover(7, 55, 102)).isFalse();

        var legacy = new GeneratedFileManager(dir);
        legacy.saveHtml(7, 55, 90, "<!DOCTYPE html><html><head></head><body>old</body></html>");
        legacy.saveCover(7, 55, 90, jpeg);
        var missing = new CosServiceException("test"); missing.setStatusCode(404);
        when(cos.getObject("bucket-test", "xy/covers/7/55/90.jpg")).thenThrow(missing);
        assertThat(new GeneratedFileManager(dir, store).readCover(7, 55, 90)).containsExactly(jpeg);
    }
}
