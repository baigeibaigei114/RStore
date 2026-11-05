package com.remotesensing.platform.ingestion.transport;

import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import com.remotesensing.platform.ingestion.runtime.IngestionPublicationExecutor;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable PUBLISH intent, never MQ. COMPUTE arbitration also covers legacy NDVI and VALIDATE. */
@Component
public class IngestionPublicationDispatch {
    private static final Logger LOG=LoggerFactory.getLogger(IngestionPublicationDispatch.class);
    private final IngestionStore db;private final IngestionPublicationExecutor execution;private final AtomicBoolean busy=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"ingestion-local-publisher");t.setDaemon(true);return t;});
    public IngestionPublicationDispatch(IngestionStore db,IngestionPublicationExecutor execution){this.db=db;this.execution=execution;}
    @Scheduled(fixedDelay=2000) public void dispatch(){
        if(!execution.available() || busy.get())return;
        var rows=db.rows("SELECT id FROM ingest_outbox w WHERE dispatch_mode='LOCAL' AND kind='PUBLISH' AND status IN ('PENDING','SENDING') AND next_send_at<=clock_timestamp() AND NOT EXISTS(SELECT 1 FROM ingest_attempt a WHERE a.work_id=w.id) ORDER BY created_at LIMIT 1");
        if(rows.isEmpty() || !busy.compareAndSet(false,true))return;
        try{worker.submit(()->{try{execution.execute(IngestionStore.id(rows.get(0)));}catch(RuntimeException error){LOG.warn("LOCAL发布工作未开始或待恢复");}finally{busy.set(false);}});}catch(RejectedExecutionException error){busy.set(false);}
    }
    @jakarta.annotation.PreDestroy public void close(){worker.shutdownNow();}
}
