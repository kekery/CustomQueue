package org.hongtaozhao;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;


/**
 * A custom queue with specific throttling mechanism. It is following the FIFO strategy, however there's some specific
 * customization -
 * - Each element in this queue is inserted with a key
 * - The queue dequeues elements based on keys, the least goes out first
 * - When numbers of elements of same key x got dequeued, an element of key x+1 must be dequeued next to throttle,
 * where this number of how many elements dequeued accumulate could be configurable.
 * - This queue can be enqueued and dequeued randomly from multiple threads simultaneously.
 * <p>
 * There's a small flaw from this specification - that a key x could never be dequeued if no consecutive keys are
 * found in this queue, i.e., if the queue contains keys of 1 and 5 only, and we keep enqueue and dequeue elements
 * of key 1, then elements of key 5 could never be dequeued since we are trying to find strictly elements of key 2
 * first, which is not in the queue, and there would be no chance to reach to key 3 or 4, then to 5.
 * </p>
 * <p>
 * In order to resolve the above issue, a slight adjustment is introduced, instead of look for exact key x + 1, we
 * try to find anything in this queue with least greater than the key to be throttled, this way we are reaching 2
 * benefits
 * - We do have throttling in place
 * - elements of keys in the latter of the queue can be eventually dequeued.
 * </p>
 * <p>
 * This queue is thread safe and performs with a time complexity of O(log(m)), where m is the size of the keys, in worst
 * case when enqueuing an element, O(1) in best case; O(1) when dequeue an element.
 * </p>
 * 
 * This source code is for interview evaluation purpose only, no other use beyond this is allowed.
 *
 * @param <E> The type of elements held in this queue.
 * @author Hongtao Zhao
 * @version 1.0.0
 */
public final class CustomQueue<E> {

    /**
     * Make it dynamic on the throttling rate when deciding time to dequeue a key 'x+1' element after dequeue a number
     * of key 'x' elements.
     */
    private final int throttlingRate;

    /**
     * A {@link TreeSet} is used here to maintain the relationships among the keys, so that we know, when enqueuing an
     * element, where this new key is supposed to be inserted in a 'linked list', based on its value, the cost here is
     * O(log(m)), where m is the size of the keys. Attention: only active keys are in this data structure.
     */
    private final TreeSet<Integer> keySet;

    /**
     * A map with a pair of key and a bucket(queue). The benefit of having this additional metadata is, we can quickly
     * know whether a key is already in the current queue, with cost of O(1), when enqueuing an element of a specific
     * key.
     */
    private final Map<Integer, KeyBucket<E>> bucketMap;

    /**
     * A map contains the key buckets that are currently empty, but we'd like to keep its track of dequeue count so that
     * when an element of such key is enqueued, we can track when to throttle dequeue it.
     */
    private final Map<Integer, KeyBucket<E>> parkedBuckedMap;

    /**
     * An internal indicator telling which key is next to be dequeued. This value is calculated upon dequeue an
     * element, or enqueuing of an element of key 'x+1', when 'x+1' doesn't exist yet, and previously dequeued element
     * is of key 'x', and it is time to throttle key 'x'.
     */
    private int nextDequeueKey;

    /**
     * An internal indicator to track what has been previously just dequeued. This value is used to recalculate next
     * dequeue key when an element of key 'x+1', which didn't exist in the queue in previous dequeue action, is enqueued
     * meanwhile.
     */
    private int previousDequeuedKey;

    /**
     * The max size of elements this queue can hold. Default to 16 if no specification is provided.
     */
    private final int capacity;

    /**
     * The current size of the queue.
     */
    private int size;

    /**
     * An internal local used to make the enqueue and dequeue thread safe.
     */
    private final ReentrantLock reentrantLock;

    /**
     * An internal object to store the elements for a same key, and maintain a relationship between the previous key and
     * next key, if possible. This class is introduced mainly to increase the lookup speed when it's time to throttle
     * and a next key can be immediately located. Otherwise, we will have to use the TreeSet above to look up next key
     * with a cost of O(log(m)) guaranteed, where m is the size of the keys.
     *
     * @param <E>
     */
    private static final class KeyBucket<E> {
        /**
         * The key associated to this queue, the least goes out first. This is for reference only, most likely won't be
         * used for any purpose.
         */
        private final int key;

        /**
         * The queue holds elements of the same key defined above. When enqueuing, new element of this key is added to
         * the end of this queue, while in dequeue an element of this key, the head of this queue is polled.
         */
        private final Queue<E> queue;

        /**
         * An internal counter to keep track of the number elements in this queue has been dequeued, and this value is
         * then used to compare with the defined throttlingRate above to determine whether it is time to throttle, once
         * it has been triggered, and a next key is dequeued, this counter is then reset.
         */
        private int polledCount;

        /**
         * A pointer to point to its previous queue, which is the least key less that this key.
         */
        private KeyBucket<E> previous;

        /**
         * A pointer to point to its next queue, which is the least key grater than this key.
         */
        private KeyBucket<E> next;

        /**
         * Default constructor, calling to this default constructor is not allowed.
         *
         * @throws IllegalArgumentException - Disable this default constructor.
         */
        private KeyBucket() throws IllegalArgumentException {
            throw new IllegalArgumentException();
        }

        /**
         * Constructs a key and queue pair with given key. It creates an empty queue.
         *
         * @param key   The key this queue associates with.
         */
        KeyBucket(final int key) {
            this.key = key;
            this.queue = new LinkedList<>();
            this.polledCount = 0;
        }

        /**
         * The constructor to initiate a key and queue pair, and the key is not yet found in the  {@link #keySet} of
         * this custom queue. This implicitly add the specified element into this internal queue.
         *
         * @param key   The key or priority of this queue. All the elements in this queue will share this same key.
         * @param e     The element to be added into this queue upon initiation.
         */
        KeyBucket(final int key, final E e) {
            this(key);
            this.add(e);
        }

        /**
         * Adds an element of the same key into this queue.
         *
         * @param e The element to be added into this queue.
         */
        void add(final E e) {
            this.queue.add(e);
        }

        /**
         * Polls the head element of this queue out, and increment the polling counter by 1.
         *
         * @return An element of type E,or null if the queue is empty.
         */
        E poll() {
            E element = this.queue.poll();

            if (element != null)
                this.polledCount += 1;

            return element;
        }

        /**
         * Fetches the head of this queue but keep the queue untouched.
         *
         * @return  The head element of this queue.
         */
        E peek() {
            return this.queue.peek();
        }

        /**
         * Internal method to return the size of this key bucket.
         *
         * @return An integer indicating the number of elements in this key queue.
         */
        int size() {
            return this.queue.size();
        }

        /**
         * Adds another KeyBucket before this one, and reestablish the relationships amongst them.
         *
         * @param newPrevious   A new previous KeyBucket, and its key must be smaller than this one.
         * @throws IllegalArgumentException - If the key in the newPrevious is not less than this key.
         */
        void addBefore(KeyBucket<E> newPrevious) throws IllegalArgumentException {
            if (this.key <= newPrevious.key)
                throw new IllegalArgumentException();

            KeyBucket<E> oldPrevious = this.previous;

            if (oldPrevious != null) {
                oldPrevious.next = newPrevious;
                newPrevious.previous = oldPrevious;
            }

            this.previous = newPrevious;
            newPrevious.next = this;
        }

        /**
         * Adds another KeyBucket right after the current one. It throws exception if the one key to be added after is
         * not greater than the current one.
         *
         * @param newNext   Another KeyBucket to be added right after the current one.
         * @throws IllegalArgumentException - If the key in the to be added KeyBucket is not greater than this key.
         */
        void addAfter(KeyBucket<E> newNext) throws IllegalArgumentException {
            if (this.key >= newNext.key)
                throw new IllegalArgumentException();

            KeyBucket<E> oldNext = this.next;

            if (oldNext != null) {
                newNext.next = oldNext;
                oldNext.previous = newNext;
            }

            this.next = newNext;
            newNext.previous = this;
        }

        /**
         * Removes the current KeyBucket from the active keyBucket list, when it is empty, so that it increases the
         * performance when looking for next key when throttling is needed.
         */
        void remove() {
            if (this.previous != null) {
                this.previous.next = next;
            }

            if (this.next != null) {
                this.next.previous = this.previous;
            }

            this.previous = null;
            this.next = null;
        }

        /**
         * Restore the polling count after throttling happened.
         */
        void resetPollingCount() {
            this.polledCount = 0;
        }
    }

    /**
     * Default constructor, with capacity of 16 and throttlingRate 2, that means this queue can hold maximum 16 elements
     * and, it triggers throttling once 2 elements of same key has been dequeued.
     */
    public CustomQueue() {
        this(16, 2);
    }

    /**
     * Construct a {@link CustomQueue} that holds elements with key for each, and throttle a key when the number of
     * elements has been dequeued reached the throttling rate, in which case an element with the least great key is
     * then dequeued. This queue must be configured with a fixed number of how much elements it can hold.
     *
     * @param capacity       The max number of elements this queue can hold.
     * @param throttlingRate The number of elements of same key had been dequeued then an element from the least
     *                       great key must be dequeued to throttle the previous key.
     */
    public CustomQueue(final int capacity, final int throttlingRate) {
        if (throttlingRate <= 0 || capacity < throttlingRate)
            throw new IllegalArgumentException();

        this.capacity = capacity;
        this.throttlingRate = throttlingRate;
        this.reentrantLock = new ReentrantLock(true);
        this.keySet = new TreeSet<>();

        //
        // These two could be initiated only when needed, but initiate them anyway for simplicity
        //
        this.bucketMap = new HashMap<>();
        this.parkedBuckedMap = new HashMap<>();
    }


    /**
     * A number indicates the current size of this queue.
     *
     * @return a number between 0 and the defined capacity.
     */
    public int size() {
        return size;
    }

    /**
     * A number indicates how large this queue can grow to.
     *
     * @return The maximum number of elements this queue can hold.
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Add an element with specific key into this queue. If the queue is full, meaning it reached its capacity, then
     * this action is blocked and an {@link CustomQueueIsFullException} is thrown.
     *
     * @param key The specific key this object is associated with.
     * @param e   The type of object this queue holds.
     * @throws CustomQueueIsFullException - if the queue is full - {@link #capacity} == {{@link #size}}.
     * @throws IllegalArgumentException   - Object to enqueue must not be null and its key must be greater than 0.
     */
    public void enqueue(int key, E e) throws CustomQueueIsFullException, IllegalArgumentException {
        if (key <= 0 || e == null) {
            throw new IllegalArgumentException();
        }

        final ReentrantLock lock = this.reentrantLock;
        lock.lock();

        try {
            if (isFull()) {
                throw new CustomQueueIsFullException();
            }

            if (this.bucketMap.containsKey(key)) {

                //
                // First try to check if this key is already in the current active key queue list; If found that means
                // only need to insert this element into this found queue, no recalculation of next polling key is
                // required.
                //
                addIntoActiveKeyQueue(key, e);
            } else if (this.parkedBuckedMap.containsKey(key)) {
                //
                // Then check if this key is already in the parked key queue list; If found that means this key was
                // previously in the active key queue list, however all the elements of such key have been dequeued and
                // this queue is now empty, however the polledCount is not 0, meaning it is still keep tracking, as a
                // result, we need to add this element into this parked queue, and bring this parked queue back into the
                // active queue list, and proceed with any related operations from doing so.
                //
                restoreParkedKeyQueue(key, e);
            } else {
                //
                // At this point, this key is neither found in the active key queue list, nor in the parked key queue
                // list, then it must be a brand new or previously deleted key, in which case it must be added into the
                // keySet first, then find its 'position' among the keys, and re-establish the sequence of its neighbour
                // keys.
                addNewKeyQueue(key, e);
            }

            //
            // Increase the queue size by 1.
            //
            this.size += 1;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Remove the head element from this queue, if one exists. If the queue is empty, then this action is blocked and an
     * {@link CustomQueueIsEmptyException} exception is thrown.
     *
     * @return An element of object in this queue, if one exists.
     * @throws CustomQueueIsEmptyException - if the queue is empty - {@link #size} == 0
     */
    public E dequeue() throws CustomQueueIsEmptyException {
        final ReentrantLock lock = this.reentrantLock;
        lock.lock();

        try {
            //
            // In case this queue is now empty, simply throw exception.
            //
            if (isEmpty()) {
                throw new CustomQueueIsEmptyException();
            }

            //
            // Before updating the previous dequeue key, first check if the current previous dequeue key is triggering
            // throttling. If it is throttling, then the current being dequeued key is the throttling itself, in other
            // words, the previous dequeue key bucket polling counter should be reset.
            //
            if (throttlingIsRequired()) {
                KeyBucket<E> previousDeququedKeyBucket;
                //
                // If previous dequeued key bucket is still active.
                //
                if (this.bucketMap.containsKey(this.previousDequeuedKey)) {
                    previousDeququedKeyBucket = this.bucketMap.get(this.previousDequeuedKey);
                    previousDeququedKeyBucket.resetPollingCount();
                }

                if (this.parkedBuckedMap.containsKey(this.previousDequeuedKey)) {
                    previousDeququedKeyBucket = this. parkedBuckedMap.get(this.previousDequeuedKey);
                    previousDeququedKeyBucket.resetPollingCount();

                    //
                    // Here to decide whether to remove this key bucket for good from the parked list if its size is 0.
                    // Remove it just to free up space, not deleting it could be fine, too.
                    //
                    if (previousDeququedKeyBucket.size() == 0) {
                        this.parkedBuckedMap.remove(this.previousDequeuedKey);
                    }
                }
            }

            //
            // Use the nextDequeueKey to return an element from such queue
            //
            KeyBucket<E> dequeueKeyBucket = this.bucketMap.get(this.nextDequeueKey);
            E e = dequeueKeyBucket.poll();

            //
            // Reduce the size of this queue by 1.
            //
            this.size -= 1;

            //
            // Update the previous dequeued key to this just dequeued key.
            //
            this.previousDequeuedKey = this.nextDequeueKey;

            calculatePollingKeyUponDequeue(dequeueKeyBucket);

            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Return but not remove the head element from this queue, if one exists. Return null if the queue is empty.
     *
     * @return An element of object in this queue, if one exists, or null.
     */
    public E peek() {
        return null;
    }


    /**
     * Internal helper method to determine whether this queue is full or not.
     *
     * @return  A boolean indicate whether this queue is full - true means this queue is full.
     */
    private boolean isFull() {
        return size == capacity;
    }

    /**
     * Internal helper method to determine whether this queue is empty or not.
     *
     * @return  A boolean indicate whether this queue is empty - true means this queue is empty.
     */
    private boolean isEmpty() {
        return size == 0;
    }

    /**
     * Adds an element of key into an active KeyQueue, in other words, since this key is currently active in this queue,
     * no recalculation is required to manipulate the throttling or next polling key.
     *
     * @param key   The specified priority for this element to be added.
     * @param e     The to be added element.
     */
    private void addIntoActiveKeyQueue(final int key, final E e) {
        this.bucketMap.get(key).add(e);
    }

    /**
     * Adds a brand new KeyQueue object into this queue, in other words, this is to add a new key into this queue.
     *
     * @param key   The associated key to this element.
     * @param e     The element to be added itself.
     */
    private void addNewKeyQueue(final int key, final E e) {
        //
        // First create a new KeyBucket object with this key and element.
        //
        KeyBucket<E> newKeyQueue = new KeyBucket<>(key, e);

        //
        // This queue is currently empty, then next dequeue key is simply this key.
        //
        if (isEmpty()) {
            this.nextDequeueKey = key;
        } else {

            //
            // Insert this new key bucket into its proper location among all the key buckets.
            //
            locateAndPlaceKeyBucket(newKeyQueue);

            //
            // Now need to recalculate what next dequeue key is.
            //
            calculatePollingKey(key);
        }

        this.keySet.add(key);
        this.bucketMap.put(key, newKeyQueue);
    }

    /**
     * For enqueuing an element of key which is parked, first put this element into this key bucket, then move this key
     * bucket from parked into active list, and update next dequeue key, if necessary.
     *
     * @param key   A key which is associated to the to be added element, this key currently is parked.
     * @param e     The element to be added itself.
     */
    private void restoreParkedKeyQueue(final int key, final E e) {
        //
        // First fetch this key bucket from parked key bucket list.
        //
        KeyBucket<E> parkedKeyBucket = this.parkedBuckedMap.get(key);

        //
        // Add this element into this key bucket.
        //
        parkedKeyBucket.add(e);

        //
        // Add this key bucket into active list map.
        //
        this.bucketMap.put(key, parkedKeyBucket);

        //
        // Next compute where this key should be place among all other keys, its location in terms of order.
        //
        locateAndPlaceKeyBucket(parkedKeyBucket);

        //
        // Add this key back into the keySet.
        //
        this.keySet.add(key);

        //
        // Now need to recalculate what next dequeue key is.
        //
        calculatePollingKey(key);
    }

    /**
     * An internal helper method to quickly place a new key bucket object into its proper place among all other key
     * buckets. The cost of doing so is O(log(m)), where m is the size of all the keys; The benefit of doing so is, when
     * dequeue, calculating next dequeue key when throttling is required, will perform in O(1).
     *
     * @param newKeyQueue   The new key bucket object to be place into among all the key buckets.
     */
    private void locateAndPlaceKeyBucket(final KeyBucket<E> newKeyQueue) {
        //
        // Next need to find where this key queue is supposed to be placed among all the key queue list, based on
        // its key. We use the keySet, and try to find a lower element from this tree set.
        //
        Integer lowerKey = this.keySet.lower(newKeyQueue.key);

        //
        // If the lowerKey is not found, then it means this to be added key is the least key, and therefore this key
        // queue should be added as the very first key queue, and this key will be the first one in the keySet.
        //
        if (lowerKey == null) {
            //
            // First find the current least key from keySet, then add this new key bucket before it.
            //
            Integer currentFirstKey = this.keySet.first();

            //
            // Then fetch its KeyBucket object, and use it to place the new key bucket before it.
            //
            KeyBucket<E> currentFirstKeyBucket = this.bucketMap.get(currentFirstKey);

            //
            // This new key bucket is now the head.
            //
            newKeyQueue.addAfter(currentFirstKeyBucket);
        } else {
            //
            // Find its least-low key bucket.
            //
            KeyBucket<E> lowerKeyBucket = this.bucketMap.get(lowerKey);

            //
            // Add this new key bucket right after the lower key bucket.
            //
            lowerKeyBucket.addAfter(newKeyQueue);
        }
    }

    /**
     * Internal helper method to calculate what next dequeue key is, this method is mainly used only in enqueue action
     * on this queue, since every time when a new element is enqueued, it may invalidate the next dequeue key which was
     * calculated before.
     *
     * @param key   The newly added key into this queue.
     */
    private void calculatePollingKey(final int key) {
        //
        // When the queue is currently empty, no need to calculate...
        //
        if (isEmpty())
            return;

        if (!throttlingIsRequired())
            //
            // When throttling is not required for next dequeue, then simply use the head of the keys as next key to
            // dequeue. The cost is O(1).
            //
            this.nextDequeueKey = this.keySet.first();
        else {
            //
            // When throttling is required, that means we know there must be a previous dequeued key k_previous, and
            // this key k_previous is triggering throttling condition on itself, and we need to find its next key,
            // ideally k_previous + 1. In the event k_previous + 1 doesn't exist, it should return next least great key
            // comparing to k_previous, the cost is O(1).
            //
            final int oldKeyPrevious = this.previousDequeuedKey;
            final int oldKeyNext = this.nextDequeueKey;

            if (oldKeyPrevious >= oldKeyNext) {
                //
                // When previous dequeued key is triggering throttling and the next dequeue key computed is actually
                // smaller than what has been dequeued, this means, the previous dequeued key is the current largest
                // key in the queue, and there's no other keys after it, that's why the next dequeue key computed is
                // 'jumping' back to the head of the keySet, in this case, 2 different scenarios should be considered:
                //

                //
                // (1) This newly enqueued key is now behind the previous dequeued key, and this new key is supposed
                // to be the x+1 key, so set the next dequeue key to this new key.
                //
                // (2) In this case, this newly added key is smaller than what to be the next dequeue key, however
                // this next dequeue key was computed by fetching the head of the keySet; Now this new key is even
                // smaller and will be the new head, then this new key is the next key.
                //
                if (key > oldKeyPrevious || key < oldKeyNext) {
                    this.nextDequeueKey = key;
                }

                //
                // When in any other cases, no change is required for the next dequeue key.
                //
            } else {
                //
                // In this scenario, the next dequeue key should be updated only when this newly inserted key is greater
                // than what just dequeued and smaller than what next key is.
                //
                if (oldKeyPrevious < key && key < oldKeyNext) {
                    this.nextDequeueKey = key;
                }
            }
        }
    }

    /**
     * Internal method to calculate next dequeue key upon dequeue an element, and if necessary, park the associated
     * key bucket if it becomes empty.
     *
     * @param dequeuedKeyBucket The key bucket which was just dequeued.
     */
    private void calculatePollingKeyUponDequeue(final KeyBucket<E> dequeuedKeyBucket) {
        //
        // First determine whether this key bucket should be parked, if it should, then first remove its key from the
        // keySet.
        //
        boolean parkKeyBucket = dequeuedKeyBucket.size() == 0;

        //
        // Remove the key from keySet, if needed. This is required before calculating next dequeue key.
        //
        if (parkKeyBucket)
            this.keySet.remove(dequeuedKeyBucket.key);

        //
        // This dequeuedKeyBucket is the most recently dequeued key bucket, then next is to check whether throttling on
        // this key is triggered.
        //
        if (throttlingIsRequired()) {
            //
            // If throttling is now required, need to find its next key bucket, and use that key as the next dequeue key
            // when it exists, or use the head of the keySet, if one exists, otherwise set it to 0
            //
            if (!isEmpty()) {
                KeyBucket<E> nextDeququeKeyBucket = Optional
                        .ofNullable(dequeuedKeyBucket.next)
                        .orElse(this.bucketMap.get(this.keySet.first()));

                this.nextDequeueKey = nextDeququeKeyBucket.key;
            } else {
                this.nextDequeueKey = 0;
            }

        } else {
            //
            // In this case, simply fetch the head of the keySet and that would be next dequeue key, if one exists,
            // otherwise set the next dequeue key to 0.
            //
            this.nextDequeueKey = isEmpty() ? 0 : this.keySet.first();
        }

        //
        // Now move this key bucket into parked list, if necessary.
        //
        if (parkKeyBucket) {
            //
            // Decouple itself from all other active key buckets.
            //
            dequeuedKeyBucket.remove();

            //
            // Move this key bucket into parked list.
            //
            this.parkedBuckedMap.put(dequeuedKeyBucket.key, dequeuedKeyBucket);

            //
            // Remove it from active key bucket list.
            //
            this.bucketMap.remove(dequeuedKeyBucket.key);
        }
    }

    /**
     * Internal helper method to determine whether it is time to throttle previous dequeued key. The logic is based on
     * what the previous dequeued key, if there's any, use that polling counter to determine; Once it is required, then
     * the counter of this previous dequeued key must be reset, after throttling happened.
     *
     * @return true to throttle and false to not throttle.
     */
    private boolean throttlingIsRequired() {
        //
        // If this previousDequeuedKey is 0, that means it has never been dequeued yet, then no throttling.
        //
        if (this.previousDequeuedKey == 0)
            return false;

        //
        // This previous dequeued key is still in active key queue list, then simply check whether the key queue polling
        // counter reached the throttling rate.
        //
        if (this.bucketMap.containsKey(this.previousDequeuedKey)) {
            return this.bucketMap.get(this.previousDequeuedKey).polledCount == this.throttlingRate;
        }

        //
        // This previous dequeued key is now in parked key queue list, then simply check whether the key queue polling
        // counter reached the throttling rate.
        //
        if (this.parkedBuckedMap.containsKey(this.previousDequeuedKey)) {
            return this.parkedBuckedMap.get(this.previousDequeuedKey).polledCount == this.throttlingRate;
        }

        // Should not reach here...
        return false;
    }

    /**
     * {@link CustomQueue} is full exception, this is thrown so that producer can catch and retry enqueuing later.
     */
    static class CustomQueueIsFullException extends Exception {

        /**
         * Error message.
         */
        private static final String MSG = "The queue is full, please try again later";

        /**
         * Construct an exception to indicate full queue.
         */
        CustomQueueIsFullException() {
            super(MSG);
        }
    }

    /**
     * {@link CustomQueue} is empty exception, so that consumer can pause consuming.
     */
    static class CustomQueueIsEmptyException extends Exception {
        /**
         * Error message.
         */
        private static final String MSG = "The queue is empty, please try again later";

        /**
         * Construct an exception to indicate the queue is empty.
         */
        CustomQueueIsEmptyException() {
            super(MSG);
        }
    }
}
