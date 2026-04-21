package jst.oktopoi

import androidx.compose.runtime.Composer

/**
 * OkTopoi compiler-plugin runtime support.
 *
 * Symbols in this file are referenced from code emitted by the OkTopoi compiler plugin.
 * They are not part of the public API — do not call them directly.
 */

/**
 * Walks [keys] and ORs the result of `composer.changed(element)` per element into [seed].
 *
 * Emitted by the OkTopoi compiler plugin when a `@WrapInRemember` lambda captures a `vararg`
 * array parameter from its enclosing composable. The array's synthesized identity changes
 * every recomposition, so keying on the reference directly would invalidate the cache on
 * every frame; element-wise `changed()` calls catch actual element churn instead.
 *
 * Matches the pattern used by Compose's own `remember(vararg keys: Any?)` — each
 * `Composer.changed` call is slot-consuming, and the surrounding group handles variable
 * slot counts positionally, so calling this from a non-composable context is safe as long
 * as the caller is inside composition (which the plugin guarantees).
 */
@Suppress("FunctionName")
fun _oktopoiArrayChangedOr(composer: Composer, keys: Array<out Any?>, seed: Boolean): Boolean {
    var invalid = seed
    for (k in keys) invalid = invalid or composer.changed(k)
    return invalid
}
