package id.walt.credentials.verification

import id.walt.credentials.verification.policies.JsonSchemaPolicy
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.time.Duration

@ExperimentalJsExport
@JsExport
@Serializable
sealed class SerializableRuntimeException(override val message: String? = null) : RuntimeException(message)
@ExperimentalJsExport
@JsExport
@Serializable
@SerialName("JsonSchemaVerificationException")
data class JsonSchemaVerificationException(val validationErrors: List<JsonSchemaPolicy.SerializableValidationError>) :
    SerializableRuntimeException()
@ExperimentalJsExport
@JsExport
@Serializable
@SerialName("NotBeforePolicyException")
data class NotBeforePolicyException(
    val date: Instant,
    @SerialName("date_seconds")
    val dateSeconds: Long,

    @SerialName("available_in")
    val availableIn: Duration,
    @SerialName("available_in_seconds")
    val availableInSeconds: Long,
    val key: String,
    @SerialName("policy_available")
    val policyAvailable: Boolean = true
) :
    SerializableRuntimeException()
@ExperimentalJsExport
@JsExport
@Serializable
@SerialName("ExpirationDatePolicyException")
data class ExpirationDatePolicyException(
    val date: Instant,
    @SerialName("date_seconds")
    val dateSeconds: Long,

    @SerialName("expired_in")
    val expiredSince: Duration,
    @SerialName("expired_in_seconds")
    val expiredSinceSeconds: Long,
    val key: String,
    @SerialName("policy_available")
    val policyAvailable: Boolean = true
) :
    SerializableRuntimeException()
@ExperimentalJsExport
@JsExport
@Serializable
@SerialName("WebhookPolicyException")
data class WebhookPolicyException(
    val response: JsonObject
) :
    SerializableRuntimeException()
@ExperimentalJsExport
@JsExport
@Serializable
@SerialName("PresentationDefinitionException")
class PresentationDefinitionException(
    val missingCredentialTypes: List<String>
) : SerializableRuntimeException()
@ExperimentalJsExport
@JsExport
@Serializable
@SerialName("MinimumCredentialsException")
class MinimumCredentialsException(
    val total: Int,
    val missing: Int
) : SerializableRuntimeException()
@ExperimentalJsExport
@JsExport
@Serializable
@SerialName("MaximumCredentialsException")
class MaximumCredentialsException(
    val total: Int,
    val exceeded: Int
) : SerializableRuntimeException()
@ExperimentalJsExport
@JsExport
@Serializable
@SerialName("HolderBindingException")
class HolderBindingException(
    val presenterDid: String,
    val credentialDids: List<String>
) : SerializableRuntimeException()
@ExperimentalJsExport
@JsExport
@Serializable
@SerialName("NotAllowedIssuerException")
class NotAllowedIssuerException(
    val issuer: String,
    val allowedIssuers: List<String>
) : SerializableRuntimeException()
