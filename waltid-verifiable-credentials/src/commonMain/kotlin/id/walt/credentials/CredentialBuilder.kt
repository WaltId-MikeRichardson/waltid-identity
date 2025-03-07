package id.walt.credentials

import id.walt.credentials.CredentialBuilderType.W3CV11CredentialBuilder
import id.walt.credentials.CredentialBuilderType.W3CV2CredentialBuilder
import id.walt.credentials.vc.vcs.W3CBaseDataModels
import id.walt.credentials.vc.vcs.W3CV11DataModel
import id.walt.credentials.vc.vcs.W3CV2DataModel
import id.walt.credentials.vc.vcs.W3CVC
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.uuid.UUID
import kotlinx.uuid.generateUUID
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.js.JsName
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
@ExperimentalJsExport
@JsExport
enum class CredentialBuilderType {
    /** W3C Verifiable Credential version 1.1 */
    W3CV11CredentialBuilder,

    /** W3C Verifiable Credential version 2.0 */
    W3CV2CredentialBuilder,

    MdocsCredentialBuilder // TODO
}
@ExperimentalJsExport
@JsExport
class CredentialBuilder(
    val builderType: CredentialBuilderType = W3CV2CredentialBuilder
) {
    fun getDefaultBuilderContext() = when (builderType) {
        W3CV11CredentialBuilder -> W3CV11DataModel.defaultContext
        W3CV2CredentialBuilder -> W3CV2DataModel.defaultContext
        else -> throw NotImplementedError("Not yet implemented: Default context for builder $builderType")
    }

    var context: List<String> = getDefaultBuilderContext()
    var type: List<String> = listOf("VerifiableCredential")
    fun addType(addType: String) {
        type = type.toMutableList().apply { add(addType) }
    }

    fun addContext(addContext: String) {
        context = context.toMutableList().apply { add(addContext) }
    }

    var credentialId: String? = "urn:uuid:${UUID.generateUUID()}"
    fun randomCredentialSubjectUUID() {
        credentialId = "urn:uuid:${UUID.generateUUID()}"
    }

    var issuerDid: String? = null
    var subjectDid: String? = null
    var validFrom: Instant? = Clock.System.now()
    fun validFromNow() {
        validFrom = Clock.System.now()
    }

    var validUntil: Instant? = null
    fun validFor(duration: Duration) {
        validUntil = Clock.System.now() + duration
    }

    var credentialStatus: W3CBaseDataModels.CredentialStatus? = null

    fun useStatusList2021Revocation(statusListCredential: String, listIndex: Int) {
        credentialStatus = W3CBaseDataModels.CredentialStatus(
            id = "$statusListCredential#$listIndex",
            type = "StatusList2021Entry",
            statusPurpose = "revocation",
            statusListIndex = listIndex.toString(),
            statusListCredential = statusListCredential //
        )
    }

    var termsOfUse: W3CBaseDataModels.TermsOfUse? = null

    var _customCredentialSubjectData: JsonObject? = null
    fun useCredentialSubject(data: JsonObject) {
        _customCredentialSubjectData = data
    }

    var _extraCustomData: MutableMap<String, JsonElement> = HashMap()
    fun useData(key: String, data: JsonElement) {
        _extraCustomData[key] = data
    }

    @JsName("useDataPair")
    fun useData(pair: Pair<String, JsonElement>) = useData(pair.first, pair.second)

    infix fun String.set(data: JsonElement) {
        useData(this, data)
    }


    fun buildW3CSubject() = _customCredentialSubjectData?.let {
        JsonObject(
            _customCredentialSubjectData!!.toMutableMap()
                .apply {
                    subjectDid?.let {
                        put("id", JsonPrimitive(subjectDid))
                    }
                })
    }


    /** W3C Verifiable Credential version 2.0 */
    fun buildW3CV2DataModel() = W3CV2DataModel(
        context = context,
        type = type,
        credentialSubject = buildW3CSubject() ?: JsonObject(mapOf("id" to JsonPrimitive(subjectDid))),
        id = credentialId,
        issuer = issuerDid,
        validFrom = validFrom?.toString(),
        validUntil = validUntil?.toString(),
        credentialStatus = credentialStatus,
        termsOfUse = termsOfUse
    )

    /** W3C Verifiable Credential version 1.1 */
    fun buildW3CV11DataModel() = W3CV11DataModel(
        context = context,
        type = type,
        credentialSubject = buildW3CSubject() ?: JsonObject(mapOf("id" to JsonPrimitive(subjectDid))),
        id = credentialId,
        issuer = issuerDid,
        issuanceDate = validFrom?.toString(),
        expirationDate = validUntil?.toString(),
        credentialStatus = credentialStatus,
        termsOfUse = termsOfUse
    )

    fun buildW3C(): W3CVC {
        return W3CVC(
            when (builderType) {
                W3CV2CredentialBuilder -> buildW3CV2DataModel()
                W3CV11CredentialBuilder -> buildW3CV11DataModel()
                else -> throw NotImplementedError("Not yet implemented: Builder Type $builderType")
            }.encodeToJsonObject().toMutableMap()
                .apply {
                    putAll(_extraCustomData)
                }
        )
    }
}
