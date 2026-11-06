package com.n0n5ense.taassistant.ui.common

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** テーマの色に合わせたスクロールバー */
@Composable
private fun scrollbarStyle(): ScrollbarStyle {
    val color = MaterialTheme.colorScheme.onSurface
    return defaultScrollbarStyle().copy(
        thickness = 8.dp,
        shape = RoundedCornerShape(4.dp),
        unhoverColor = color.copy(alpha = 0.20f),
        hoverColor = color.copy(alpha = 0.45f),
    )
}

@Composable
internal actual fun AppVerticalScrollbar(state: ScrollState, modifier: Modifier) {
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier.padding2(), style = scrollbarStyle())
}

@Composable
internal actual fun AppVerticalScrollbar(state: LazyListState, modifier: Modifier) {
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier.padding2(), style = scrollbarStyle())
}

@Composable
internal actual fun AppHorizontalScrollbar(state: ScrollState, modifier: Modifier) {
    HorizontalScrollbar(rememberScrollbarAdapter(state), modifier.padding2(), style = scrollbarStyle())
}

/** 端に少し余白をあける */
private fun Modifier.padding2(): Modifier = this.then(Modifier.padding(2.dp))
