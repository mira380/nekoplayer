package com.nekoplayer.app.data

import java.io.InputStream

internal fun interface NativeTermBankBatchConsumer {
    @Suppress("unused")
    fun onBatch(
        expressions: Array<String>,
        readings: Array<String>,
        scores: IntArray,
        rawRows: Array<String>,
    )
}

internal object NativeYomitanTermBank {
    init {
        System.loadLibrary("nekoplayer_importer")
    }

    external fun parseTermBank(
        input: InputStream,
        consumer: NativeTermBankBatchConsumer,
    ): Int
}
