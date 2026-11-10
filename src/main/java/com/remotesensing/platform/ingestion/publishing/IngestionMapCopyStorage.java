package com.remotesensing.platform.ingestion.publishing;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.config.properties.*;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import com.remotesensing.platform.ingestion.workflow.IngestionStorage;
import com.remotesensing.platform.ingestion.workflow.IngestionLayerService.Asset;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.HexFormat;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;
import static com.remotesensing.platform.ingestion.api.IngestionProblem.require;

/** One immutable shared-map path per admitted image; never overwrite an unexplained existing file. */
@Service
public class IngestionMapCopyStorage {
    private final GeoServerProperties geo;private final IngestionProperties limits;private final IngestionStorage storage;
    public IngestionMapCopyStorage(GeoServerProperties geo,IngestionProperties limits,IngestionStorage storage){this.geo=geo;this.limits=limits;this.storage=storage;}
    public static String key(Asset a){return "ingestion/assets/"+a.publication().path("image_id").asLong()+"/raster.tif";}
    private Path path(Asset a,boolean create)throws IOException{
        Path root=Path.of(geo.getSharedDataLocalDir()).toAbsolutePath().normalize();
        Path target=root.resolve(key(a)).normalize();require(target.startsWith(root) && !target.equals(root),503,"SERVICE_UNAVAILABLE");
        Path cursor=root.getRoot();
        for(Path part:root){cursor=cursor.resolve(part);if(Files.exists(cursor,LinkOption.NOFOLLOW_LINKS))require(!Files.isSymbolicLink(cursor),503,"SERVICE_UNAVAILABLE");else if(create)Files.createDirectory(cursor);else require(false,409,"ASSET_CHANGED");}
        cursor=root;
        for(Path part:root.relativize(target.getParent())){cursor=cursor.resolve(part);if(Files.exists(cursor,LinkOption.NOFOLLOW_LINKS))require(!Files.isSymbolicLink(cursor) && Files.isDirectory(cursor,LinkOption.NOFOLLOW_LINKS),503,"SERVICE_UNAVAILABLE");else if(create)Files.createDirectory(cursor);else require(false,409,"ASSET_CHANGED");}
        require(!Files.isSymbolicLink(target) && target.getParent().toRealPath().startsWith(root.toRealPath()),503,"SERVICE_UNAVAILABLE");return target;
    }
    private void alive(Instant deadline,BooleanSupplier lease){require(Instant.now().isBefore(deadline) && lease.getAsBoolean() && !Thread.currentThread().isInterrupted(),409,"LEASE_LOST");}
    public void verify(Asset a,Instant deadline,BooleanSupplier lease){
        try{
            Path target=path(a,false);require(Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS) && Files.size(target)==a.tiff().path("actual_bytes").asLong(),409,"INPUT_HASH_MISMATCH");
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            try(var in=Files.newInputStream(target,LinkOption.NOFOLLOW_LINKS)){byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1){alive(deadline,lease);digest.update(buf,0,n);}}
            require(HexFormat.of().formatHex(digest.digest()).equals(a.tiff().path("sha256").asText()),409,"INPUT_HASH_MISMATCH");
        }catch(IngestionProblem error){throw error;}catch(Exception error){throw new IngestionProblem(503,"PUBLISH_FAILED");}
    }
    public void prepare(Asset a,ObjectNode reservation,Instant deadline,BooleanSupplier lease){
        require("MAP_COPY".equals(reservation.path("role").asText()) && "SHARED_MAP".equals(reservation.path("storage").asText())
                && reservation.path("publication_id").equals(a.publication().path("id")) && reservation.path("owner_id").equals(a.publication().path("owner_id"))
                && reservation.path("server_key").asText().equals(key(a)) && reservation.path("reserved_bytes").asLong()>=a.tiff().path("actual_bytes").asLong(),409,"ASSET_CHANGED");
        Path part=null;
        try{
            Path target=path(a,true);alive(deadline,lease);
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){verify(a,deadline,lease);return;}
            long bytes=a.tiff().path("actual_bytes").asLong();require(bytes>0 && bytes<=(256L<<20) && Files.getFileStore(target.getParent()).getUsableSpace()>=bytes+limits.getDiskSafetyBytes(),503,"RESOURCE_LIMIT");
            // The minio client uses CREATE_NEW; allocate only the unpredictable NAME here.
            part=target.getParent().resolve("copy-"+java.util.UUID.randomUUID()+".part");
            storage.download(a.tiff(),part,Math.max(0,Duration.between(Instant.now(),deadline).getSeconds()),256L<<20);alive(deadline,lease);
            require(path(a,false).equals(target),503,"SERVICE_UNAVAILABLE");
            // A hard link is an atomic no-replace install on the shared Linux filesystem.
            // Unsupported filesystems fail closed instead of a race-prone overwrite/copy fallback.
            try{Files.createLink(target,part);}catch(FileAlreadyExistsException raced){verify(a,deadline,lease);}
            verify(a,deadline,lease);
        }catch(IngestionProblem error){throw error;}catch(IOException error){throw new IngestionProblem(503,"PUBLISH_FAILED");}
        finally{if(part!=null)try{Files.deleteIfExists(part);}catch(IOException ignored){/* bounded ORPHAN sidecar, never delete the permanent target */}}
    }
}
