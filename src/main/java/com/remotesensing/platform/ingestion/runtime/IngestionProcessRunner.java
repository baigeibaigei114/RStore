package com.remotesensing.platform.ingestion.runtime;

import com.remotesensing.platform.config.properties.PythonWorkerProperties;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Linux LOCAL child process group. Completion is reported only after the child has stopped. */
@Component
public class IngestionProcessRunner {
    public static class UnconfirmedStop extends RuntimeException {
        public UnconfirmedStop() {super("LOCAL child stop could not be confirmed");}
    }
    private final PythonWorkerProperties python;
    public IngestionProcessRunner(PythonWorkerProperties python) {this.python=python;}
    public Path helper() {
        Path parser=Path.of(python.getParseMetadataScript()).toAbsolutePath().normalize();
        return parser.getParent().getParent().resolve("ingestion/validate_candidate.py");
    }
    public boolean available() {return System.getProperty("os.name").toLowerCase().contains("linux") && Files.isRegularFile(helper()) && !Files.isSymbolicLink(helper());}
    public void run(Path raster,Path region,Path result,Path ready,Instant deadline,BooleanSupplier lease,Consumer<String> stopped) {
        IngestionProblem.require(available(),503,"SERVICE_UNAVAILABLE");
        Process child=null;
        try {
            long seconds=Math.max(1,java.time.Duration.between(Instant.now(),deadline).getSeconds());
            ProcessBuilder builder=new ProcessBuilder(List.of(python.getPythonExecutable(),"-B",helper().toString(),raster.toString(),region.toString(),result.toString(),ready.toString(),Long.toString(ProcessHandle.current().pid()),Long.toString(seconds)));
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().putAll(java.util.Map.of("OPENBLAS_NUM_THREADS","1","OMP_NUM_THREADS","1","GDAL_NUM_THREADS","1","OPJ_NUM_THREADS","1"));
            child=builder.start();
            while(child.isAlive()) {
                IngestionProblem.require(Instant.now().isBefore(deadline),503,"HARD_TIMEOUT");
                IngestionProblem.require(lease.getAsBoolean(),409,"LEASE_LOST");
                child.waitFor(100,TimeUnit.MILLISECONDS);
            }
            IngestionProblem.require(child.exitValue()!=124,503,"HARD_TIMEOUT");
            IngestionProblem.require(child.exitValue()==0 && Files.isRegularFile(result),400,"ADMISSION_REJECTED");
        } catch(IngestionProblem error) {throw error;}
        catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IngestionProblem(503,"LEASE_LOST");}
        catch(java.io.IOException error) {throw new IngestionProblem(503,"SERVICE_UNAVAILABLE");}
        finally {
            if(child!=null) {
                boolean killed=child.isAlive();
                if(killed)stop(child,ready);
                if(child.isAlive())throw new UnconfirmedStop();
                stopped.accept(killed?"KILLED":"EXITED");
            }
        }
    }
    private void stop(Process child,Path ready) {
        boolean interrupted=Thread.interrupted();
        try {
            boolean group=false;
            try {group=Files.isRegularFile(ready) && Files.size(ready)<32 && Files.readString(ready).trim().equals(Long.toString(child.pid()));}
            catch(java.io.IOException ignored) { /* No readiness proof: only the direct bootstrap child exists. */ }
            if(group)signal(child.pid(),"TERM");else child.destroy();
            interrupted|=await(child,10000);
            if(child.isAlive()) {if(group)signal(child.pid(),"KILL");child.destroyForcibly();interrupted|=await(child,2000);}
        } finally {if(interrupted)Thread.currentThread().interrupt();}
    }
    private boolean await(Process child,long millis) {
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);boolean interrupted=false;
        while(child.isAlive() && System.nanoTime()<deadline) {
            try {Thread.sleep(25);}catch(InterruptedException error) {interrupted=true;}
        }
        return interrupted;
    }
    private void signal(long pid,String signal) {
        Process command=null;
        try {
            command=new ProcessBuilder("/bin/kill","-"+signal,"--","-"+pid).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            await(command,2000);
        } catch(java.io.IOException ignored) { /* Direct kill below remains the fallback; no stop proof until exit. */ }
        finally {if(command!=null && command.isAlive())command.destroyForcibly();}
    }
}
