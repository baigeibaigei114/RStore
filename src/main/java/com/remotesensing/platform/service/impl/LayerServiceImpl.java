package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.properties.GeoServerProperties;
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

/**
 * 图层服务实现类，负责图层的分页查询以及 WMS/WCS OGC 代理请求的转发。
 *
 * <p>核心职责：
 * <ol>
 *   <li>根据搜索条件分页查询当前用户可访问的已发布图层。</li>
 *   <li>对 WMS / WCS 代理请求进行参数校验（请求类型、渲染尺寸、输出格式），
 *       剥离客户端传入的危险参数后将合法参数转发至 GeoServer。</li>
 * </ol>
 *
 * <p>设计要点：
 * <ul>
 *   <li>通过白名单机制（{@link #WMS_ALLOWED_KEYS}、{@link #WCS_ALLOWED_KEYS}）
 *       限制可透传的查询参数，防止参数注入。</li>
 *   <li>强制覆盖 {@code service}、{@code layers} 等服务端管控参数，
 *       避免客户端篡改后端标识。</li>
 *   <li>WMS / WCS 的渲染尺寸上限分别通过配置和硬编码双重控制。</li>
 * </ul>
 *
 * @author remote-sensing-platform
 */

@Service
public class LayerServiceImpl implements LayerService {

    /** 默认分页页码 */
    private static final int DEFAULT_PAGE_NUM = 1;
    /** 默认每页记录数 */
    private static final int DEFAULT_PAGE_SIZE = 10;
    /** 每页最大记录数，防止客户端一次查询过大 */
    private static final int MAX_PAGE_SIZE = 100;
    /** WMS 代理 URL 模板，用于填充派生字段 */
    private static final String PROXY_WMS_PATH = "/api/layers/%d/wms";
    /** WCS 代理 URL 模板，用于填充派生字段 */
    private static final String PROXY_WCS_PATH = "/api/layers/%d/wcs";
    /** WMS 代理中强制覆盖的请求参数键 —— 这些键由后端管控，客户端不允许自定义 */
    private static final Set<String> WMS_FORCED_KEYS = Set.of("service", "layers", "query_layers");
    /** WCS 代理中强制覆盖的请求参数键 */
    private static final Set<String> WCS_FORCED_KEYS = Set.of("service", "coverageid");
    /** WMS 代理允许透传的查询参数白名单 */
    private static final Set<String> WMS_ALLOWED_KEYS = Set.of(
            "request", "version", "bbox", "width", "height", "srs", "crs",
            "format", "transparent", "styles", "exceptions", "tiled",
            "info_format", "feature_count", "i", "j", "x", "y", "query_layers"
    );
    /** WCS 代理允许透传的查询参数白名单 */
    private static final Set<String> WCS_ALLOWED_KEYS = Set.of(
            "request", "version", "format", "bbox", "subset", "width", "height",
            "crs", "srs", "outputcrs"
    );
    /** WMS 允许的输出格式 */
    private static final Set<String> WMS_ALLOWED_FORMATS = Set.of("image/png", "image/jpeg", "image/jpg");
    /** WCS 允许的输出格式 —— 只允许 GeoTIFF 系列格式 */
    private static final Set<String> WCS_ALLOWED_FORMATS = Set.of("image/tiff", "image/geotiff", "application/geotiff", "geotiff");
    /** 请求类型参数的参数名常量 */
    private static final String REQUEST_PARAM = "request";
    /** WMS 允许的请求类型 —— 只支持 GetMap（获取地图图片）和 GetFeatureInfo（获取要素信息） */
    private static final Set<String> WMS_ALLOWED_REQUESTS = Set.of("GetMap", "GetFeatureInfo");

    private final LayerMapper layerMapper;
    private final CurrentUserContext currentUserContext;
    private final GeoServerProxyClient geoServerProxyClient;
    private final GeoServerProperties geoServerProperties;

    public LayerServiceImpl(LayerMapper layerMapper,
                            CurrentUserContext currentUserContext,
                            GeoServerProxyClient geoServerProxyClient,
                            GeoServerProperties geoServerProperties) {
        this.layerMapper = layerMapper;
        this.currentUserContext = currentUserContext;
        this.geoServerProxyClient = geoServerProxyClient;
        this.geoServerProperties = geoServerProperties;
    }

    /**
     * 分页查询当前用户可访问的已发布图层。
     *
     * <p>查询前对搜索条件做空值归一化（{@link #normalizeQuery(LayerSearchDTO)}），
     * 对分页参数做边界保护（{@link #normalizePageNum(Integer)} / {@link #normalizePageSize(Integer)}）。
     * 查询结果中填充派生字段（代理 URL、限定图层名）后返回。
     *
     * @param query    搜索条件（关键词、任务类型等）
     * @param pageNum  页码（从 1 开始），为空或小于 1 时默认为 1
     * @param pageSize 每页大小，为空或小于 1 时默认为 10，超过 100 时截断为 100
     * @return 分页结果，包含当前页记录、总数、页码、页大小
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult<LayerVO> page(LayerSearchDTO query, Integer pageNum, Integer pageSize) {
        LayerSearchDTO normalizedQuery = normalizeQuery(query);
        int currentPageNum = normalizePageNum(pageNum);
        int currentPageSize = normalizePageSize(pageSize);
        // 计算数据库分页偏移量：页码从 1 开始，offset = (pageNum - 1) * pageSize
        int offset = (currentPageNum - 1) * currentPageSize;
        String currentUserId = currentUserContext.getCurrentUserId();

        // 查询记录并填充派生字段（代理 URL、限定图层名等），返回不可变列表
        List<LayerVO> records = layerMapper.selectPage(normalizedQuery, offset, currentPageSize, currentUserId).stream()
                .map(this::fillDerivedFields)
                .toList();
        long total = layerMapper.count(normalizedQuery, currentUserId);
        return new PageResult<>(records, total, currentPageNum, currentPageSize);
    }

    /**
     * 代理 WMS 请求，将已验证的请求参数转发至 GeoServer /wms 端点。
     *
     * <p>流程说明：
     * <ol>
     *   <li>校验图层可访问性及已发布状态。</li>
     *   <li>校验 WMS 请求类型（只允许 GetMap / GetFeatureInfo）。</li>
     *   <li>校验渲染尺寸是否超过 WMS 配置上限。</li>
     *   <li>校验输出格式是否为 image/png 或 image/jpeg。</li>
     *   <li>从客户端参数中白名单拷贝允许透传的参数，同时排除强制管控参数。</li>
     *   <li>添加强制参数值（service=WMS、layers、query_layers），并应用默认值（format、tiled）。</li>
     * </ol>
     *
     * @param id         图层 ID
     * @param queryParams 客户端传入的原始 WMS 查询参数
     * @return 包含 GeoServer 原始响应流和响应头的 ResponseEntity
     */
    @Override
    public ResponseEntity<StreamingResponseBody> proxyWms(Long id, MultiValueMap<String, String> queryParams) {
        // Step 1: 校验图层存在、已发布且当前用户有权访问
        LayerVO layer = getAccessiblePublishedLayer(id);
        // Step 2: 校验 WMS 请求类型是否在允许范围内（GetMap / GetFeatureInfo）
        validateWmsRequest(queryParams);
        // Step 3: 校验渲染尺寸（宽/高）不超 WMS 配置上限
        validateWmsRenderSize(queryParams);
        // Step 4: 校验输出格式是否受支持
        validateFormat(queryParams, WMS_ALLOWED_FORMATS, "WMS format 仅支持 image/png 或 image/jpeg");
        // Step 5: 白名单拷贝允许透传的参数，排除 service/layers/query_layers 等后端管控键
        MultiValueMap<String, String> forwardedParams = copyAllowedWithoutKeys(queryParams, WMS_ALLOWED_KEYS, WMS_FORCED_KEYS);
        // Step 6: 添加强制管控参数 —— 防止客户端伪造 service 或 layers
        forwardedParams.set("service", "WMS");
        forwardedParams.set("layers", qualifiedLayerName(layer));
        // GetFeatureInfo 需要额外指定 query_layers 参数
        if ("GetFeatureInfo".equalsIgnoreCase(firstValueIgnoreCase(queryParams, REQUEST_PARAM))) {
            forwardedParams.set("query_layers", qualifiedLayerName(layer));
        }
        // Step 7: 对未传入的可选参数应用默认值（format 默认 image/png，tiled 按配置决定）
        applyWmsDefaults(queryParams, forwardedParams);
        // Step 8: 转发至 GeoServer
        return geoServerProxyClient.proxy("/wms", forwardedParams);
    }

    /**
     * 代理 WCS 请求，将已验证的请求参数转发至 GeoServer /wcs 端点。
     *
     * <p>与 WMS 代理类似，但 WCS 只支持 GetCoverage 请求且输出格式只允许 GeoTIFF。
     * 渲染尺寸上限使用统一的 4096 硬编码限制（而非 WMS 的可配置限制），
     * 这是因为 WCS 下载场景中过大尺寸对带宽和内存消耗更敏感。
     *
     * @param id         图层 ID
     * @param queryParams 客户端传入的原始 WCS 查询参数
     * @return 包含 GeoServer 原始响应流和响应头的 ResponseEntity
     */
    @Override
    public ResponseEntity<StreamingResponseBody> proxyWcs(Long id, MultiValueMap<String, String> queryParams) {
        // Step 1: 校验图层存在、已发布且当前用户有权访问
        LayerVO layer = getAccessiblePublishedLayer(id);
        // Step 2: 校验请求类型必须为 GetCoverage（WCS 只支持覆盖数据下载）
        validateRequest(queryParams, "GetCoverage", "WCS 代理只支持 GetCoverage 请求");
        // Step 3: 校验渲染尺寸不超硬编码上限（4096），防止内存溢出
        validateRenderSize(queryParams);
        // Step 4: 校验输出格式必须为 GeoTIFF 系列
        validateFormat(queryParams, WCS_ALLOWED_FORMATS, "WCS format 仅支持 GeoTIFF 输出");
        // Step 5: 白名单拷贝允许透传的参数，排除 service/coverageId 等后端管控键
        MultiValueMap<String, String> forwardedParams = copyAllowedWithoutKeys(queryParams, WCS_ALLOWED_KEYS, WCS_FORCED_KEYS);
        // Step 6: 添加强制管控参数
        forwardedParams.set("service", "WCS");
        forwardedParams.set("coverageId", qualifiedLayerName(layer));
        // Step 7: 转发至 GeoServer
        return geoServerProxyClient.proxy("/wcs", forwardedParams);
    }

    /**
     * 填充图层的派生字段：限定图层名（workspace:layerName）、WMS 代理 URL、WCS 代理 URL。
     * <p>这些字段不存储在数据库中，而是根据当前图层信息动态计算，避免冗余存储。
     */
    private LayerVO fillDerivedFields(LayerVO layer) {
        layer.setQualifiedLayerName(qualifiedLayerName(layer));
        layer.setProxyWmsUrl(PROXY_WMS_PATH.formatted(layer.getId()));
        layer.setProxyWcsUrl(PROXY_WCS_PATH.formatted(layer.getId()));
        return layer;
    }

    /**
     * 获取当前用户可访问的已发布图层的详细信息。
     * <p>如果图层不存在、未发布或当前用户无权访问，直接抛出 {@link BusinessException}。
     * 这是代理请求的统一前置校验入口。
     */
    private LayerVO getAccessiblePublishedLayer(Long id) {
        LayerVO layer = layerMapper.selectAccessiblePublishedById(id, currentUserContext.getCurrentUserId());
        if (layer == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "图层不存在、未发布或无权访问");
        }
        return fillDerivedFields(layer);
    }

    /**
     * 构造 GeoServer 限定的完整图层名，格式为 {@code workspace:layerName}。
     * <p>GeoServer REST API 和 OGC 服务中均使用此格式唯一标识一个图层。
     */
    private String qualifiedLayerName(LayerVO layer) {
        return layer.getWorkspace() + ":" + layer.getLayerName();
    }

    /**
     * 从源参数中拷贝允许白名单内的键，同时排除强制管控的键。
     * <p>所有键名在匹配前统一转为小写以忽略大小写差异。
     *
     * @param source       客户端传入的原始参数
     * @param allowedKeys  白名单集合，只有在此集合中的键才允许透传
     * @param excludedKeys 强制管控的键集合，即使在白名单中也要排除
     * @return 过滤后的参数集合
     */
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

    /**
     * 校验请求参数中的 {@code request} 值是否与期望值一致。
     * <p>用于 WCS 场景中确保只允许 GetCoverage 请求。
     *
     * @param queryParams    客户端传入的参数
     * @param expectedRequest 预期的请求类型（如 "GetCoverage"）
     * @param errorMessage    不匹配时抛出的错误信息
     */
    private void validateRequest(MultiValueMap<String, String> queryParams, String expectedRequest, String errorMessage) {
        String request = firstValueIgnoreCase(queryParams, REQUEST_PARAM);
        if (!expectedRequest.equalsIgnoreCase(request)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), errorMessage);
        }
    }

    /**
     * 校验 WMS 请求的 {@code request} 参数是否在允许的请求类型集合中
     * （{@link #WMS_ALLOWED_REQUESTS}）。
     * <p>与 {@link #validateRequest} 不同，这里允许多个请求类型（GetMap / GetFeatureInfo）。
     */
    private void validateWmsRequest(MultiValueMap<String, String> queryParams) {
        String request = firstValueIgnoreCase(queryParams, REQUEST_PARAM);
        if (request == null || WMS_ALLOWED_REQUESTS.stream().noneMatch(allowed -> allowed.equalsIgnoreCase(request))) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "WMS 代理只支持 GetMap 或 GetFeatureInfo 请求");
        }
    }

    /**
     * 校验 WCS 渲染尺寸（width / height）不超硬编码上限 4096。
     * <p>WCS 的尺寸校验使用硬编码常量而非配置，因为在下载场景中超大尺寸的输出
     * 对后端带宽和 GeoServer 内存的消耗风险更高。
     */
    private void validateRenderSize(MultiValueMap<String, String> queryParams) {
        validatePositiveIntMax(firstValueIgnoreCase(queryParams, "width"), "width", 4096);
        validatePositiveIntMax(firstValueIgnoreCase(queryParams, "height"), "height", 4096);
    }

    /**
     * 校验 WMS 渲染尺寸（width / height）不超配置上限。
     * <p>WMS 的尺寸上限通过 {@link GeoServerProperties#getWmsMaxWidth()} /
     * {@link GeoServerProperties#getWmsMaxHeight()} 配置，支持运维灵活调整。
     */
    private void validateWmsRenderSize(MultiValueMap<String, String> queryParams) {
        validatePositiveIntMax(firstValueIgnoreCase(queryParams, "width"), "width", geoServerProperties.getWmsMaxWidth());
        validatePositiveIntMax(firstValueIgnoreCase(queryParams, "height"), "height", geoServerProperties.getWmsMaxHeight());
    }

    /**
     * 校验整数参数在合法范围（1 ~ maxSize）内。
     * <p>参数为空时跳过校验（可选参数），仅对传入值做格式和范围验证。
     *
     * @param value     参数值的字符串形式
     * @param paramName 参数名（用于错误提示）
     * @param maxSize   允许的最大值
     */
    private void validatePositiveIntMax(String value, String paramName, int maxSize) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            int size = Integer.parseInt(value.trim());
            if (size < 1 || size > maxSize) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), paramName + " 必须在 1 到 " + maxSize + " 之间");
            }
        } catch (NumberFormatException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), paramName + " 必须是整数");
        }
    }

    /**
     * 对未设置的 WMS 可选参数应用默认值：
     * <ul>
     *   <li>{@code format} 未传入时默认为 {@code image/png}（浏览器兼容性最好）</li>
     *   <li>{@code tiled} 未传入时按配置决定是否默认启用瓦片模式</li>
     * </ul>
     */
    private void applyWmsDefaults(MultiValueMap<String, String> source, MultiValueMap<String, String> target) {
        if (firstValueIgnoreCase(source, "format") == null) {
            target.set("format", "image/png");
        }
        if (geoServerProperties.isWmsDefaultTiled() && firstValueIgnoreCase(source, "tiled") == null) {
            target.set("tiled", "true");
        }
    }

    /**
     * 校验输出格式是否在允许的格式集合内。
     * <p>如果客户端未传入 {@code format} 参数则跳过校验——后端会填充默认格式。
     *
     * @param queryParams     客户端传入的参数
     * @param allowedFormats  允许的格式集合
     * @param errorMessage    格式不合规时的错误信息
     */
    private void validateFormat(MultiValueMap<String, String> queryParams, Set<String> allowedFormats, String errorMessage) {
        String format = firstValueIgnoreCase(queryParams, "format");
        if (format == null || format.isBlank()) {
            return;
        }
        if (!allowedFormats.contains(format.trim().toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), errorMessage);
        }
    }

    /**
     * 忽略键名大小写获取 {@link MultiValueMap} 中指定键的第一个值。
     * <p>HTTP 查询参数的大小写规范不统一，前端可能传入 {@code BBOX}、{@code Bbox} 等形式，
     * 因此需要忽略大小写进行匹配。
     *
     * @param queryParams 参数集合
     * @param key         要查找的键名
     * @return 匹配到的第一个值，未找到时返回 null
     */
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

    /**
     * 归一化查询条件：将空字符串或纯空白字符串转为 null，
     * 避免 SQL 查询中出现 {@code WHERE keyword = ''} 这种无效条件。
     */
    private LayerSearchDTO normalizeQuery(LayerSearchDTO query) {
        LayerSearchDTO normalized = query == null ? new LayerSearchDTO() : query;
        normalized.setTaskType(trimToNull(normalized.getTaskType()));
        normalized.setKeyword(trimToNull(normalized.getKeyword()));
        return normalized;
    }

    /**
     * 将空字符串或纯空白字符串转为 null。
     * <p>用于查询条件归一化，统一空值与 null 的语义，便于 SQL 中统一使用 {@code IS NULL} 判断。
     */
    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 归一化分页页码：为 null 或小于 1 时返回默认页码 1。
     */
    private int normalizePageNum(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? DEFAULT_PAGE_NUM : pageNum;
    }

    /**
     * 归一化分页大小：为 null 或小于 1 时返回默认值 10，超过上限 100 时截断为 100。
     * <p>防止客户端一次性请求过多数据导致数据库或网络压力过大。
     */
    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
