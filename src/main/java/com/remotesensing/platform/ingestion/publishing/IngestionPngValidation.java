package com.remotesensing.platform.ingestion.publishing;

import com.remotesensing.platform.ingestion.api.IngestionProblem;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import static com.remotesensing.platform.ingestion.api.IngestionProblem.require;

/** Bound header dimensions before asking ImageIO to allocate pixels. XML/OGC errors never pass. */
public final class IngestionPngValidation {
    private IngestionPngValidation() {}
    public static BufferedImage validate(byte[] bytes,String type,int width,int height) {
        require(type!=null && type.split(";",2)[0].trim().equalsIgnoreCase("image/png")
                && bytes.length>=33 && bytes.length<=32L*1024*1024,503,"PUBLISH_FAILED");
        byte[] signature={(byte)137,80,78,71,13,10,26,10};
        for(int i=0;i<8;i++) require(bytes[i]==signature[i],503,"PUBLISH_FAILED");
        var header=java.nio.ByteBuffer.wrap(bytes);
        require(header.getInt(8)==13 && header.getInt(12)==0x49484452
                && header.getInt(16)==width && header.getInt(20)==height
                && width>=1 && width<=2048 && height>=1 && height<=2048,503,"PUBLISH_FAILED");
        try {
            BufferedImage decoded=ImageIO.read(new ByteArrayInputStream(bytes));
            require(decoded!=null && decoded.getWidth()==width && decoded.getHeight()==height,503,"PUBLISH_FAILED");
            return decoded;
        } catch(java.io.IOException error) {throw new IngestionProblem(503,"PUBLISH_FAILED");}
    }
}
