package com.remotesensing.platform.service;

import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.dto.LayerSearchDTO;
import com.remotesensing.platform.vo.LayerVO;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;

public interface LayerService {

    PageResult<LayerVO> page(LayerSearchDTO query, Integer pageNum, Integer pageSize);

    ResponseEntity<byte[]> proxyWms(Long id, MultiValueMap<String, String> queryParams);

    ResponseEntity<byte[]> proxyWcs(Long id, MultiValueMap<String, String> queryParams);
}
