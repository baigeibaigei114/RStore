package com.remotesensing.platform.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.common.enums.ImageStatus;
import com.remotesensing.platform.common.enums.ThumbnailStatus;
import com.remotesensing.platform.common.enums.Visibility;
import com.remotesensing.platform.config.properties.UploadProperties;
import com.remotesensing.platform.dto.RsImageSearchDTO;
import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.RsImageMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import com.remotesensing.platform.service.GeoTiffMetadataService;
import com.remotesensing.platform.service.MinioService;
import com.remotesensing.platform.service.ThumbnailAsyncService;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import com.remotesensing.platform.vo.GeoTiffMetadataVO;
import com.remotesensing.platform.vo.MinioUploadVO;
import com.remotesensing.platform.vo.RsImageListVO;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class RsImageServiceImplPermissionTest {

    @Mock
    private RsImageMapper imageMapper;

    @Mock
    private RsTaskMapper taskMapper;

    @Mock
    private MinioService minioService;

    @Mock
    private GeoTiffMetadataService geoTiffMetadataService;

    @Mock
    private ThumbnailAsyncService thumbnailAsyncService;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private CurrentUserContext currentUserContext;

    private RsImageServiceImpl service;

    @BeforeEach
    void setUp() {
        UploadProperties uploadProperties = new UploadProperties();
        uploadProperties.setMaxConcurrent(1);
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        service = new RsImageServiceImpl(
                imageMapper,
                taskMapper,
                minioService,
                geoTiffMetadataService,
                thumbnailAsyncService,
                new ObjectMapper(),
                uploadProperties,
                transactionManager,
                currentUserContext
        );
    }

    @Test
    @DisplayName("上传影像默认归属当前用户且可见性为 PRIVATE")
    void uploadShouldCreatePrivateImageForCurrentUser() {
        MockMultipartFile file = new MockMultipartFile("file", "demo.tif", "image/tiff", new byte[]{1, 2, 3});
        when(currentUserContext.getCurrentUserId()).thenReturn("user-a");
        when(geoTiffMetadataService.parse(any(Path.class))).thenReturn(metadata());
        when(minioService.uploadGeoTiff(any(Path.class), org.mockito.ArgumentMatchers.eq("demo.tif"), org.mockito.ArgumentMatchers.eq("image/tiff")))
                .thenReturn(new MinioUploadVO("bucket", "raw/2026/05/demo.tif", 3L, "image/tiff"));
        when(imageMapper.insert(any(RsImage.class))).thenAnswer(invocation -> {
            RsImage image = invocation.getArgument(0);
            image.setId(1L);
            return 1;
        });
        when(imageMapper.selectAccessibleById(1L, "user-a")).thenAnswer(invocation -> {
            RsImage image = new RsImage();
            image.setId(1L);
            image.setOwnerId("user-a");
            image.setVisibility(Visibility.PRIVATE.dbValue());
            image.setStatus(ImageStatus.READY.dbValue());
            return image;
        });

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.upload(file, "测试影像", null, null, null);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        ArgumentCaptor<RsImage> captor = ArgumentCaptor.forClass(RsImage.class);
        verify(imageMapper).insert(captor.capture());
        assertThat(captor.getValue().getOwnerId()).isEqualTo("user-a");
        assertThat(captor.getValue().getVisibility()).isEqualTo(Visibility.PRIVATE.dbValue());
        assertThat(captor.getValue().getThumbnailStatus()).isEqualTo(ThumbnailStatus.PENDING.dbValue());
    }

    @Test
    @DisplayName("用户 B 搜索不到用户 A 的 PRIVATE 影像")
    void searchShouldHideOtherUsersPrivateImage() {
        RsImageSearchDTO query = new RsImageSearchDTO();
        when(currentUserContext.getCurrentUserId()).thenReturn("user-b");
        when(imageMapper.searchPage(query, 0, 10, "user-b")).thenReturn(List.of());
        when(imageMapper.countSearch(query, "user-b")).thenReturn(0L);

        PageResult<RsImageListVO> result = service.search(query, 1, 10);

        assertThat(result.getRecords()).isEmpty();
        verify(imageMapper).searchPage(query, 0, 10, "user-b");
    }

    @Test
    @DisplayName("影像改为 PUBLIC 后其他用户可搜索到")
    void searchShouldReturnPublicImageForOtherUser() {
        RsImageSearchDTO query = new RsImageSearchDTO();
        RsImage publicImage = new RsImage();
        publicImage.setId(1L);
        publicImage.setOwnerId("user-a");
        publicImage.setVisibility(Visibility.PUBLIC.dbValue());
        publicImage.setStatus(ImageStatus.READY.dbValue());

        when(currentUserContext.getCurrentUserId()).thenReturn("user-b");
        when(imageMapper.searchPage(query, 0, 10, "user-b")).thenReturn(List.of(publicImage));
        when(imageMapper.countSearch(query, "user-b")).thenReturn(1L);

        PageResult<RsImageListVO> result = service.search(query, 1, 10);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).getVisibility()).isEqualTo(Visibility.PUBLIC.dbValue());
    }

    @Test
    @DisplayName("行政区影像检索继续使用当前用户权限过滤")
    void searchByRegionShouldKeepCurrentUserPermissionFilter() {
        RsImageSearchDTO query = new RsImageSearchDTO();
        query.setRegionId(310101L);
        when(currentUserContext.getCurrentUserId()).thenReturn("user-b");
        when(imageMapper.searchByRegionPage(query, 0, 10, "user-b")).thenReturn(List.of());
        when(imageMapper.countSearchByRegion(query, "user-b")).thenReturn(0L);

        PageResult<RsImageListVO> result = service.searchByRegion(query, 1, 10);

        assertThat(result.getRecords()).isEmpty();
        verify(imageMapper).searchByRegionPage(query, 0, 10, "user-b");
        verify(imageMapper).countSearchByRegion(query, "user-b");
    }

    @Test
    @DisplayName("只有 owner 可以修改影像可见性")
    void updateVisibilityShouldUseOwnerCondition() {
        RsImage image = new RsImage();
        image.setId(1L);
        image.setOwnerId("user-a");
        image.setVisibility(Visibility.PUBLIC.dbValue());

        when(currentUserContext.getCurrentUserId()).thenReturn("user-a");
        when(imageMapper.updateVisibility(1L, "user-a", Visibility.PUBLIC.dbValue())).thenReturn(1);
        when(imageMapper.selectAccessibleById(1L, "user-a")).thenReturn(image);

        service.updateVisibility(1L, "PUBLIC");

        verify(imageMapper).updateVisibility(1L, "user-a", Visibility.PUBLIC.dbValue());
    }

    @Test
    @DisplayName("用户 A 可以获取自己 PRIVATE 影像的原始下载 URL")
    void getDownloadUrlShouldAllowOwnerPrivateImage() {
        RsImage image = image(1L, "user-a", Visibility.PRIVATE.dbValue(), "raw/2026/05/a.tif", null);
        when(currentUserContext.getCurrentUserId()).thenReturn("user-a");
        when(imageMapper.selectAccessibleById(1L, "user-a")).thenReturn(image);
        when(minioService.generatePresignedUrl("raw/2026/05/a.tif"))
                .thenReturn(new FilePresignedUrlVO("raw/2026/05/a.tif", "http://minio/a", 1800));

        FilePresignedUrlVO result = service.getDownloadUrl(1L);

        assertThat(result.getObjectKey()).isEqualTo("raw/2026/05/a.tif");
        verify(minioService).generatePresignedUrl("raw/2026/05/a.tif");
    }

    @Test
    @DisplayName("用户 B 不能获取用户 A PRIVATE 影像的原始下载 URL")
    void getDownloadUrlShouldRejectOtherUsersPrivateImage() {
        when(currentUserContext.getCurrentUserId()).thenReturn("user-b");
        when(imageMapper.selectAccessibleById(1L, "user-b")).thenReturn(null);

        assertThatThrownBy(() -> service.getDownloadUrl(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("影像记录不存在");
    }

    @Test
    @DisplayName("用户 B 可以获取用户 A PUBLIC 影像的原始下载 URL")
    void getDownloadUrlShouldAllowPublicImage() {
        RsImage image = image(1L, "user-a", Visibility.PUBLIC.dbValue(), "raw/2026/05/public.tif", null);
        when(currentUserContext.getCurrentUserId()).thenReturn("user-b");
        when(imageMapper.selectAccessibleById(1L, "user-b")).thenReturn(image);
        when(minioService.generatePresignedUrl("raw/2026/05/public.tif"))
                .thenReturn(new FilePresignedUrlVO("raw/2026/05/public.tif", "http://minio/public", 1800));

        FilePresignedUrlVO result = service.getDownloadUrl(1L);

        assertThat(result.getUrl()).isEqualTo("http://minio/public");
    }

    @Test
    @DisplayName("缩略图不存在时返回明确业务异常")
    void getThumbnailUrlShouldFailWhenThumbnailMissing() {
        RsImage image = image(1L, "user-a", Visibility.PRIVATE.dbValue(), "raw/2026/05/a.tif", null);
        when(currentUserContext.getCurrentUserId()).thenReturn("user-a");
        when(imageMapper.selectAccessibleById(1L, "user-a")).thenReturn(image);

        assertThatThrownBy(() -> service.getThumbnailUrl(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缩略图尚未生成");
    }

    private RsImage image(Long id, String ownerId, String visibility, String objectKey, String thumbnailObjectKey) {
        RsImage image = new RsImage();
        image.setId(id);
        image.setOwnerId(ownerId);
        image.setVisibility(visibility);
        image.setObjectKey(objectKey);
        image.setThumbnailObjectKey(thumbnailObjectKey);
        image.setStatus(ImageStatus.READY.dbValue());
        return image;
    }

    private GeoTiffMetadataVO metadata() {
        GeoTiffMetadataVO metadata = new GeoTiffMetadataVO();
        metadata.setWidth(10);
        metadata.setHeight(10);
        metadata.setBandCount(1);
        metadata.setCrs("EPSG:4326");
        GeoTiffMetadataVO.Resolution resolution = new GeoTiffMetadataVO.Resolution();
        resolution.setX(BigDecimal.ONE);
        resolution.setY(BigDecimal.ONE);
        metadata.setResolution(resolution);
        return metadata;
    }
}
