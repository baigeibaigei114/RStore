package com.remotesensing.platform.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "geoserver")
public class GeoServerProperties {

    private String url = "http://localhost:8081/geoserver";
    private String username = "admin";
    private String password = "geoserver";
    private String workspace = "remote_sensing";
    private String sharedDataLocalDir = "data/geoserver-raster";
    private String sharedDataGeoServerDir = "/opt/geoserver/raster-data";
    private int connectTimeoutSeconds = 5;
    private int readTimeoutSeconds = 30;
}
