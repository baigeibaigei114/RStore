package com.remotesensing.platform.ingestion.publishing;

import com.remotesensing.platform.config.properties.*;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IngestionGeoServerClientTest {
    @Test void pngRequiresActualDecodeAndExactBoundedDimensions() throws Exception {
        var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(256,256,BufferedImage.TYPE_INT_ARGB),"PNG",output);
        byte[] png=output.toByteArray();assertEquals(256,IngestionPngValidation.validate(png,"image/png",256,256).getWidth());
        assertThrows(IngestionProblem.class,()->IngestionPngValidation.validate(png,"application/xml",256,256));
        assertThrows(IngestionProblem.class,()->IngestionPngValidation.validate(png,"image/png",2048,2048));
        assertThrows(IngestionProblem.class,()->IngestionPngValidation.validate("<ServiceException/>".getBytes(),"image/png",256,256));
    }
    @Test void newClientHasNoDefaultIdentityOrBoundaryApproval(){
        var c=new IngestionProperties();c.setEnabled(true);c.setPublicationEnabled(true);
        var client=new IngestionGeoServerClient(new GeoServerProperties(),c);assertFalse(client.configured());client.close();
    }
}
