package jst.oktopoi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch


@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
open class E<ValueType : Any?>(
    private val observing: StateFlow<ValueType>?,
    val defaultValue: (() -> ValueType?)?
) : MutableStateFlow<ValueType?> {

    private val state: MutableStateFlow<ValueType?> = MutableStateFlow(defaultValue?.invoke())

    lateinit var callingClassName: String
    lateinit var propertyName: String

    internal open fun setup() {
        if (observing != null) {
            persistCoroutineScope.launch {
                observing.collect { state.emit(it) }
            }
        }
    }

    override var value: ValueType?
        get() = state.value
        set(value) {
            state.value = value
        }

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

    fun get(): ValueType? {
        return value
    }

    fun isEmpty(): Boolean {
        return value === null
    }

    open fun set(newValue: ValueType) {
        value = newValue
    }

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

    open fun clear() {
        value = null
    }

    override fun toString(): String {
        return value.toString()
    }

}