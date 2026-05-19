package com.remotesensing.platform.service;

import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

public interface GeoServerProxyClient {

    ResponseEntity<StreamingResponseBody> proxy(String path, MultiValueMap<String, String> queryParams);
}
