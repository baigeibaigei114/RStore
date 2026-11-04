package com.remotesensing.platform.ingestion.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.config.properties.IngestionProperties;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import com.remotesensing.platform.ingestion.workflow.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import static com.remotesensing.platform.ingestion.api.IngestionProblem.require;
import static com.remotesensing.platform.ingestion.workflow.IngestionJson.*;

/** One LOCAL attempt; scheduled lease callbacks never execute native parsing themselves. */
@Component
public class IngestionValidationExecutor implements IngestionJobService.ValidationRuntime {
    private static final Logger LOG=LoggerFactory.getLogger(IngestionValidationExecutor.class);
    private final IngestionStore db;
    private final IngestionProperties config;
    private final IngestionJobService jobs;
    private final IngestionJobWorkService works;
    private final IngestionValidationService validation;
    private final IngestionAssetRegistration registration;
    private final IngestionProcessRunner runner;
    private final ComputeSlots slots;
    private final String executor=UUID.randomUUID().toString();
    private Path root;
    public IngestionValidationExecutor(IngestionStore db,IngestionProperties config,IngestionJobService jobs,IngestionJobWorkService works,
        IngestionValidationService validation,IngestionAssetRegistration registration,IngestionProcessRunner runner,ComputeSlots slots) {
        this.db=db;this.config=config;this.jobs=jobs;this.works=works;this.validation=validation;this.registration=registration;this.runner=runner;this.slots=slots;
    }
    @jakarta.annotation.PostConstruct public void prepare() {
        if(!config.isEnabled() || !config.isStandardizationEnabled())return;
        try {
            Path configured=Path.of(config.getTempDirectory()).toAbsolutePath().normalize().resolve("validation");
            Files.createDirectories(configured);require(!Files.isSymbolicLink(configured),503,"SERVICE_UNAVAILABLE");root=configured.toRealPath();
        } catch(java.io.IOException error) {LOG.warn("入库LOCAL验证临时目录不可用");}
    }
    @Override public boolean available() {return config.isEnabled() && config.isStandardizationEnabled() && root!=null && Files.isWritable(root) && runner.available();}
    public void execute(UUID workId) {
        require(available(),503,"SERVICE_UNAVAILABLE");
        ObjectNode claim=works.claimLocal(workId,UUID.randomUUID(),executor);if(!"EXECUTE".equals(claim.path("action").asText()))return;
        ObjectNode identity=db.object().put("attemptId",claim.path("attemptId").asText()).put("slotFence",claim.path("slotFence").asLong());
        AtomicReference<RuntimeException> lost=new AtomicReference<>();
        ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(r -> {Thread t=new Thread(r,"ingestion-local-lease");t.setDaemon(true);return t;});
        long[] sequence={0};String[] termination={"EXITED"};boolean stopKnown=true;Path directory=null;
        Instant deadline=java.time.OffsetDateTime.parse(claim.path("hardDeadline").asText()).toInstant();
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                ObjectNode request=identity.deepCopy().put("sequence",++sequence[0]);works.heartbeatLocal(workId,request,executor);
            } catch(RuntimeException error) {lost.compareAndSet(null,error);}
        },0,20,TimeUnit.SECONDS);
        try {
            require(Files.getFileStore(root).getUsableSpace()>=(512L<<20)+config.getDiskSafetyBytes(),503,"RESOURCE_LIMIT");
            directory=Files.createTempDirectory(root,"validate-"+claim.path("attemptId").asText()+"-");
            ObjectNode work=works.work(workId,true,false),job=jobs.job(db.one("SELECT owner_id FROM ingest_job WHERE id=?",uuid(work,"target_id")).path("owner_id").asText(),uuid(work,"target_id"),false);
            var actual=validation.validate(job,directory,deadline,() -> lost.get()==null && Instant.now().isBefore(deadline),value -> termination[0]=value);
            String digest=sha(db.object().put("candidateHash",actual.candidateHash()).set("admission",actual.metadata().getAdmission()));
            works.commitValidated(workId,identity,executor,digest,(current,attempt) -> registration.register(current,attempt,actual));
        } catch(IngestionProcessRunner.UnconfirmedStop error) {
            stopKnown=false;slots.recovery("COMPUTE","WORK:"+workId,executor,claim.path("slotFence").asLong());LOG.warn("LOCAL原生进程停止未确认，保持恢复态");
        } catch(Exception error) {
            String reason=error instanceof IngestionProblem p?p.reason:"SERVICE_UNAVAILABLE";
            if(!java.util.Set.of("ADMISSION_REJECTED","INVALID_GRID","NO_VALID_PIXELS","INPUT_HASH_MISMATCH","RESOURCE_LIMIT","HARD_TIMEOUT","LEASE_LOST","SERVICE_UNAVAILABLE").contains(reason))reason="ADMISSION_REJECTED";
            try {works.failLocal(workId,identity.deepCopy().put("reason",reason),executor);}
            catch(RuntimeException ignored) {LOG.warn("LOCAL验证失败状态需按持久回执核对");}
        } finally {
            heartbeat.shutdownNow();
            if(stopKnown) {
                try {works.stoppedLocal(workId,identity.deepCopy().put("executorId",executor).put("termination",termination[0]),executor);}
                catch(RuntimeException error) {LOG.warn("LOCAL停止回执未确认，保持当前fence");}
                if(directory!=null)cleanup(directory);
            }
        }
    }
    private void cleanup(Path directory) {
        try {
            require(directory.getParent().equals(root) && !Files.isSymbolicLink(directory),503,"SERVICE_UNAVAILABLE");
            try(var paths=Files.walk(directory)) {
                for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) {
                    require(path.toAbsolutePath().normalize().startsWith(directory) && !path.equals(root),503,"SERVICE_UNAVAILABLE");Files.deleteIfExists(path);
                }
            }
        } catch(java.io.IOException | IngestionProblem error) {LOG.warn("LOCAL临时目录待后续清理");}
    }
}
