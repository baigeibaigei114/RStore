package com.remotesensing.platform.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.config.properties.UploadProperties;
import com.remotesensing.platform.dto.RsImageCreateDTO;
import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.mapper.RsImageMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import com.remotesensing.platform.service.*;
import com.remotesensing.platform.vo.GeoTiffMetadataVO;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class RsImageServiceImplTest {
    @Test void standardizedUploadCompensatesWhenDatabaseFails() throws Exception {
        // 栅格解析使用测试替身；真实 Rasterio 路径由端到端测试覆盖。
        var json = new ObjectMapper();
        var actual = json.readValue("""
                {"width":500,"height":500,"bandCount":4,"crs":"EPSG:32650",
                 "bandDescriptions":["B02","B03","B04","B08"],
                 "dtypes":["float32","float32","float32","float32"],
                 "inputUnits":"surface_reflectance","nodata":-9999,
                 "scales":[1,1,1,1],"offsets":[0,0,0,0],
                 "transform":[10,0,402460,0,-10,3202570]}
                """, GeoTiffMetadataVO.class);
        var manifest = (com.fasterxml.jackson.databind.node.ObjectNode)
                json.readTree(java.nio.file.Files.readString(Path.of("shared/ingestion/example-public-v1.json")));
        byte[] bytes = {1, 2, 3};
        manifest.put("sha256", java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        when(parser.parse(any(Path.class))).thenReturn(actual);
        when(user.getCurrentUserId()).thenReturn("test-user");
        when(storage.uploadGeoTiff(any(Path.class), anyString(), anyString()))
                .thenReturn(new com.remotesensing.platform.vo.MinioUploadVO("test", "raw/test.tif", 3L, "image/tiff"));
        when(images.insert(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("test db failure"));
        assertThatThrownBy(() -> service.uploadStandardized(
                new MockMultipartFile("file", "test.tif", "image/tiff", bytes),
                new MockMultipartFile("manifest", "test.json", "application/json", json.writeValueAsBytes(manifest)),
                "test")).hasMessageContaining("影像记录保存失败");
        verify(storage).deleteObject("raw/test.tif");
        var path = ArgumentCaptor.forClass(Path.class);
        verify(parser).parse(path.capture());
        assertThat(path.getValue()).doesNotExist();
    }

    private final RsImageMapper images = mock(RsImageMapper.class);
    private final MinioService storage = mock(MinioService.class);
    private final GeoTiffMetadataService parser = mock(GeoTiffMetadataService.class);
    private final CurrentUserContext user = mock(CurrentUserContext.class);
    private final ThumbnailAsyncService thumbnails = mock(ThumbnailAsyncService.class);
    private final TestTransactionManager transactions = new TestTransactionManager();
    private final RsImageServiceImpl service = new RsImageServiceImpl(images, mock(RsTaskMapper.class),
            storage, parser, new ImageBandCapabilityServiceImpl(new ObjectMapper()),
            thumbnails, new ObjectMapper(), new UploadProperties(), transactions, user);

    /** 执行真实 Spring 同步回调，但不连接数据库。 */
    private static class TestTransactionManager extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
        boolean unknownCommit;
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition definition) {}
        protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
            if (unknownCommit) throw new org.springframework.transaction.TransactionSystemException("unknown commit");
        }
        protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {}
    }

    private void prepareOrdinaryUpload() {
        var metadata = new GeoTiffMetadataVO();
        when(parser.parse(any(Path.class))).thenReturn(metadata);
        when(user.getCurrentUserId()).thenReturn("test-user");
        when(storage.uploadGeoTiff(any(Path.class), anyString(), anyString()))
                .thenReturn(new com.remotesensing.platform.vo.MinioUploadVO("test", "raw/test.tif", 3L, "image/tiff"));
        when(images.insert(any())).thenAnswer(call -> {
            ((RsImage) call.getArgument(0)).setId(1L);
            return 1;
        });
    }

    private void uploadOrdinary() {
        service.upload(new MockMultipartFile("file", "test.tif", "image/tiff", new byte[]{1,2,3}),
                "test", null, null, null);
    }

    @Test void committedUploadSurvivesDetailReadFailure() {
        prepareOrdinaryUpload();
        when(images.selectAccessibleById(1L, "test-user")).thenThrow(new IllegalStateException("read failed"));
        assertThatThrownBy(this::uploadOrdinary).hasMessageContaining("read failed");
        verify(storage, never()).deleteObject(anyString());
    }

    @Test void committedUploadSurvivesThumbnailSchedulingFailure() {
        prepareOrdinaryUpload();
        var returned = new RsImage(); returned.setId(1L); returned.setOwnerId("test-user");
        when(images.selectAccessibleById(1L, "test-user")).thenReturn(returned);
        doThrow(new IllegalStateException("executor unavailable")).when(thumbnails).generateAsync(1L);
        assertThatCode(this::uploadOrdinary).doesNotThrowAnyException();
        verify(storage, never()).deleteObject(anyString());
    }

    @Test void unknownCommitMustNotDeleteObject() {
        prepareOrdinaryUpload();
        transactions.unknownCommit = true;
        assertThatThrownBy(this::uploadOrdinary).hasMessageContaining("unknown commit");
        verify(storage, never()).deleteObject(anyString());
    }

    @Test void invalidPackageDoesNotReachStorageOrDatabase() {
        when(parser.parse(any(Path.class))).thenReturn(new GeoTiffMetadataVO());
        var file = new MockMultipartFile("file", "test.tif", "image/tiff", new byte[]{1,2,3});
        var manifest = new MockMultipartFile("manifest", "test.json", "application/json", "{}".getBytes());
        assertThatThrownBy(() -> service.uploadStandardized(file, manifest, "test"))
                .hasMessageContaining("契约版本");
        verifyNoInteractions(storage);
        verify(images, never()).insert(any());
        // 失败释放上传信号量，再次调用仍应到达校验而非并发拒绝。
        assertThatThrownBy(() -> service.uploadStandardized(file, manifest, "test"))
                .hasMessageContaining("契约版本");
    }

    @Test void metadataRegistrationDoesNotGrantAdmission() {
        when(user.getCurrentUserId()).thenReturn("test-user");
        var dto = new RsImageCreateDTO();
        dto.setImageCode("test");
        when(images.insert(any())).thenAnswer(call -> {
            RsImage image = call.getArgument(0); image.setId(1L); return 1;
        });
        var returned = new RsImage(); returned.setId(1L); returned.setOwnerId("test-user");
        when(images.selectAccessibleById(1L, "test-user")).thenReturn(returned);
        service.create(dto);
        var captor = ArgumentCaptor.forClass(RsImage.class);
        verify(images).insert(captor.capture());
        assertThat(captor.getValue().getMetadataJson()).doesNotContain("admission");
    }
}
