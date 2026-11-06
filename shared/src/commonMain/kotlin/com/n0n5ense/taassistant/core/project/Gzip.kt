package com.n0n5ense.taassistant.core.project

/** gzip で圧縮する */
internal expect fun gzip(bytes: ByteArray): ByteArray

/** gzip を展開する。gzip として壊れていれば例外を投げる */
internal expect fun gunzip(bytes: ByteArray): ByteArray

/** 先頭が gzip のマジックナンバー (1f 8b) か */
internal fun ByteArray.isGzip(): Boolean = size >= 2 && this[0] == 0x1f.toByte() && this[1] == 0x8b.toByte()
