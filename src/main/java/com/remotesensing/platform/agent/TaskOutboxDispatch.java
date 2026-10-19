package com.remotesensing.platform.agent;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Post-commit attempts run without the completed transaction's connection or browser latency. */
public final class TaskOutboxDispatch {
    private static final int MAX_WORKERS = 2;
    private static final int QUEUE_CAPACITY = 32;
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(1, MAX_WORKERS, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
                Thread thread = new Thread(runnable, "task-outbox-" + THREAD_ID.incrementAndGet());
                thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    static { EXECUTOR.allowCoreThreadTimeOut(true); }
    private TaskOutboxDispatch() {}
    public static boolean offer(Runnable publish) {
        try { EXECUTOR.execute(publish); return true; }
        catch (RejectedExecutionException full) { return false; }
    }
}
