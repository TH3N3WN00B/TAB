package me.neznamy.tab.shared.cpu;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import me.neznamy.tab.shared.TAB;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Thread executor for accepting tasks to execute them in a single threaded executor.
 * All tasks are try/catch-ed and might track CPU usage if needed.
 */
public class ThreadExecutor {

    /** Timeout for finishing tasks when shutting down thread executor */
    private static final int SHUTDOWN_TIMEOUT = 5000;

    private final String threadName;
    private final ScheduledThreadPoolExecutor executor;

    /** Thread created by the executor, used to detect calls from within this executor */
    private volatile Thread thread;

    /**
     * Constructs new instance and starts new thread executor with give name.
     *
     * @param   threadName
     *          Name of the created thread
     */
    public ThreadExecutor(@NotNull String threadName) {
        this.threadName = threadName;
        ThreadFactory factory = new ThreadFactoryBuilder().setNameFormat(threadName).build();
        executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = factory.newThread(r);
            thread = t;
            return t;
        });
    }

    /**
     * Returns {@code true} if this method is called from the thread of this executor,
     * {@code false} if not.
     *
     * @return  {@code true} if called from the thread of this executor, {@code false} if not
     */
    public boolean isCurrentThread() {
        return Thread.currentThread() == thread;
    }

    /**
     * Shuts down the executor.
     */
    public void shutdown() {
        long time = System.currentTimeMillis();
        executor.shutdown();

        // Delayed tasks (such as announcements) would still run after shutdown, blocking until they are due.
        // Cancel them manually, because setExecuteExistingDelayedTasksAfterShutdownPolicy(false) also drops
        // tasks which are already due on Java 8. Removing them also lets the executor terminate.
        for (Runnable task : executor.getQueue()) {
            if (task instanceof RunnableScheduledFuture && ((RunnableScheduledFuture<?>) task).getDelay(TimeUnit.NANOSECONDS) > 0) {
                ((RunnableScheduledFuture<?>) task).cancel(false);
                executor.remove(task);
            }
        }

        if (isCurrentThread()) {
            // Called from a task of this executor (such as unload on Velocity), it cannot wait for its own termination
            TAB.getInstance().debug("Thread " + threadName + " was shut down from itself, not waiting for termination");
            return;
        }
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT, TimeUnit.MILLISECONDS)) {
                List<Runnable> cancelledTasks = executor.shutdownNow();
                TAB.getInstance().getErrorManager().printError("Soft shutdown of thread " + threadName + " exceeded time limit of " + SHUTDOWN_TIMEOUT +
                        "ms, forcing shutdown. This may cause issues. Cancelling " + cancelledTasks.size() + " tasks.", null);
            }
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT, TimeUnit.MILLISECONDS)) {
                TAB.getInstance().getErrorManager().printError("Hard shutdown of thread " + threadName + " exceeded time limit of " + SHUTDOWN_TIMEOUT +
                        "ms. This may cause issues.", null);
            }
        } catch (InterruptedException ignored) {
            // Shutdown successful
            TAB.getInstance().debug("Thread " + threadName + " shutdown in " + (System.currentTimeMillis() - time) + "ms");
        }
    }

    public void execute(@NotNull Runnable task) {
        if (executor.isShutdown()) return;
        executor.execute(new CaughtTask(task));
    }

    public void execute(@NotNull TimedCaughtTask task) {
        if (executor.isShutdown()) return;
        executor.execute(task);
    }

    public void executeLater(@NotNull TimedCaughtTask task, int delayMillis) {
        if (executor.isShutdown()) return;
        executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Starts a repeating task.
     *
     * @param   task
     *          Task to run periodically
     * @param   intervalMilliseconds
     *          How often should the task run
     */
    public void repeatTask(@NotNull TimedCaughtTask task, int intervalMilliseconds) {
        if (executor.isShutdown()) return;
        executor.scheduleAtFixedRate(task, intervalMilliseconds, intervalMilliseconds, TimeUnit.MILLISECONDS);
    }
}
