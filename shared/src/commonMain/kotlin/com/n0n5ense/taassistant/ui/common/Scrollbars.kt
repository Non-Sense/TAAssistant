package com.n0n5ense.taassistant.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 縦スクロールバー (スクロールできないときは表示しない) */
@Composable
internal expect fun AppVerticalScrollbar(state: ScrollState, modifier: Modifier = Modifier)

/** 縦スクロールバー (LazyColumn 用) */
@Composable
internal expect fun AppVerticalScrollbar(state: LazyListState, modifier: Modifier = Modifier)

/** 横スクロールバー */
@Composable
internal expect fun AppHorizontalScrollbar(state: ScrollState, modifier: Modifier = Modifier)
