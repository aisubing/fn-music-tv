// SPDX-License-Identifier: GPL-3.0-only
package com.fnmusic.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.yield

/**
 * One-shot TV focus restore: resolves the previously focused key against the keys that are
 * currently present in composition, adjusts [focusedKey] when the retained target is gone, and
 * then retries requesting focus for a few frames while the target row attaches its requester.
 * Call [reset] when the underlying content is replaced (for example a content revision bump).
 */
internal class FocusRestoreRequester {
    internal var requested by mutableStateOf(false)

    fun reset() {
        requested = false
    }
}

@Composable
internal fun rememberFocusRestoreRequester(stateKey: Any? = null): FocusRestoreRequester =
    remember(stateKey) { FocusRestoreRequester() }

@Composable
internal fun FocusRestoreEffect(
    vararg effectKeys: Any?,
    restore: FocusRestoreRequester = rememberFocusRestoreRequester(),
    enabled: Boolean = true,
    focusedKey: String?,
    onResolvedKey: (String?) -> Unit,
    requester: FocusRequester,
    availableKeys: () -> List<String>,
) {
    LaunchedEffect(effectKeys.toList(), focusedKey, enabled) {
        if (!enabled || restore.requested) return@LaunchedEffect
        val keys = availableKeys()
        // No candidates yet (content still loading): leave the retained key untouched so the
        // target can still be honored once candidates arrive.
        if (keys.isEmpty()) return@LaunchedEffect
        val target = focusedKey?.takeIf(keys::contains) ?: keys.firstOrNull()
        if (focusedKey != target) {
            onResolvedKey(target)
            return@LaunchedEffect
        }
        if (target != null) {
            yield()
            repeat(3) {
                withFrameNanos { }
                if (runCatching { requester.requestFocus() }.getOrDefault(false)) {
                    restore.requested = true
                    return@LaunchedEffect
                }
            }
        }
    }
}
