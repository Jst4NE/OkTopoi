package jst.oktopoi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Observable state container that provides reactive state management with optional external observation.
 *
 * E ("Element") is the foundational class for OkTopoi's state management architecture. It implements
 * MutableStateFlow to provide reactive state changes that can be observed and collected by coroutines.
 * This class serves as the base for persistence (Ep) and synchronization (Esp) extensions.
 *
 * ## Architecture
 * 
 * ```
 * E (observable state)
 *   ↓ extends
 * Ep (+ file persistence)  
 *   ↓ extends
 * Esp (+ bi-directional sync)
 * ```
 *
 * ## Key Features
 * 
 * - **Reactive state**: Implements MutableStateFlow for coroutine-based observation
 * - **Optional external observation**: Can observe another StateFlow and mirror its values
 * - **Default value support**: Provides fallback values when state is null
 * - **Thread-safe operations**: All state changes are atomic via underlying StateFlow
 * - **Extensible design**: Designed to be extended by persistence and sync layers
 *
 * ## Usage Examples
 *
 * ```kotlin
 * // Basic observable state
 * val counter = e { 0 }
 * counter.value = 42
 *
 * // Nullable state with default
 * val userName = e<String?> { "guest" }
 * userName.value = "alice"
 *
 * // Reactive collection
 * lifecycleScope.launch {
 *     counter.collect { value ->
 *         println("Counter changed to: $value")
 *     }
 * }
 *
 * // Atomic updates
 * counter.compareAndSet(42, 43)
 * userName.setIfDifferent("bob") // Only updates if different
 * ```
 *
 * ## Thread Safety
 *
 * All operations delegate to the underlying MutableStateFlow which provides:
 * - Atomic read/write operations for `value` property
 * - Thread-safe emission via `emit()` and `tryEmit()`
 * - Consistent state during concurrent access
 * - Lock-free implementation for performance
 *
 * ## Lifecycle
 *
 * E instances require `setup()` to be called for external observation initialization.
 * This is handled automatically by factory functions (`e()`, `ep()`, etc.)
 * and should not be called manually in normal usage.
 *
 * @param ValueType the type of value stored (can be nullable)
 * @param observing optional external StateFlow to observe and mirror
 * @param defaultValue factory function for initial/fallback value
 *
 * @see ep for persistent state containers
 * @see esp for synchronized state containers
 * @see Es for reactive collections
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
open class E<ValueType : Any?>(
    private val observing: StateFlow<ValueType>?,
    val defaultValue: (() -> ValueType?)?
) : MutableStateFlow<ValueType?> {

    private val state: MutableStateFlow<ValueType?> = MutableStateFlow(defaultValue?.invoke())

    /**
     * Metadata for identifying this state container in logging and persistence.
     * Set automatically by factory functions - not intended for manual use.
     */
    lateinit var callingClassName: String
    lateinit var propertyName: String

    /**
     * Initializes external observation if provided.
     * Called automatically by factory functions - should not be called manually.
     * 
     * When an `observing` StateFlow is provided, this sets up a coroutine to mirror
     * its values into this E's internal state, enabling reactive chaining.
     */
    internal open fun setup() {
        if (observing != null) {
            persistCoroutineScope.launch {
                observing.collect { state.emit(it) }
            }
        }
    }

    /**
     * The current value of this state container.
     * 
     * Reading is thread-safe and returns the most recent value immediately.
     * Writing is atomic and will notify all collectors of the change.
     * 
     * @see emit for suspend-based value setting
     * @see tryEmit for non-blocking value setting
     */
    override var value: ValueType?
        get() = state.value
        set(value) {
            state.value = value
        }

    /**
     * Atomically sets the value to `update` if the current value equals `expect`.
     * 
     * This provides thread-safe conditional updates, useful for implementing
     * lock-free algorithms and preventing race conditions.
     * 
     * @param expect the expected current value
     * @param update the new value to set
     * @return true if the update was performed, false if current value != expect
     * 
     * @sample
     * ```kotlin
     * val counter = e { 0 }
     * val success = counter.compareAndSet(0, 1) // true - value is now 1
     * val failed = counter.compareAndSet(0, 2)  // false - value is still 1
     * ```
     */
    override fun compareAndSet(expect: ValueType?, update: ValueType?): Boolean {
        return state.compareAndSet(expect, update)
    }

    override val replayCache: List<ValueType?>
        get() = state.replayCache

    override suspend fun collect(collector: FlowCollector<ValueType?>): Nothing {
        state.collect(collector)
    }

    override val subscriptionCount: StateFlow<Int>
        get() = state.subscriptionCount

    override suspend fun emit(value: ValueType?) {
        return state.emit(value)
    }

    override fun tryEmit(value: ValueType?): Boolean {
        return state.tryEmit(value)
    }

    @ExperimentalCoroutinesApi
    override fun resetReplayCache() {
        return state.resetReplayCache()
    }

    /**
     * Gets the current value. Alias for the `value` property.
     * 
     * @return the current value
     */
    fun get(): ValueType? {
        return value
    }

    /**
     * Checks if the current value is null using identity comparison.
     * 
     * @return true if value is null, false otherwise
     */
    fun isEmpty(): Boolean {
        return value === null
    }

    /**
     * Sets a new value. Equivalent to `value = newValue`.
     * 
     * This method is designed to be overridden by subclasses (Ep, Esp)
     * to add persistence or synchronization behavior.
     * 
     * @param newValue the value to set
     */
    open fun set(newValue: ValueType) {
        value = newValue
    }

    /**
     * Sets a new value only if it differs from the current value.
     * 
     * Uses both identity (===) and equality (==) comparison to determine if values differ.
     * This method is atomic and thread-safe, using compareAndSet internally.
     * 
     * @param newValue the value to set
     * @return true if the value was updated, false if it was already equal
     * 
     * @sample
     * ```kotlin
     * val name = e { "alice" }
     * name.setIfDifferent("alice") // false - no change
     * name.setIfDifferent("bob")   // true - value updated
     * ```
     */
    open fun setIfDifferent(newValue: ValueType?): Boolean {
        var currentValue: ValueType?
        do {
            currentValue = value
            if (currentValue === newValue || currentValue == newValue) {
                return false
            }
        } while (!compareAndSet(currentValue, newValue))
        return true
    }

    /**
     * Sets the value to null.
     * 
     * This method is designed to be overridden by subclasses to add
     * persistence or synchronization behavior for clear operations.
     */
    open fun clear() {
        value = null
    }

    override fun toString(): String {
        return value.toString()
    }

}