package id.walt.crypto.utils

import id.walt.crypto.keys.KeyType
import id.walt.crypto.utils.Base64Utils.base64UrlToBase64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

@ExperimentalJsExport
@JsExport
object JwsUtils {

    fun KeyType.jwsAlg() = when (this) {
        KeyType.Ed25519 -> "EdDSA"
        KeyType.secp256r1 -> "ES256"
        KeyType.secp256k1 -> "ES256K"
        KeyType.RSA -> "RS256" // TODO: RS384 RS512
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun String.decodeJwsPart(): JsonObject =
        Json.parseToJsonElement(Base64.decode(this.base64UrlToBase64()).decodeToString()).jsonObject

    data class JwsParts(val header: JsonObject, val payload: JsonObject, val signature: String)

    fun String.decodeJws(withSignature: Boolean = false): JwsParts {
        check(startsWith("ey")) { "String does not look like JWS: $this" }
        check(count { it == '.' } == 2) { "String does not have JWS part amount of 3 (= 2 dots): $this" }

        val splitted = split(".")
        val header = runCatching { splitted[0].decodeJwsPart() }.getOrElse { ex ->
            throw IllegalArgumentException("Could not parse JWT header (base64/json issue): ${splitted[0]}", ex)
        }
        val payload = runCatching { splitted[1].decodeJwsPart() }.getOrElse { ex ->
            throw IllegalArgumentException("Could not parse JWT payload (base64/json issue): ${splitted[1]}", ex)
        }
        val signature = if (withSignature) splitted[2] else ""

        return JwsParts(header, payload, signature)
    }

}
