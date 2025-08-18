package jst.oktopoi

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

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
     * Default replay cache size for change flows.
     * Set to 0 to avoid replaying old events to new subscribers.
     * 
     * A replay cache of 0 means new subscribers only receive future changes,
     * not historical ones. This prevents unexpected state synchronization issues
     * when components subscribe at different times.
     * 
     * ```kotlin
     * // With replay = 0 (default):
     * val collection = es<String, User> { it.id }
     * collection["alice"] = alice  // Event 1
     * 
     * lifecycleScope.launch {
     *     collection.changes.collect { change ->
     *         // This subscriber only sees events from this point forward
     *         // Event 1 above is NOT replayed
     *     }
     * }
     * ```
     */
    const val DEFAULT_CHANGE_FLOW_REPLAY_SIZE = 0
    
    /**
     * Default sync interval for Esp/Esps periodic synchronization.
     * Syncing occurs on data changes AND at this periodic interval.
     * 
     * This interval ensures that unsynced changes are eventually synchronized
     * even if immediate sync attempts fail. The sync system uses both:
     * - Immediate sync on data changes (for low latency)
     * - Periodic sync at this interval (for reliability)
     * 
     * ```kotlin
     * // Sync behavior example:
     * val syncedData = esp<UserSettings>(
     *     defaultValue = { UserSettings.default() },
     *     incomingSync = incomingChanges,
     *     outgoingSync = { key, value, timestamp ->
     *         sendToRemote(key, value, timestamp)
     *     }
     * )
     * 
     * syncedData.value = newSettings  // Immediate sync attempt
     * // + Periodic sync every 5 minutes as backup
     * ```
     * 
     * Adjust this value based on your sync requirements:
     * - Lower values: More frequent sync, higher network/CPU usage
     * - Higher values: Less frequent sync, potential for data staleness
     */
    val DEFAULT_SYNC_INTERVAL: Duration = 5.minutes
    
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