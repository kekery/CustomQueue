package org.hongtaozhao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class CustomQueueTest {

    @Test
    @DisplayName("Wrong constructor parameters throw exception")
    void testWrongArgumentsThenThrowsException() {
        Exception exception = assertThrows(IllegalArgumentException.class,
                () -> new CustomQueue<>(1, 2));

        assertNotNull(exception);
    }

    @Test
    @DisplayName("Consume from empty queue throw exception")
    void testConsumeEmptyQueueThenThrowsException() {
        CustomQueue<Integer> queue = new CustomQueue<>(1024, 2);

        Exception exception = assertThrows(CustomQueue.CustomQueueIsEmptyException.class,
                queue::dequeue);

        assertNotNull(exception);
    }

    @Test
    @DisplayName("produce into full queue throw exception")
    void testProduceIntoFullQueueThenThrowsException() {
        try {
            CustomQueue<Integer> queue = new CustomQueue<>(2, 1);
            queue.enqueue(1, 1);
            queue.enqueue(2, 2);

            Exception exception = assertThrows(CustomQueue.CustomQueueIsFullException.class,
                    () -> queue.enqueue(3, 3));

            assertNotNull(exception);
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("Single consumer enqueue all then dequeue all and the order should match")
    void testEnqueueAndDequeue() {
        try {
            final int[] enqueueSequence = {4, 1, 3, 2, 1, 4, 2, 3, 2, 4, 1, 3, 3, 5, 2, 1, 3, 6, 1, 2, 4, 2, 4, 1, 3, 2,
                    1, 5, 2, 1, 1, 2, 3, 1, 1};
            final String expectedDequeueSequence = "11211231121123411212322345233434564";

            CustomQueue<Integer> queue = new CustomQueue<>(1024, 2);

            for (int i : enqueueSequence) {
                queue.enqueue(i, i);
            }

            StringBuilder builder = new StringBuilder();

            while (queue.size() > 0) {
                builder.append(queue.dequeue());
            }

            assertEquals(expectedDequeueSequence, builder.toString());

        } catch (CustomQueue.CustomQueueIsFullException | CustomQueue.CustomQueueIsEmptyException e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("Single consumer dequeue all then dequeue all one key then put one key back and throttling happens")
    void testRestoringKeyThenReserveTheThrottling1() {
        try {
            final int[] enqueueSequence = {4, 1, 3, 2, 1, 2};
            CustomQueue<Integer> queue = new CustomQueue<>();

            for (int i : enqueueSequence) {
                queue.enqueue(i, i);
            }

            assertEquals(1, queue.dequeue());
            assertEquals(1, queue.dequeue());

            queue.enqueue(1, 1);
            assertEquals(2, queue.dequeue());
            assertEquals(1, queue.dequeue());
            assertEquals(2, queue.dequeue());
            assertEquals(3, queue.dequeue());

        } catch (CustomQueue.CustomQueueIsFullException | CustomQueue.CustomQueueIsEmptyException e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("Single consumer dequeue all then dequeue some keys then put one key back and order reserves")
    void testRestoringKeyThenReserveTheThrottling2() {
        try {
            final int[] enqueueSequence = {4, 1, 3, 2, 2};
            CustomQueue<Integer> queue = new CustomQueue<>();

            for (int i : enqueueSequence) {
                queue.enqueue(i, i);
            }

            assertEquals(1, queue.dequeue());
            assertEquals(2, queue.dequeue());

            queue.enqueue(1, 1);
            assertEquals(1, queue.dequeue());
            assertEquals(2, queue.dequeue());
            assertEquals(3, queue.dequeue());
            assertEquals(4, queue.dequeue());

        } catch (CustomQueue.CustomQueueIsFullException | CustomQueue.CustomQueueIsEmptyException e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("Multiple consumer consume the same queue and would produce the same sequence from it")
    void testMultipleConsumerDequeuedResultAsInSameOrder() {
        try {
            final int[] enqueueSequence = {4, 1, 3, 2, 1, 4, 2, 3, 2, 4, 1, 3, 3, 5, 2, 1, 3, 6, 1, 2, 4, 2, 4, 1, 3, 2,
                    1, 5, 2, 1, 1, 2, 3, 1, 1};
            final String expectedDequeueSequence = "11211231121123411212322345233434564";

            CustomQueue<Integer> queue = new CustomQueue<>(1024, 2);

            for (int i : enqueueSequence) {
                queue.enqueue(i, i);
            }

            BlockingQueue<Integer> concurrentConsumedQueue = new ArrayBlockingQueue<>(1024);

            ExecutorService service = Executors.newFixedThreadPool(5);
            Future future = service.submit(() -> {
                while (queue.size() > 0) {
                    try {
                        concurrentConsumedQueue.add(queue.dequeue());
                    } catch (CustomQueue.CustomQueueIsEmptyException e) {
                        throw new RuntimeException(e);
                    }
                }
            });

            future.get();

            StringBuilder builder = new StringBuilder();

            while (concurrentConsumedQueue.size() > 0) {
                builder.append(concurrentConsumedQueue.poll());
            }

            assertEquals(expectedDequeueSequence, builder.toString());

        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("Multiple producer enqueue then consumer consume the same queue and would produce the same sequence")
    void testMultipleProducerEnqueueConsumerDequeuedResultAsInSameOrder() {
        try {
            final int[] enqueueSequence = {4, 1, 3, 2, 1, 4, 2, 3, 2, 4, 1, 3, 3, 5, 2, 1, 3, 6, 1, 2, 4, 2, 4, 1, 3, 2,
                    1, 5, 2, 1, 1, 2, 3, 1, 1};
            final String expectedDequeueSequence = "11211231121123411212322345233434564";

            CustomQueue<Integer> queue = new CustomQueue<>(1024, 2);

            BlockingQueue<Integer> concurrentConsumedQueue = new ArrayBlockingQueue<>(1024);

            for (int i : enqueueSequence) {
                concurrentConsumedQueue.add(i);
            }

            ExecutorService service = Executors.newFixedThreadPool(5);
            Future future = service.submit(() -> {
                while (concurrentConsumedQueue.size() > 0) {
                    try {
                        int i = concurrentConsumedQueue.poll();
                        queue.enqueue(i, i);
                    } catch (CustomQueue.CustomQueueIsFullException e) {
                        throw new RuntimeException(e);
                    }
                }
            });

            future.get();

            future = service.submit(() -> {
                while (queue.size() > 0) {
                    try {
                        concurrentConsumedQueue.add(queue.dequeue());
                    } catch (CustomQueue.CustomQueueIsEmptyException e) {
                        throw new RuntimeException(e);
                    }
                }
            });

            future.get();

            StringBuilder builder = new StringBuilder();

            while (concurrentConsumedQueue.size() > 0) {
                builder.append(concurrentConsumedQueue.poll());
            }

            assertEquals(expectedDequeueSequence, builder.toString());

        } catch (Exception e) {
            fail(e);
        }
    }
}
