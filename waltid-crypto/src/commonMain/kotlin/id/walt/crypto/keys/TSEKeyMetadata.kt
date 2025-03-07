package id.walt.crypto.keys

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

@ExperimentalJsExport
@JsExport
data class TSEKeyMetadata(
    val server: String,
    val accessKey: String,
    val namespace: String? = null,
    val id: String? = null
)
