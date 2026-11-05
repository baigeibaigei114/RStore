package com.remotesensing.platform.ingestion.publishing;

import com.remotesensing.platform.config.properties.IngestionProperties;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import com.remotesensing.platform.ingestion.workflow.*;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import java.util.UUID;
import static com.remotesensing.platform.ingestion.workflow.IngestionJson.JSON;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IngestionLayerServiceTest {
    @Test void onlyBrowserMapParametersAndCaseNormalizedDuplicates() {
        var p=new LinkedMultiValueMap<String,String>();p.add("bbox","0,0,1,1");p.add("srs","EPSG:4326");p.add("width","256");p.add("height","256");
        assertEquals(256,IngestionLayerService.parameters(p,"EPSG:32650").width());
        p.add("WIDTH","1");assertThrows(IngestionProblem.class,()->IngestionLayerService.parameters(p,"EPSG:32650"));p.remove("WIDTH");
        p.add("LAYERS","other");assertThrows(IngestionProblem.class,()->IngestionLayerService.parameters(p,"EPSG:32650"));p.remove("LAYERS");
        p.set("bbox","0,0,NaN,1");assertThrows(IngestionProblem.class,()->IngestionLayerService.parameters(p,"EPSG:32650"));
    }
    @Test void ownerAndAvailableCheckedBeforeAnyUpstreamRead() {
        var db=mock(IngestionStore.class);var jobs=mock(IngestionJobService.class);var client=mock(IngestionGeoServerClient.class);
        var c=new IngestionProperties();c.setEnabled(true);c.setPublicationEnabled(true);
        var service=new IngestionLayerService(db,jobs,c,client);var id=UUID.randomUUID();
        var error=assertThrows(IngestionProblem.class,()->service.readable("other",id));assertEquals(404,error.status);
        when(db.one(anyString(),eq(id),eq("owner"))).thenReturn(JSON.createObjectNode().put("status","PUBLISHING"));
        assertEquals(409,assertThrows(IngestionProblem.class,()->service.readable("owner",id)).status);
        verifyNoInteractions(client,jobs);
    }
}
