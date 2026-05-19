package com.remotesensing.platform.service;

import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;

public interface GeoServerProxyClient {

    ResponseEntity<byte[]> proxy(String path, MultiValueMap<String, String> queryParams);
}
