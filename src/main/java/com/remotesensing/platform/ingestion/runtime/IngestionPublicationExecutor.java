package com.remotesensing.platform.ingestion.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import com.remotesensing.platform.ingestion.publishing.*;
import com.remotesensing.platform.ingestion.workflow.*;
import com.remotesensing.platform.ingestion.workflow.IngestionLayerService.Asset;
import com.remotesensing.platform.ingestion.workflow.IngestionLayerService.MapRequest;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import static com.remotesensing.platform.ingestion.api.IngestionProblem.require;
import static com.remotesensing.platform.ingestion.workflow.IngestionJson.*;

/** Synchronous bounded IO under one LOCAL attempt, never under a database business lock. */
@Component
public class IngestionPublicationExecutor {
    private static final Logger LOG=LoggerFactory.getLogger(IngestionPublicationExecutor.class);
    private final IngestionStore db;private final IngestionPublicationWorkService works;private final IngestionGeoServerClient geo;
    private final IngestionMapCopyStorage copies;private final ComputeSlots slots;
    private final String executor=UUID.randomUUID().toString();
    public IngestionPublicationExecutor(IngestionStore db,IngestionPublicationWorkService works,IngestionGeoServerClient geo,IngestionMapCopyStorage copies,ComputeSlots slots){this.db=db;this.works=works;this.geo=geo;this.copies=copies;this.slots=slots;}
    public boolean available(){return geo.configured();}
    public void execute(UUID work){
        require(available(),503,"SERVICE_UNAVAILABLE");
        // ACL/identity failure must not start or enable a publication attempt.
        geo.boundary(Instant.now().plusSeconds(30));
        ObjectNode claim=works.claim(work,UUID.randomUUID(),executor);if(!"EXECUTE".equals(claim.path("action").asText()))return;
        ObjectNode identity=db.object().put("attemptId",claim.path("attemptId").asText()).put("slotFence",claim.path("slotFence").asLong());
        Instant deadline=java.time.OffsetDateTime.parse(claim.path("hardDeadline").asText()).toInstant();
        AtomicReference<RuntimeException> lost=new AtomicReference<>();long[] sequence={0};
        ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"ingestion-publication-lease");t.setDaemon(true);return t;});
        boolean known=true,completing=false;String completionDigest=null;
        heartbeat.scheduleAtFixedRate(()->{try{works.heartbeat(work,identity,executor,++sequence[0]);}catch(RuntimeException error){lost.compareAndSet(null,error);}},20,20,TimeUnit.SECONDS);
        try{
            Asset asset=works.guard(work,identity,executor,false);
            copies.prepare(asset,works.copy(asset.publication(),false),deadline,()->lost.get()==null && Instant.now().isBefore(deadline));
            geo.publish(asset,deadline,()->{
                require(lost.get()==null && Instant.now().isBefore(deadline),409,"LEASE_LOST");
                require(works.guard(work,identity,executor,false).fingerprint().equals(asset.fingerprint()),409,"ASSET_CHANGED");
                require(lost.get()==null,409,"LEASE_LOST");
            });
            byte[] png=verifyPublicationAttempt(work,identity,deadline,lost);
            completionDigest=sha(db.object().put("asset",asset.fingerprint()).put("png",sha(png)));
            completing=true;works.complete(work,identity,executor,asset.fingerprint(),sha(png));completing=false;
        }catch(Exception error){
            String reason=error instanceof IngestionProblem problem?problem.reason:"PUBLISH_FAILED";
            if(completing && !(error instanceof IngestionPublicationWorkService.CompletionRolledBack)){
                try{if(completionDigest!=null && works.completed(work,identity,executor,completionDigest)){completing=false;return;}}
                catch(RuntimeException ignored){/* keep recovery until the persistent receipt can be read */}
                known=false;LOG.warn("发布完成提交未确认，保持恢复态等待持久回执核对");
            }else{
                if("PUBLISH_UNCERTAIN".equals(reason))known=false;
                try{works.fail(work,identity,executor,reason);}catch(RuntimeException uncertain){known=false;LOG.warn("发布失败提交未确认，保持当前fence");}
            }
        }finally{
            heartbeat.shutdownNow();
            if(known){try{works.stopped(work,identity,executor);}catch(RuntimeException error){slots.recovery("COMPUTE","WORK:"+work,executor,identity.path("slotFence").asLong());LOG.warn("发布停止回执未确认，保持恢复态");}}
            else slots.recovery("COMPUTE","WORK:"+work,executor,identity.path("slotFence").asLong());
        }
    }
    /** Only a currently claimed PUBLISHING attempt reaches this process-local verification path. */
    private byte[] verifyPublicationAttempt(UUID work,ObjectNode identity,Instant deadline,AtomicReference<RuntimeException> lost){
        require(lost.get()==null && Instant.now().isBefore(deadline),409,"LEASE_LOST");
        Asset a=works.guard(work,identity,executor,false);copies.verify(a,deadline,()->lost.get()==null);
        byte[] png=geo.map(a,nativeMap(a),deadline);
        require(lost.get()==null && Instant.now().isBefore(deadline),409,"LEASE_LOST");
        require(works.guard(work,identity,executor,false).fingerprint().equals(a.fingerprint()),409,"ASSET_CHANGED");
        return png;
    }
    public static MapRequest nativeMap(Asset a){
        var m=a.manifest();var tr=m.path("transform");require(tr.isArray() && tr.size()==6,409,"ASSET_CHANGED");
        double left=Double.POSITIVE_INFINITY,bottom=Double.POSITIVE_INFINITY,right=Double.NEGATIVE_INFINITY,top=Double.NEGATIVE_INFINITY;
        for(int x:new int[]{0,m.path("width").asInt()})for(int y:new int[]{0,m.path("height").asInt()}){
            double px=tr.get(0).asDouble()*x+tr.get(1).asDouble()*y+tr.get(2).asDouble(),py=tr.get(3).asDouble()*x+tr.get(4).asDouble()*y+tr.get(5).asDouble();
            require(Double.isFinite(px) && Double.isFinite(py),409,"ASSET_CHANGED");left=Math.min(left,px);right=Math.max(right,px);bottom=Math.min(bottom,py);top=Math.max(top,py);
        }
        require(left<right && bottom<top,409,"ASSET_CHANGED");return new MapRequest(left+","+bottom+","+right+","+top,m.path("crs").asText(),256,256,true);
    }
}
