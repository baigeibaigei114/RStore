package com.remotesensing.platform.ingestion.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import static com.remotesensing.platform.ingestion.api.IngestionProblem.require;
import static com.remotesensing.platform.ingestion.workflow.IngestionJson.*;

/** Validates the bounded Worker descriptor; actual JP2 decoding remains deterministic Worker code. */
public final class DescriptorValidation {
    private DescriptorValidation() {}
    public static void check(JsonNode d, String profile) {
        fields(d,"schemaVersion","sourceId","captureTime","granule","processingBaseline","parserVersion","profileVersion",
                "productMetadataSha256","grid","calibration","rawCalibration","scl","previewGrid");
        require(d.path("schemaVersion").isIntegralNumber() && d.path("schemaVersion").asInt()==1,400,"INVALID_REQUEST");
        require(profile.equals(d.path("profileVersion").asText()) && "safe-l2a-probe-v1".equals(d.path("parserVersion").asText())
                && "05.13".equals(d.path("processingBaseline").asText()),400,"UNSUPPORTED_PRODUCT");
        require(d.path("sourceId").asText().matches("S2[ABC]_MSIL2A_\\d{8}T\\d{6}_N0513_R\\d{3}_T\\w{5}_\\d{8}T\\d{6}\\.SAFE"),400,"UNSUPPORTED_PRODUCT");
        require(d.path("granule").asText().matches("[A-Za-z0-9_-]{1,200}") && d.path("productMetadataSha256").asText().matches("[0-9a-f]{64}"),400,"INVALID_REQUEST");
        require(d.path("captureTime").isTextual() && d.path("captureTime").asText().length()<=50,400,"INVALID_REQUEST");
        OffsetDateTime.parse(d.path("captureTime").asText());
        JsonNode grid=d.path("grid"), scl=d.path("scl"), preview=d.path("previewGrid");
        fields(grid,"crs","width","height","transform"); fields(scl,"crs","width","height","transform","dtype","validClasses");
        fields(preview,"crs","width","height","transform","bounds");
        require(grid.path("crs").asText().matches("EPSG:32[67](0[1-9]|[1-5][0-9]|60)"),400,"INVALID_GRID");
        int width=(int)integer(grid,"width",2,20000), height=(int)integer(grid,"height",2,20000);
        require(width%2==0 && height%2==0,400,"INVALID_GRID");
        vector(grid.path("transform"),6); JsonNode transform=grid.path("transform");
        require(transform.get(0).doubleValue()==10 && transform.get(1).doubleValue()==0 && transform.get(3).doubleValue()==0 && transform.get(4).doubleValue()==-10,400,"INVALID_GRID");
        require("uint8".equals(scl.path("dtype").asText()) && grid.path("crs").equals(scl.path("crs"))
                && integer(scl,"width",1,10000)*2==width && integer(scl,"height",1,10000)*2==height
                && canonical(scl.path("validClasses")).equals("[4,5,6,7]"),400,"INVALID_GRID");
        vector(scl.path("transform"),6);
        for(int i=0;i<6;i++) near(scl.path("transform").get(i).doubleValue(),transform.get(i).doubleValue()*(i==0||i==1||i==3||i==4?2:1),1e-9);
        int pw=(int)integer(preview,"width",1,512), ph=(int)integer(preview,"height",1,512);
        require(grid.path("crs").equals(preview.path("crs")),400,"INVALID_GRID");
        vector(preview.path("transform"),6); vector(preview.path("bounds"),4);
        double[] expected={10.0*width/pw,0,transform.get(2).doubleValue(),0,-10.0*height/ph,transform.get(5).doubleValue()};
        for(int i=0;i<6;i++) near(preview.path("transform").get(i).doubleValue(),expected[i],1e-8);
        double[] bounds={expected[2],expected[5]-10*height,expected[2]+10*width,expected[5]};
        for(int i=0;i<4;i++) near(preview.path("bounds").get(i).doubleValue(),bounds[i],1e-7);
        JsonNode raw=d.path("rawCalibration"), calibration=d.path("calibration");
        fields(raw,"quantificationValue","boaAddOffsets"); fields(raw.path("boaAddOffsets"),"B02","B03","B04","B08");
        fields(calibration,"blue","green","red","nir");
        require(raw.path("quantificationValue").isNumber() && Double.isFinite(raw.path("quantificationValue").doubleValue()) && raw.path("quantificationValue").doubleValue()>0,400,"INVALID_REQUEST");
        double q=raw.path("quantificationValue").doubleValue(); String[] bands={"B02","B03","B04","B08"}; int index=0;
        for(String name:List.of("blue","green","red","nir")) {
            JsonNode applied=calibration.path(name); fields(applied,"scale","offset");
            require(applied.path("scale").isNumber() && applied.path("offset").isNumber() && raw.path("boaAddOffsets").path(bands[index]).isNumber(),400,"INVALID_REQUEST");
            near(applied.path("scale").doubleValue(),1/q,1e-15);
            near(applied.path("offset").doubleValue(),raw.path("boaAddOffsets").path(bands[index++]).doubleValue()/q,1e-12);
        }
    }
    private static void vector(JsonNode values,int size) {
        require(values.isArray() && values.size()==size,400,"INVALID_GRID");
        values.forEach(value -> require(value.isNumber() && Double.isFinite(value.doubleValue()),400,"INVALID_GRID"));
    }
    private static void near(double actual,double expected,double tolerance) {
        require(Double.isFinite(actual) && Double.isFinite(expected) && Math.abs(actual-expected)<=tolerance,400,"INVALID_GRID");
    }
}
