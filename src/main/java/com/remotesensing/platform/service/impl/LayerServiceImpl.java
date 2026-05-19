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

@Service
public class LayerServiceImpl implements LayerService {

    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String PROXY_WMS_PATH = "/api/layers/%d/wms";
    private static final String PROXY_WCS_PATH = "/api/layers/%d/wcs";
    private static final Set<String> WMS_FORCED_KEYS = Set.of("service", "layers");
    private static final Set<String> WCS_FORCED_KEYS = Set.of("service", "coverageid");
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
    public ResponseEntity<byte[]> proxyWms(Long id, MultiValueMap<String, String> queryParams) {
        LayerVO layer = getAccessiblePublishedLayer(id);
        rejectCapabilitiesRequest(queryParams);
        MultiValueMap<String, String> forwardedParams = copyWithoutKeys(queryParams, WMS_FORCED_KEYS);
        forwardedParams.set("service", "WMS");
        forwardedParams.set("layers", qualifiedLayerName(layer));
        return geoServerProxyClient.proxy("/wms", forwardedParams);
    }

    @Override
    public ResponseEntity<byte[]> proxyWcs(Long id, MultiValueMap<String, String> queryParams) {
        LayerVO layer = getAccessiblePublishedLayer(id);
        rejectCapabilitiesRequest(queryParams);
        MultiValueMap<String, String> forwardedParams = copyWithoutKeys(queryParams, WCS_FORCED_KEYS);
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

    private MultiValueMap<String, String> copyWithoutKeys(MultiValueMap<String, String> source, Set<String> excludedKeys) {
        MultiValueMap<String, String> target = new LinkedMultiValueMap<>();
        if (source == null) {
            return target;
        }
        source.forEach((key, values) -> {
            if (!excludedKeys.contains(key.toLowerCase(Locale.ROOT))) {
                target.put(key, values);
            }
        });
        return target;
    }

    private void rejectCapabilitiesRequest(MultiValueMap<String, String> queryParams) {
        String request = firstValueIgnoreCase(queryParams, REQUEST_PARAM);
        if ("GetCapabilities".equalsIgnoreCase(request)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "图层代理暂不支持 GetCapabilities 请求");
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
