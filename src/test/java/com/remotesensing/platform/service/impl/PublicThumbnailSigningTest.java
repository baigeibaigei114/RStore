package com.remotesensing.platform.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.config.properties.MinioProperties;
import com.remotesensing.platform.mapper.FileObjectMapper;
import com.remotesensing.platform.mapper.RsImageMapper;
import io.minio.MinioClient;
import java.net.URI;
import org.junit.jupiter.api.Test;

class PublicThumbnailSigningTest {
    @Test
    void signsWebpAndPngForFiveMinutesAndUsesTheCurrentPublicReference() {
        RsImageMapper images = mock(RsImageMapper.class);
        CurrentUserContext users = mock(CurrentUserContext.class);
        FileObjectMapper files = mock(FileObjectMapper.class);
        MinioProperties properties = new MinioProperties();
        properties.setPublicEndpoint("http://127.0.0.1:19035");
        properties.setAccessKey("local-test");
        properties.setSecretKey("local-test-signing-key");
        properties.setBucketName("local-preview");
        MinioServiceImpl minio = new MinioServiceImpl(mock(MinioClient.class), properties, files, images, users);
        when(images.selectPublicThumbnailKey(3L)).thenReturn(
                "thumbnail/2026/11/3.webp", "thumbnail/2026/11/3.png", null);
        when(images.selectPublicThumbnailKey(4L)).thenReturn("raw/4.png", "thumbnail/../private.png");

        for (String extension : new String[]{"webp", "png"}) {
            var signed = minio.generatePublicThumbnailUrl(3L);
            var url = URI.create(signed.getUrl());
            assertThat(signed.getExpireSeconds()).isEqualTo(300);
            assertThat(url.getPath()).isEqualTo("/local-preview/thumbnail/2026/11/3." + extension);
            assertThat(url.getQuery()).contains("X-Amz-Expires=300", "response-cache-control=private, no-store");
        }
        assertThat(minio.generatePublicThumbnailUrl(3L)).isNull();
        assertThat(minio.generatePublicThumbnailUrl(4L)).isNull();
        assertThat(minio.generatePublicThumbnailUrl(4L)).isNull();
        verifyNoInteractions(users, files);
    }
}
