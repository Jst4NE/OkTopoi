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
     * Users can handle larger buffers via .buffer() on their flow chain:
     * collection.changes.buffer(10000).collect { ... }
     */
    const val DEFAULT_CHANGE_FLOW_BUFFER_SIZE = 2048
    
    /**
     * Default replay cache size for change flows.
     * Set to 0 to avoid replaying old events to new subscribers.
     */
    const val DEFAULT_CHANGE_FLOW_REPLAY_SIZE = 0
    
    /**
     * Default sync interval for Esp/Esps periodic synchronization.
     * Syncing occurs on data changes AND at this periodic interval.
     */
    val DEFAULT_SYNC_INTERVAL: Duration = 5.minutes
    
    /**
     * Maximum number of yield() attempts in ReadWriteLock before suspending.
     * Optimizes for short read operations by yielding first, then suspending for longer waits.
     */
    const val READ_WRITE_LOCK_MAX_YIELD_ATTEMPTS = 5
}