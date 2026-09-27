package jst.oktopoi

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Constants used throughout the OkTopoi library.
 * Centralizes magic numbers for better maintainability and configuration.
 */
object OkTopoiConstants {
    
    /**
     * Default buffer capacity for change flows in Es collections.
     * This buffer holds change events for reactive consumers.
     * 
     * When the buffer overflows, an exception is thrown to prevent memory issues.
     * For high-throughput scenarios or slow consumers, increase buffer size on consumer side:
     * 
     * ```kotlin
     * // Add larger buffer capacity when collecting changes
     * collection.changes.buffer(10000).collect { change ->
     *     // Process change events with larger buffer
     * }
     * 
     * // Or use conflated buffer to keep only latest changes
     * collection.changes.conflate().collect { change ->
     *     // Always get most recent change, drops intermediates
     * }
     * ```
     */
    const val DEFAULT_CHANGE_FLOW_BUFFER_SIZE = 2048
    
    /**
     * Default outbound sync cadence for Esp/Esps (their `syncInterval` parameter).
     *
     * - `Duration.ZERO` (the default): sync on every local change only — no timer. A write that
     *   fails with a retryable error waits for the next change or for `syncActive` to turn on again.
     * - `> 0`: sync on every change, plus a periodic retry at this interval.
     * - `Duration.INFINITE`: outbound sync disabled entirely.
     */
    val DEFAULT_SYNC_INTERVAL: Duration = Duration.ZERO
    
    /**
     * Maximum number of yield() attempts in ReadWriteLock before suspending.
     * Optimizes for short read operations by yielding first, then suspending for longer waits.
     * 
     * This optimization reduces latency when readers complete quickly by yielding control
     * briefly instead of immediately suspending. The strategy:
     * 
     * 1. Writer attempts to acquire lock
     * 2. If readers are active, yield up to this many times
     * 3. If readers still active after yields, suspend until notified
     * 
     * ```kotlin
     * // ReadWriteLock behavior:
     * lock.withWriteLock {
     *     // Writer waits for active readers
     *     // Yields 5 times (fast), then suspends (if needed)
     * }
     * ```
     * 
     * Tuning guidelines:
     * - Higher values: Better for very short read operations, more CPU spinning
     * - Lower values: Better for longer read operations, less CPU usage
     * - 0: Always suspend immediately (no yielding optimization)
     * 
     * @see ReadWriteLock.withWriteLock for usage
     */
    const val READ_WRITE_LOCK_MAX_YIELD_ATTEMPTS = 5
}