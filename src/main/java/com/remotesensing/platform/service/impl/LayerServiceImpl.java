package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.dto.LayerSearchDTO;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.LayerMapper;
import com.remotesensing.platform.service.GeoServerProxyClient;
import com.remotesensing.platform.service.LayerService;
import com.remotesensing.platform.vo.LayerVO;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Service
public class LayerServiceImpl implements LayerService {

    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String PROXY_WMS_PATH = "/api/layers/%d/wms";
    private static final String PROXY_WCS_PATH = "/api/layers/%d/wcs";
    private static final int MAX_RENDER_SIZE = 4096;
    private static final Set<String> WMS_FORCED_KEYS = Set.of("service", "layers");
    private static final Set<String> WCS_FORCED_KEYS = Set.of("service", "coverageid");
    private static final Set<String> WMS_ALLOWED_KEYS = Set.of(
            "request", "version", "bbox", "width", "height", "srs", "crs",
            "format", "transparent", "styles", "exceptions", "tiled"
    );
    private static final Set<String> WCS_ALLOWED_KEYS = Set.of(
            "request", "version", "format", "bbox", "subset", "width", "height",
            "crs", "srs", "outputcrs"
    );
    private static final Set<String> WMS_ALLOWED_FORMATS = Set.of("image/png", "image/jpeg", "image/jpg");
    private static final Set<String> WCS_ALLOWED_FORMATS = Set.of("image/tiff", "image/geotiff", "application/geotiff", "geotiff");
    private static final String REQUEST_PARAM = "request";

    private final LayerMapper layerMapper;
    private final CurrentUserContext currentUserContext;
    private final GeoServerProxyClient geoServerProxyClient;

    public LayerServiceImpl(LayerMapper layerMapper,
                            CurrentUserContext currentUserContext,
                            GeoServerProxyClient geoServerProxyClient) {
        this.layerMapper = layerMapper;
        this.currentUserContext = currentUserContext;
        this.geoServerProxyClient = geoServerProxyClient;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<LayerVO> page(LayerSearchDTO query, Integer pageNum, Integer pageSize) {
        LayerSearchDTO normalizedQuery = normalizeQuery(query);
        int currentPageNum = normalizePageNum(pageNum);
        int currentPageSize = normalizePageSize(pageSize);
        int offset = (currentPageNum - 1) * currentPageSize;
        String currentUserId = currentUserContext.getCurrentUserId();

        List<LayerVO> records = layerMapper.selectPage(normalizedQuery, offset, currentPageSize, currentUserId).stream()
                .map(this::fillDerivedFields)
                .toList();
        long total = layerMapper.count(normalizedQuery, currentUserId);
        return new PageResult<>(records, total, currentPageNum, currentPageSize);
    }

    @Override
    public ResponseEntity<StreamingResponseBody> proxyWms(Long id, MultiValueMap<String, String> queryParams) {
        LayerVO layer = getAccessiblePublishedLayer(id);
        validateRequest(queryParams, "GetMap", "WMS 代理只支持 GetMap 请求");
        validateRenderSize(queryParams);
        validateFormat(queryParams, WMS_ALLOWED_FORMATS, "WMS format 仅支持 image/png 或 image/jpeg");
        MultiValueMap<String, String> forwardedParams = copyAllowedWithoutKeys(queryParams, WMS_ALLOWED_KEYS, WMS_FORCED_KEYS);
        forwardedParams.set("service", "WMS");
        forwardedParams.set("layers", qualifiedLayerName(layer));
        return geoServerProxyClient.proxy("/wms", forwardedParams);
    }

    @Override
    public ResponseEntity<StreamingResponseBody> proxyWcs(Long id, MultiValueMap<String, String> queryParams) {
        LayerVO layer = getAccessiblePublishedLayer(id);
        validateRequest(queryParams, "GetCoverage", "WCS 代理只支持 GetCoverage 请求");
        validateRenderSize(queryParams);
        validateFormat(queryParams, WCS_ALLOWED_FORMATS, "WCS format 仅支持 GeoTIFF 输出");
        MultiValueMap<String, String> forwardedParams = copyAllowedWithoutKeys(queryParams, WCS_ALLOWED_KEYS, WCS_FORCED_KEYS);
        forwardedParams.set("service", "WCS");
        forwardedParams.set("coverageId", qualifiedLayerName(layer));
        return geoServerProxyClient.proxy("/wcs", forwardedParams);
    }

    private LayerVO fillDerivedFields(LayerVO layer) {
        layer.setQualifiedLayerName(qualifiedLayerName(layer));
        layer.setProxyWmsUrl(PROXY_WMS_PATH.formatted(layer.getId()));
        layer.setProxyWcsUrl(PROXY_WCS_PATH.formatted(layer.getId()));
        return layer;
    }

    private LayerVO getAccessiblePublishedLayer(Long id) {
        LayerVO layer = layerMapper.selectAccessiblePublishedById(id, currentUserContext.getCurrentUserId());
        if (layer == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "图层不存在、未发布或无权访问");
        }
        return fillDerivedFields(layer);
    }

    private String qualifiedLayerName(LayerVO layer) {
        return layer.getWorkspace() + ":" + layer.getLayerName();
    }

    private MultiValueMap<String, String> copyAllowedWithoutKeys(MultiValueMap<String, String> source,
                                                                 Set<String> allowedKeys,
                                                                 Set<String> excludedKeys) {
        MultiValueMap<String, String> target = new LinkedMultiValueMap<>();
        if (source == null) {
            return target;
        }
        source.forEach((key, values) -> {
            String normalizedKey = key.toLowerCase(Locale.ROOT);
            if (allowedKeys.contains(normalizedKey) && !excludedKeys.contains(normalizedKey)) {
                target.put(key, values);
            }
        });
        return target;
    }

    private void validateRequest(MultiValueMap<String, String> queryParams, String expectedRequest, String errorMessage) {
        String request = firstValueIgnoreCase(queryParams, REQUEST_PARAM);
        if (!expectedRequest.equalsIgnoreCase(request)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), errorMessage);
        }
    }

    private void validateRenderSize(MultiValueMap<String, String> queryParams) {
        validatePositiveIntMax(firstValueIgnoreCase(queryParams, "width"), "width");
        validatePositiveIntMax(firstValueIgnoreCase(queryParams, "height"), "height");
    }

    private void validatePositiveIntMax(String value, String paramName) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            int size = Integer.parseInt(value.trim());
            if (size < 1 || size > MAX_RENDER_SIZE) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), paramName + " 必须在 1 到 " + MAX_RENDER_SIZE + " 之间");
            }
        } catch (NumberFormatException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), paramName + " 必须是整数");
        }
    }

    private void validateFormat(MultiValueMap<String, String> queryParams, Set<String> allowedFormats, String errorMessage) {
        String format = firstValueIgnoreCase(queryParams, "format");
        if (format == null || format.isBlank()) {
            return;
        }
        if (!allowedFormats.contains(format.trim().toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), errorMessage);
        }
    }

    private String firstValueIgnoreCase(MultiValueMap<String, String> queryParams, String key) {
        if (queryParams == null) {
            return null;
        }
        return queryParams.entrySet().stream()
                .filter(entry -> key.equalsIgnoreCase(entry.getKey()))
                .findFirst()
                .map(entry -> entry.getValue().isEmpty() ? null : entry.getValue().get(0))
                .orElse(null);
    }

    private LayerSearchDTO normalizeQuery(LayerSearchDTO query) {
        LayerSearchDTO normalized = query == null ? new LayerSearchDTO() : query;
        normalized.setTaskType(trimToNull(normalized.getTaskType()));
        normalized.setKeyword(trimToNull(normalized.getKeyword()));
        return normalized;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private int normalizePageNum(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? DEFAULT_PAGE_NUM : pageNum;
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
