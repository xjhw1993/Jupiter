package org.jupiter.common.concurrent.disruptor;

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import org.jupiter.common.concurrent.NamedThreadFactory;
import org.jupiter.common.concurrent.RejectedTaskPolicyWithReport;
import org.jupiter.common.util.Pow2;

import java.util.concurrent.*;

import static org.jupiter.common.concurrent.disruptor.WaitStrategyType.*;

/**
 * 可选择的等待策略，性能由低到高：
 *
 * The default wait strategy used by the Disruptor is the BlockingWaitStrategy.
 * Internally the BlockingWaitStrategy uses a typical lock and condition variable to handle thread wake-up.
 * The BlockingWaitStrategy is the slowest of the available wait strategies,
 * but is the most conservative with the respect to CPU usage and will give the most consistent behaviour across
 * the widest variety of deployment options. However, again knowledge of the deployed system can allow for additional
 * performance.
 *
 * SleepingWaitStrategy:
 * Like the BlockingWaitStrategy the SleepingWaitStrategy it attempts to be conservative with CPU usage,
 * by using a simple busy wait loop, but uses a call to LockSupport.parkNanos(1) in the middle of the loop.
 * On a typical Linux system this will pause the thread for around 60µs.
 * However it has the benefit that the producing thread does not need to take any action other increment the appropriate
 * counter and does not require the cost of signalling a condition variable. However, the mean latency of moving the
 * event between the producer and consumer threads will be higher. It works best in situations where low latency is not
 * required, but a low impact on the producing thread is desired. A common use case is for asynchronous logging.
 *
 * YieldingWaitStrategy:
 * The YieldingWaitStrategy is one of 2 Wait Strategies that can be use in low latency systems,
 * where there is the option to burn CPU cycles with the goal of improving latency.
 * The YieldingWaitStrategy will busy spin waiting for the sequence to increment to the appropriate value.
 * Inside the body of the loop Thread.yield() will be called allowing other queued threads to run.
 * This is the recommended wait strategy when need very high performance and the number of Event Handler threads is
 * less than the total number of logical cores, e.g. you have hyper-threading enabled.
 *
 * BusySpinWaitStrategy:
 * The BusySpinWaitStrategy is the highest performing Wait Strategy, but puts the highest constraints on the deployment
 * environment. This wait strategy should only be used if the number of Event Handler threads is smaller than the number
 * of physical cores on the box. E.g. hyper-threading should be disabled
 *
 * jupiter
 * org.jupiter.common.concurrent.disruptor
 *
 * @author jiachun.fjc
 */
public class TaskDispatcher implements Dispatcher<Runnable>, Executor {

    private static final EventFactory<MessageEvent<Runnable>> eventFactory = new EventFactory<MessageEvent<Runnable>>() {

        @Override
        public MessageEvent<Runnable> newInstance() {
            return new MessageEvent<>();
        }
    };

    private final Disruptor<MessageEvent<Runnable>> disruptor;
    private final Executor reserveExecutor;

    public TaskDispatcher(int numWorkers) {
        this(numWorkers, "task.dispatcher", BUFFER_SIZE, 0, BLOCKING_WAIT);
    }

    @SuppressWarnings("unchecked")
    public TaskDispatcher(int numWorkers, String threadFactoryName, int bufSize, int numReserveWorkers, WaitStrategyType waitStrategyType) {
        if (bufSize < 0) {
            throw new IllegalArgumentException("bufSize must be larger than 0");
        }
        if (!Pow2.isPowerOfTwo(bufSize)) {
            bufSize = Pow2.roundToPowerOfTwo(bufSize);
        }

        if (numReserveWorkers > 0) {
            reserveExecutor = new ThreadPoolExecutor(
                    0,
                    numReserveWorkers,
                    60L,
                    TimeUnit.SECONDS,
                    new SynchronousQueue<Runnable>(),
                    new NamedThreadFactory("reserve.processor"),
                    new RejectedTaskPolicyWithReport("reserve.processor"));
        } else {
            reserveExecutor = null;
        }

        WaitStrategy waitStrategy;
        switch (waitStrategyType) {
            case BLOCKING_WAIT:
                waitStrategy = new BlockingWaitStrategy();
                break;
            case LITE_BLOCKING_WAIT:
                waitStrategy = new LiteBlockingWaitStrategy();
                break;
            case PHASED_BACK_OFF_WAIT:
                waitStrategy = PhasedBackoffWaitStrategy.withLock(1, 1, TimeUnit.MILLISECONDS);
                break;
            case SLEEPING_WAIT:
                waitStrategy = new SleepingWaitStrategy();
                break;
            case YIELDING_WAIT:
                waitStrategy = new YieldingWaitStrategy();
                break;
            case BUSY_SPIN_WAIT:
                waitStrategy = new BusySpinWaitStrategy();
                break;
            default:
                throw new UnsupportedOperationException(waitStrategyType.toString());
        }

        ThreadFactory tFactory = new NamedThreadFactory(threadFactoryName);
        numWorkers = Math.min(Math.abs(numWorkers), MAX_NUM_WORKERS);
        Disruptor<MessageEvent<Runnable>> dr;
        if (numWorkers == 1) {
            dr = new Disruptor<>(
                    eventFactory, bufSize, Executors.newSingleThreadExecutor(tFactory), ProducerType.MULTI, waitStrategy);
            dr.handleExceptionsWith(new IgnoreExceptionHandler()); // ignore exception
            dr.handleEventsWith(new TaskHandler());
        } else {
            dr = new Disruptor<>(
                    eventFactory, bufSize, Executors.newCachedThreadPool(tFactory), ProducerType.MULTI, waitStrategy);
            dr.handleExceptionsWith(new IgnoreExceptionHandler()); // ignore exception
            WorkHandler<MessageEvent<Runnable>>[] handlers = new TaskHandler[numWorkers];
            for (int i = 0; i < numWorkers; i++) {
                handlers[i] = new TaskHandler();
            }
            dr.handleEventsWithWorkerPool(handlers);
        }

        dr.start();
        disruptor = dr;
    }

    @Override
    public boolean dispatch(Runnable message) {
        RingBuffer<MessageEvent<Runnable>> ringBuffer = disruptor.getRingBuffer();
        try {
            final long sequence = ringBuffer.tryNext();
            try {
                MessageEvent<Runnable> event = ringBuffer.get(sequence);
                event.setMessage(message);
            } finally {
                ringBuffer.publish(sequence);
            }
            return true;
        } catch (InsufficientCapacityException e) {
            return false;
        }
    }

    @SuppressWarnings("NullableProblems")
    @Override
    public void execute(Runnable message) {
        if (!dispatch(message)) {
            // 备选线程池
            if (reserveExecutor != null) {
                reserveExecutor.execute(message);
            } else {
                throw new RejectedExecutionException("ring buffer is full");
            }
        }
    }

    @Override
    public void shutdown() {
        disruptor.shutdown();
    }
}