package id.walt.webwallet.service

import id.walt.crypto.keys.Key
import id.walt.crypto.keys.KeySerialization
import id.walt.crypto.keys.KeyType
import id.walt.crypto.keys.LocalKey
import id.walt.crypto.utils.JwsUtils
import id.walt.crypto.utils.JwsUtils.decodeJws
import id.walt.did.dids.DidService
import id.walt.did.dids.registrar.LocalRegistrar
import id.walt.did.dids.registrar.dids.DidCheqdCreateOptions
import id.walt.did.dids.registrar.dids.DidJwkCreateOptions
import id.walt.did.dids.registrar.dids.DidKeyCreateOptions
import id.walt.did.dids.registrar.dids.DidWebCreateOptions
import id.walt.did.dids.resolver.LocalResolver
import id.walt.did.utils.EnumUtils.enumValueIgnoreCase
import id.walt.oid4vc.data.CredentialFormat
import id.walt.oid4vc.data.GrantType
import id.walt.oid4vc.data.OpenIDProviderMetadata
import id.walt.oid4vc.data.dif.PresentationDefinition
import id.walt.oid4vc.errors.AuthorizationError
import id.walt.oid4vc.providers.CredentialWalletConfig
import id.walt.oid4vc.providers.OpenIDClientConfig
import id.walt.oid4vc.providers.TokenTarget
import id.walt.oid4vc.requests.*
import id.walt.oid4vc.responses.AuthorizationErrorCode
import id.walt.oid4vc.responses.BatchCredentialResponse
import id.walt.oid4vc.responses.CredentialResponse
import id.walt.oid4vc.responses.TokenResponse
import id.walt.oid4vc.util.randomUUID
import id.walt.webwallet.db.models.WalletCategoryData
import id.walt.webwallet.db.models.WalletCredential
import id.walt.webwallet.db.models.WalletOperationHistories
import id.walt.webwallet.db.models.WalletOperationHistory
import id.walt.webwallet.manifest.extractor.EntraManifestExtractor
import id.walt.webwallet.service.category.CategoryService
import id.walt.webwallet.service.credentials.CredentialFilterObject
import id.walt.webwallet.service.credentials.CredentialsService
import id.walt.webwallet.service.dids.DidsService
import id.walt.webwallet.service.dto.LinkedWalletDataTransferObject
import id.walt.webwallet.service.dto.WalletDataTransferObject
import id.walt.webwallet.service.events.*
import id.walt.webwallet.service.issuers.IssuerDataTransferObject
import id.walt.webwallet.service.issuers.IssuersService
import id.walt.webwallet.service.keys.KeysService
import id.walt.webwallet.service.oidc4vc.TestCredentialWallet
import id.walt.webwallet.service.report.ReportRequestParameter
import id.walt.webwallet.service.report.ReportService
import id.walt.webwallet.service.settings.SettingsService
import id.walt.webwallet.service.settings.WalletSetting
import id.walt.webwallet.trustusecase.TrustStatus
import id.walt.webwallet.trustusecase.TrustValidationUseCase
import id.walt.webwallet.web.controllers.PresentationRequestParameter
import id.walt.webwallet.web.parameter.CredentialRequestParameter
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.toJavaInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.uuid.UUID
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.net.URLDecoder
import kotlin.time.Duration.Companion.seconds


class SSIKit2WalletService(
    tenant: String,
    accountId: UUID,
    walletId: UUID,
    private val categoryService: CategoryService,
    private val trustUseCase: TrustValidationUseCase,
    private val settingsService: SettingsService,
) : WalletService(tenant, accountId, walletId) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        init {
            runBlocking {
                //WaltidServices.init()
                DidService.apply {
                    registerResolver(LocalResolver())
                    updateResolversForMethods()
                    registerRegistrar(LocalRegistrar())
                    updateRegistrarsForMethods()
                }
            }
        }
    }

    override fun listCredentials(filter: CredentialFilterObject): List<WalletCredential> =
        CredentialsService.list(walletId, filter)

    override suspend fun listRawCredentials(): List<String> =
        CredentialsService.list(walletId, CredentialFilterObject.default).map {
            it.document
        }

    override suspend fun deleteCredential(id: String, permanent: Boolean) = let {
        CredentialsService.get(walletId, id)?.run {
            logEvent(
                action = EventType.Credential.Delete,
                originator = "wallet",
                data = createCredentialEventData(this, null),
                credentialId = this.id
            )
        }
        CredentialsService.delete(walletId, id, permanent)
    }

    override suspend fun restoreCredential(id: String): WalletCredential =
        CredentialsService.restore(walletId, id) ?: error("Credential not found: $id")

    override suspend fun getCredential(credentialId: String): WalletCredential =
        CredentialsService.get(walletId, credentialId)
            ?: throw IllegalArgumentException("WalletCredential not found for credentialId: $credentialId")

    override suspend fun attachCategory(credentialId: String, category: String): Boolean =
        categoryService.get(walletId, category)?.let {// validation should be part of schema
            CredentialsService.Category.add(walletId, credentialId, it.name) == 1
        } ?: throw IllegalArgumentException("Category not found for wallet: $category")

    override suspend fun detachCategory(credentialId: String, category: String): Boolean =
        CredentialsService.Category.delete(walletId, credentialId, category) == 1

    override suspend fun acceptCredential(parameter: CredentialRequestParameter): Boolean =
        CredentialsService.get(walletId, parameter.credentialId)?.takeIf { it.deletedOn == null }?.let {
            CredentialsService.setPending(walletId, parameter.credentialId, false) > 0
        } ?: error("Credential not found: ${parameter.credentialId}")

    override suspend fun rejectCredential(parameter: CredentialRequestParameter): Boolean =
        CredentialsService.delete(walletId, parameter.credentialId, true)

    override fun matchCredentialsByPresentationDefinition(presentationDefinition: PresentationDefinition): List<WalletCredential> {
        val credentialList = listCredentials(CredentialFilterObject.default)

        logger.debug("WalletCredential list is: {}", credentialList.map { it.parsedDocument?.get("type")!!.jsonArray })

        data class TypeFilter(val path: String, val type: String? = null, val pattern: String)

        val filters = presentationDefinition.inputDescriptors.mapNotNull { inputDescriptor ->
            inputDescriptor.constraints?.fields?.filter { field -> field.path.any { path -> path.contains("type") } }
                ?.map {
                    val path = it.path.first().removePrefix("$.")
                    val filterType = it.filter?.get("type")?.jsonPrimitive?.content
                    val filterPattern = it.filter?.get("pattern")?.jsonPrimitive?.content
                        ?: throw IllegalArgumentException("No filter pattern in presentation definition constraint")

                    TypeFilter(path, filterType, filterPattern)
                }?.plus(
                inputDescriptor.schema?.map { schema ->
                    TypeFilter("type", "string", schema.uri)
                } ?: listOf()
            )
        }

        logger.debug("Using filters: {}", filters)

        val matchedCredentials = when {
            filters.isNotEmpty() -> credentialList.filter { credential ->
                filters.any { fields ->
                    fields.all { typeFilter ->
                        val credField = credential.parsedDocument!![typeFilter.path] ?: return@all false

                        when (credField) {
                            is JsonPrimitive -> credField.jsonPrimitive.content == typeFilter.pattern
                            is JsonArray -> credField.jsonArray.last().jsonPrimitive.content == typeFilter.pattern
                            else -> false
                        }
                    }
                }
            }

            else -> credentialList.filter { cred ->
                presentationDefinition.inputDescriptors.any { desc -> desc.name == cred.parsedDocument?.get("type")?.jsonArray?.last()?.jsonPrimitive?.content }
            }
        }


        logger.debug("Matched credentials: {}", matchedCredentials)

        return matchedCredentials.ifEmpty { credentialList }
    }

    private fun getQueryParams(url: String): Map<String, MutableList<String>> {
        val params: MutableMap<String, MutableList<String>> = HashMap()
        val urlParts = url.split("\\?".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

        if (urlParts.size <= 1)
            return params

        val query = urlParts[1]
        for (param in query.split("&".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
            val pair = param.split("=".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            val key = URLDecoder.decode(pair[0], "UTF-8")
            var value = ""
            if (pair.size > 1) {
                value = URLDecoder.decode(pair[1], "UTF-8")
            }
            var values = params[key]
            if (values == null) {
                values = ArrayList()
                params[key] = values
            }
            values.add(value)
        }
        return params
    }


    /* SIOP */
    @Serializable
    data class PresentationResponse(
        val vp_token: String,
        val presentation_submission: String,
        val id_token: String?,
        val state: String?,
        val fulfilled: Boolean,
        val rp_response: String?
    )

    @Serializable
    data class SIOPv2Response(
        val vp_token: String,
        val presentation_submission: String,
        val id_token: String?,
        val state: String?
    )

    private val ktorClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
        followRedirects = false
    }


    data class PresentationError(
        override val message: String,
        val redirectUri: String?
    ) : IllegalArgumentException(message)


    /**
     * @return redirect uri
     */
    override suspend fun usePresentationRequest(parameter: PresentationRequestParameter): Result<String?> {
        val credentialWallet = getCredentialWallet(parameter.did)

        val authReq = AuthorizationRequest.fromHttpParametersAuto(parseQueryString(Url(parameter.request).encodedQuery).toMap())
        logger.debug("Auth req: {}", authReq)

        logger.debug("USING PRESENTATION REQUEST, SELECTED CREDENTIALS: {}", parameter.selectedCredentials)

        SessionAttributes.HACK_outsideMappedSelectedCredentialsPerSession[authReq.state + authReq.presentationDefinition] =
            parameter.selectedCredentials
        if (parameter.disclosures != null) {
            SessionAttributes.HACK_outsideMappedSelectedDisclosuresPerSession[authReq.state + authReq.presentationDefinition] =
                parameter.disclosures
        }

        val presentationSession =
            credentialWallet.initializeAuthorization(authReq, 60.seconds, parameter.selectedCredentials.toSet())
        logger.debug("Initialized authorization (VPPresentationSession): {}", presentationSession)

        logger.debug("Resolved presentation definition: ${presentationSession.authorizationRequest!!.presentationDefinition!!.toJSONString()}")

        val tokenResponse = credentialWallet.processImplicitFlowAuthorization(presentationSession.authorizationRequest)
        val resp = ktorClient.submitForm(
            presentationSession.authorizationRequest.responseUri ?: presentationSession.authorizationRequest.redirectUri
            ?: throw AuthorizationError(
                presentationSession.authorizationRequest,
                AuthorizationErrorCode.invalid_request,
                "No response_uri or redirect_uri found on authorization request"
            ),
            parameters {
                tokenResponse.toHttpParameters().forEach { entry ->
                    entry.value.forEach { append(entry.key, it) }
                }
            })
        val httpResponseBody = runCatching { resp.bodyAsText() }.getOrNull()
        val isResponseRedirectUrl =
            httpResponseBody != null && httpResponseBody.take(10).lowercase().let {
                @Suppress("HttpUrlsUsage")
                it.startsWith("http://") || it.startsWith("https://")
            }
        logger.debug("HTTP Response: {}, body: {}", resp, httpResponseBody)
        parameter.selectedCredentials.forEach {
            CredentialsService.get(walletId, it)?.run {
                logEvent(
                    action = EventType.Credential.Present,
                    originator = presentationSession.presentationDefinition?.name ?: EventDataNotAvailable,
                    data = createCredentialEventData(this, null),
                    credentialId = this.id,
                    note = parameter.note,
                )
            }
        }

        return if (resp.status.isSuccess()) {
            Result.success(if (isResponseRedirectUrl) httpResponseBody else null)
        } else {
            if (isResponseRedirectUrl) {
                Result.failure(
                    PresentationError(
                        message = "Presentation failed - redirecting to error page",
                        redirectUri = httpResponseBody
                    )
                )
            } else {
                logger.debug("Response body: $httpResponseBody")
                Result.failure(
                    PresentationError(
                        message = if (httpResponseBody != null) "Presentation failed:\n $httpResponseBody" else "Presentation failed",
                        redirectUri = ""
                    )
                )
            }
        }
    }

    override suspend fun resolvePresentationRequest(request: String): String {
        val credentialWallet = getAnyCredentialWallet()

        return Url(request).protocolWithAuthority.plus("?")
            .plus(credentialWallet.parsePresentationRequest(request).toHttpQueryString())
    }


    private val credentialWallets = HashMap<String, TestCredentialWallet>()

    private fun getCredentialWallet(did: String) = credentialWallets.getOrPut(did) {
        TestCredentialWallet(
            CredentialWalletConfig("http://blank"),
            this,
            did
        )
    }

    private fun getAnyCredentialWallet() =
        credentialWallets.values.firstOrNull() ?: getCredentialWallet("did:test:test")

    private val testCIClientConfig = OpenIDClientConfig("test-client", null, redirectUri = "http://blank")

    val http = HttpClient(Java) {
        install(ContentNegotiation) {
            json()
        }
        install(Logging) {
            logger = Logger.SIMPLE
            level = LogLevel.ALL
        }
        followRedirects = false
    }

    private suspend fun processCredentialOfferRequest(
        credentialOfferRequest: CredentialOfferRequest,
        credentialWallet: TestCredentialWallet
    ): List<CredentialResponse> {
        logger.debug("// get issuer metadata")
        val providerMetadataUri =
            credentialWallet.getCIProviderMetadataUrl(credentialOfferRequest.credentialOffer!!.credentialIssuer)
        logger.debug("Getting provider metadata from: $providerMetadataUri")
        val providerMetadataResult = ktorClient.get(providerMetadataUri)
        logger.debug("Provider metadata returned: " + providerMetadataResult.bodyAsText())

        val providerMetadata = providerMetadataResult.body<JsonObject>().let { OpenIDProviderMetadata.fromJSON(it) }
        logger.debug("providerMetadata: {}", providerMetadata)

        logger.debug("// resolve offered credentials")
        val offeredCredentials = credentialOfferRequest.credentialOffer!!.resolveOfferedCredentials(providerMetadata)
        logger.debug("offeredCredentials: {}", offeredCredentials)

        //val offeredCredential = offeredCredentials.first()
        //logger.debug("offeredCredentials[0]: $offeredCredential")

        logger.debug("// fetch access token using pre-authorized code (skipping authorization step)")
        val tokenReq = TokenRequest(
            grantType = GrantType.pre_authorized_code,
            clientId = testCIClientConfig.clientID,
            redirectUri = credentialWallet.config.redirectUri,
            preAuthorizedCode = credentialOfferRequest.credentialOffer!!.grants[GrantType.pre_authorized_code.value]!!.preAuthorizedCode,
            userPin = null
        )
//        logger.debug("tokenReq: {}", tokenReq)

        val tokenResp = ktorClient.submitForm(
            providerMetadata.tokenEndpoint!!, formParameters = parametersOf(tokenReq.toHttpParameters())
        ).let {
            logger.debug("tokenResp raw: {}", it)
            it.body<JsonObject>().let { TokenResponse.fromJSON(it) }
        }

//        logger.debug("tokenResp: {}", tokenResp)

        logger.debug(">>> Token response = success: ${tokenResp.isSuccess}")

        logger.debug("// receive credential")
        val nonce = tokenResp.cNonce


        logger.debug("Using issuer URL: ${credentialOfferRequest.credentialOfferUri ?: credentialOfferRequest.credentialOffer!!.credentialIssuer}")
        val credReqs = offeredCredentials.map { offeredCredential ->
            CredentialRequest.forOfferedCredential(
                offeredCredential = offeredCredential,
                proof = credentialWallet.generateDidProof(
                    did = credentialWallet.did,
                    issuerUrl =  /*ciTestProvider.baseUrl*/ credentialOfferRequest.credentialOfferUri
                        ?: credentialOfferRequest.credentialOffer!!.credentialIssuer,
                    nonce = nonce
                )
            )
        }
        logger.debug("credReqs: {}", credReqs)


        return when {
            credReqs.size >= 2 -> {
                val batchCredentialRequest = BatchCredentialRequest(credReqs)

                val credentialResponses = ktorClient.post(providerMetadata.batchCredentialEndpoint!!) {
                    contentType(ContentType.Application.Json)
                    bearerAuth(tokenResp.accessToken!!)
                    setBody(batchCredentialRequest.toJSON())
                }.body<JsonObject>().let { BatchCredentialResponse.fromJSON(it) }
                logger.debug("credentialResponses: {}", credentialResponses)

                credentialResponses.credentialResponses
                    ?: throw IllegalArgumentException("No credential responses returned")
            }

            credReqs.size == 1 -> {
                val credReq = credReqs.first()

                val credentialResponse = ktorClient.post(providerMetadata.credentialEndpoint!!) {
                    contentType(ContentType.Application.Json)
                    bearerAuth(tokenResp.accessToken!!)
                    setBody(credReq.toJSON())
                }.body<JsonObject>().let { CredentialResponse.fromJSON(it) }
                logger.debug("credentialResponse: {}", credentialResponse)

                listOf(credentialResponse)
            }

            else -> throw IllegalStateException("No credentials offered")
        }
    }

    private suspend fun processMSEntraIssuanceRequest(
        entraIssuanceRequest: EntraIssuanceRequest,
        credentialWallet: TestCredentialWallet,
        pin: String? = null
    ): List<CredentialResponse> {
        // *) Load key:
        val walletKey = getKeyByDid(credentialWallet.did)

        // *) Create response JWT token, signed by key for holder DID
        val responseObject = entraIssuanceRequest.getResponseObject(
            walletKey.getThumbprint(),
            credentialWallet.did,
            walletKey.getPublicKey().exportJWK(),
            pin
        )
        val responseToken = credentialWallet.signToken(TokenTarget.TOKEN, responseObject, keyId = credentialWallet.did)
//        val jwtCryptoProvider = runBlocking {
//            val key = ECKey.parse(TEST_WALLET_KEY)
//            SimpleJWTCryptoProvider(JWSAlgorithm.ES256K, ECDSASigner(key).apply {
//                jcaContext.provider = BouncyCastleProviderSingleton.getInstance()
//            }, ECDSAVerifier(key.toPublicJWK()).apply {
//                jcaContext.provider = BouncyCastleProviderSingleton.getInstance()
//            })
//        }
//        val responseToken = SDJwt.sign(responseTokenPayload, jwtCryptoProvider, TEST_WALLET_DID + "#${testWalletKey.getKeyId()}").toString()

        // *) POST response JWT token to return address found in manifest
        val resp = http.post(entraIssuanceRequest.issuerReturnAddress) {
            contentType(ContentType.Text.Plain)
            setBody(responseToken)
        }
        val responseBody = resp.bodyAsText()
        logger.debug("Resp: {}", resp)
        logger.debug(responseBody)
        val vc =
            runCatching { Json.parseToJsonElement(responseBody).jsonObject["vc"]!!.jsonPrimitive.content }.getOrElse {
                throw IllegalArgumentException("Could not get Verifiable Credential from response: $responseBody")
            }
        return listOf(CredentialResponse.Companion.success(CredentialFormat.jwt_vc_json, vc))
    }

    override suspend fun useOfferRequest(
        offer: String, did: String, requireUserInput: Boolean, silent: Boolean
    ): List<WalletCredential> {

        val credentialWallet = getCredentialWallet(did)

        logger.debug("// -------- WALLET ----------")
        logger.debug("// as WALLET: receive credential offer, either being called via deeplink or by scanning QR code")
        logger.debug("// parse credential URI")
        val reqParams = Url(offer).parameters.toMap()

        // entra or openid4vc credential offer
        val isEntra = EntraIssuanceRequest.isEntraIssuanceRequestUri(offer)
        val credentialResponses = if (isEntra) {
            processMSEntraIssuanceRequest(
                EntraIssuanceRequest.fromAuthorizationRequest(
                    AuthorizationRequest.fromHttpParametersAuto(
                        reqParams
                    )
                ), credentialWallet
            )
        } else {
            processCredentialOfferRequest(CredentialOfferRequest.fromHttpParameters(reqParams), credentialWallet)
        }

        // === original ===
        logger.debug("// parse and verify credential(s)")
        if (credentialResponses.all { it.credential == null }) {
            throw IllegalStateException("No credential was returned from credentialEndpoint: $credentialResponses")
        }

        // ??multiple credentials manifests
        val manifest =
            isEntra.takeIf { it }?.let { EntraManifestExtractor().extract(offer) }//?:DefaultManifestExtractor
        val addableCredentials: List<WalletCredential> = credentialResponses.map {
            getCredentialData(it, manifest, requireUserInput || silent).also {
                logEvent(
                    action = EventType.Credential.Accept,
                    originator = "", //parsedOfferReq.credentialOffer!!.credentialIssuer,
                    data = createCredentialEventData(credential = it.credential, type = it.type),
                    credentialId = it.credential.id,
                )
            }.credential
        }.filter {
            !silent || validateTrustedIssuer(it, isEntra) == TrustStatus.Trusted
        }

        CredentialsService.add(
            wallet = walletId,
            credentials = addableCredentials.toTypedArray()
        )
        return addableCredentials
    }

    /* DIDs */

    override suspend fun createDid(method: String, args: Map<String, JsonPrimitive>): String {
        val keyId = args["keyId"]?.content?.takeIf { it.isNotEmpty() } ?: generateKey(KeyType.Ed25519.name)
        val key = getKey(keyId)
        val options = getDidOptions(method, args)
        val result = DidService.registerByKey(method, key, options)
        DidsService.add(
            wallet = walletId,
            did = result.did,
            document = Json.encodeToString(result.didDocument),
            alias = args["alias"]?.content,
            keyId = keyId
        )
        logEvent(
            EventType.Did.Create, "wallet", DidEventData(
                did = result.did, document = result.didDocument.toString()
            )
        )
        return result.did
    }

    override suspend fun listDids() = transaction { DidsService.list(walletId) }

    override suspend fun loadDid(did: String): JsonObject = DidsService.get(walletId, did)?.let {
        Json.parseToJsonElement(it.document).jsonObject
    } ?: throw IllegalArgumentException("Did not found: $did for account: $walletId")


    override suspend fun deleteDid(did: String): Boolean {
        DidsService.get(walletId, did).also {
            logEvent(
                EventType.Did.Delete, "wallet", DidEventData(
                    did = it?.did ?: did, document = it?.document ?: EventDataNotAvailable
                )
            )
        }
        return DidsService.delete(walletId, did)
    }

    override suspend fun setDefault(did: String) = DidsService.makeDidDefault(walletId, did)

    /* Keys */

    private fun getKey(keyId: String) = KeysService.get(walletId, keyId)?.let {
        KeySerialization.deserializeKey(it.document)
            .getOrElse { throw IllegalArgumentException("Could not deserialize resolved key: ${it.message}", it) }
    } ?: throw IllegalArgumentException("Key not found: $keyId")

    suspend fun getKeyByDid(did: String): Key = DidService.resolveToKey(did).fold(onSuccess = {
        getKey(it.getKeyId())
    }, onFailure = {
        throw it
    })

    override suspend fun exportKey(alias: String, format: String, private: Boolean): String = let {
        runCatching {
            getKey(alias).also {
                logEvent(
                    EventType.Key.Export, "wallet", KeyEventData(
                        id = it.getKeyId(), algorithm = it.keyType.name, keyManagementService = EventDataNotAvailable
                    )
                )
            }
        }.fold(onSuccess = {
            when (format.lowercase()) {
                "jwk" -> it.exportJWK()
                "pem" -> it.exportPEM()
                else -> throw IllegalArgumentException("Unknown format: $format")
            }
        }, onFailure = {
            throw it
        })
    }

    override suspend fun loadKey(alias: String): JsonObject = getKey(alias).exportJWKObject()

    override suspend fun listKeys(): List<SingleKeyResponse> = KeysService.list(walletId).map {
        val key = KeySerialization.deserializeKey(it.document).getOrThrow()

        SingleKeyResponse(
            keyId = SingleKeyResponse.KeyId(it.keyId),
            algorithm = key.keyType.name,
            cryptoProvider = key.toString(),
            keyPair = JsonObject(emptyMap()),
            keysetHandle = JsonNull
        )
    }

    override suspend fun generateKey(type: String): String =
        LocalKey.generate(KeyType.valueOf(type)).let { createdKey ->
            logEvent(
                EventType.Key.Create, "wallet", KeyEventData(
                    id = createdKey.getKeyId(),
                    algorithm = createdKey.keyType.name,
                    keyManagementService = "local",
                )
            )
            KeysService.add(walletId, createdKey.getKeyId(), KeySerialization.serializeKey(createdKey))
            createdKey.getKeyId()
        }

    override suspend fun importKey(jwkOrPem: String): String {
        val type = when {
            jwkOrPem.lines().first().contains("BEGIN ") -> "pem"
            else -> "jwk"
        }

        val keyResult = when (type) {
            "pem" -> LocalKey.importPEM(jwkOrPem)
            "jwk" -> LocalKey.importJWK(jwkOrPem)
            else -> throw IllegalArgumentException("Unknown key type: $type")
        }

        if (keyResult.isFailure) {
            throw IllegalArgumentException("Could not import key as: $type; error message: " + keyResult.exceptionOrNull()?.message)
        }

        val key = keyResult.getOrThrow()
        val keyId = key.getKeyId()
        logEvent(
            EventType.Key.Import, "wallet", KeyEventData(
                id = keyId, algorithm = key.keyType.name, keyManagementService = EventDataNotAvailable
            )
        )
        KeysService.add(walletId, keyId, KeySerialization.serializeKey(key))
        return keyId
    }

    override suspend fun deleteKey(alias: String): Boolean = runCatching {
        KeysService.get(walletId, alias)?.let { Json.parseToJsonElement(it.document) }?.run {
            logEvent(
                EventType.Key.Delete, "wallet", KeyEventData(
                    id = this.jsonObject["jwk"]?.jsonObject?.get("kid")?.jsonPrimitive?.content
                        ?: EventDataNotAvailable,
                    algorithm = this.jsonObject["jwk"]?.jsonObject?.get("kty")?.jsonPrimitive?.content
                        ?: EventDataNotAvailable,
                    keyManagementService = EventDataNotAvailable
                )
            )
        }
    }.let {
        KeysService.delete(walletId, alias)
    }

    fun addToHistory() {
        // data from
        // https://wallet.walt-test.cloud/api/wallet/issuance/info?sessionId=SESSION_ID
        // after taking up issuance offer
    }
// TODO
//fun infoAboutOfferRequest

    override fun getHistory(limit: Int, offset: Long): List<WalletOperationHistory> =
        WalletOperationHistories.select { WalletOperationHistories.wallet eq walletId }
            .orderBy(WalletOperationHistories.timestamp).limit(10).map { row ->
                WalletOperationHistory(row)
            }

    override suspend fun addOperationHistory(operationHistory: WalletOperationHistory) {
        transaction {
            WalletOperationHistories.insert {
                it[tenant] = operationHistory.tenant
                it[accountId] = operationHistory.account
                it[wallet] = operationHistory.wallet
                it[timestamp] = operationHistory.timestamp.toJavaInstant()
                it[operation] = operationHistory.operation
                it[data] = Json.encodeToString(operationHistory.data)
            }
        }
    }

    override fun filterEventLog(filter: EventLogFilter): EventLogFilterResult = runCatching {
        val startingAfterItemIndex = filter.startingAfter?.toLongOrNull()?.takeIf { it >= 0 } ?: -1L
        val pageSize = filter.limit
        val count = EventService.count(walletId, filter.data)
        val offset = startingAfterItemIndex + 1
        val events = EventService.get(
            accountId, walletId, filter.limit, offset, filter.sortOrder ?: "asc", filter.sortBy ?: "", filter.data
        )
        EventLogFilterDataResult(
            items = events,
            count = events.size,
            currentStartingAfter = computeCurrentStartingAfter(startingAfterItemIndex),
            nextStartingAfter = computeNextStartingAfter(startingAfterItemIndex, pageSize, count)
        )
    }.fold(onSuccess = {
        it
    }, onFailure = {
        EventLogFilterErrorResult(reason = it.localizedMessage)
    })

    override suspend fun linkWallet(wallet: WalletDataTransferObject): LinkedWalletDataTransferObject =
        Web3WalletService.link(tenant, walletId, wallet)

    override suspend fun unlinkWallet(wallet: UUID) = Web3WalletService.unlink(tenant, walletId, wallet)

    override suspend fun getLinkedWallets(): List<LinkedWalletDataTransferObject> =
        Web3WalletService.getLinked(tenant, walletId)

    override suspend fun connectWallet(walletId: UUID) = Web3WalletService.connect(tenant, this.walletId, walletId)

    override suspend fun disconnectWallet(wallet: UUID) = Web3WalletService.disconnect(tenant, walletId, wallet)

    override suspend fun listIssuers(): List<IssuerDataTransferObject> = IssuersService.list(walletId)

    override suspend fun getIssuer(name: String): IssuerDataTransferObject =
        IssuersService.get(walletId, name) ?: throw IllegalArgumentException("Issuer: $name not found for: $walletId")

    override fun getCredentialsByIds(credentialIds: List<String>): List<WalletCredential> {
        // todo: select by SQL
        return listCredentials(CredentialFilterObject.default).filter { it.id in credentialIds }
    }

    override fun authorizeIssuer(issuer: String): Boolean = IssuersService.authorize(walletId, issuer) > 0
    override fun addIssuer(issuer: IssuerDataTransferObject): Boolean = IssuersService.add(
        name = issuer.name,
        description = issuer.description,
        uiEndpoint = issuer.uiEndpoint,
        configurationEndpoint = issuer.configurationEndpoint
    ).let {
        IssuersService.addToWallet(walletId, issuer.name, issuer.authorized) > 0
    }

    override suspend fun listCategories(): List<WalletCategoryData> = categoryService.list(walletId)

    override suspend fun addCategory(name: String): Boolean = categoryService.add(walletId, name) == 1

    override suspend fun deleteCategory(name: String): Boolean = categoryService.delete(walletId, name) == 1
    override suspend fun getFrequentCredentials(parameter: ReportRequestParameter): List<WalletCredential> =
        ReportService.Credentials.frequent(parameter)

    override suspend fun getSettings(): WalletSetting =
        settingsService.get(walletId) ?: error("Settings not found for wallet: $walletId")

    override suspend fun setSettings(settings: WalletSetting): Boolean = settingsService.set(walletId, settings) > 0

    private fun getDidOptions(method: String, args: Map<String, JsonPrimitive>) = when (method.lowercase()) {
        "key" -> DidKeyCreateOptions(
            args["key"]?.let { enumValueIgnoreCase<KeyType>(it.content) } ?: KeyType.Ed25519,
            args["useJwkJcsPub"]?.let { it.content.toBoolean() } ?: false
        )

        "jwk" -> DidJwkCreateOptions()
        "web" -> DidWebCreateOptions(domain = args["domain"]?.content ?: "", path = args["path"]?.content ?: "")
        "cheqd" -> DidCheqdCreateOptions(
            network = args["network"]?.content ?: "testnet",
        )

        else -> throw IllegalArgumentException("Did method not supported: $method")
    }

    private fun logEvent(
        action: EventType.Action,
        originator: String,
        data: EventData,
        credentialId: String? = null,
        note: String? = null
    ) =
        EventService.add(
            Event(
                action = action,
                tenant = tenant,
                originator = originator,
                account = accountId,
                wallet = walletId,
                data = data,
                credentialId = credentialId,
                note = note,
            )
        )

    //TODO: move to related entity
    private fun createCredentialEventData(credential: WalletCredential, type: String?) = CredentialEventData(
        ecosystem = EventDataNotAvailable,
        issuerId = parseIssuerDid(credential.parsedDocument),
        subjectId = credential.parsedDocument?.jsonObject?.get("credentialSubject")?.jsonObject?.get(
            "id"
        )?.jsonPrimitive?.content ?: EventDataNotAvailable,
        issuerKeyId = EventDataNotAvailable,
        issuerKeyType = EventDataNotAvailable,
        subjectKeyType = EventDataNotAvailable,
        credentialType = type ?: EventDataNotAvailable,
        credentialFormat = "W3C",
        credentialProofType = EventDataNotAvailable,
        policies = emptyList(),
        protocol = "oid4vp",
        credentialId = credential.id,
    )

    //TODO: move to related entity
    private fun computeCurrentStartingAfter(afterItemIndex: Long): String? = let {
        afterItemIndex.takeIf { it >= 0 }?.toString()
    }

    //TODO: move to related entity
    private fun computeNextStartingAfter(afterItemIndex: Long, pageSize: Int, count: Long): String? = let {
        val itemIndex = afterItemIndex + pageSize
        itemIndex.takeIf { it < count }?.toString()
    }

    private fun getCredentialData(
        credentialResp: CredentialResponse, manifest: JsonObject?, pending: Boolean
    ) = let {
        val credential = credentialResp.credential!!.jsonPrimitive.content
        val credentialJwt = credential.decodeJws(withSignature = true)
        val typ = credentialJwt.header["typ"]?.jsonPrimitive?.content?.lowercase()
        when (typ) {
            "jwt" -> parseJwtCredentialResponse(credentialJwt, credential, manifest, pending)
            "vc+sd-jwt" -> parseSdJwtCredentialResponse(credentialJwt, credential, manifest, pending)
            null -> throw IllegalArgumentException("WalletCredential JWT does not have \"typ\"")
            else -> throw IllegalArgumentException("Invalid credential \"typ\": $typ")
        }.let {
            CredentialDataResult(credential = it, type = typ)
        }
    }

    //TODO: move to related entity
    private fun parseJwtCredentialResponse(
        credentialJwt: JwsUtils.JwsParts, document: String, manifest: JsonObject?, pending: Boolean,
    ) = let {
        val credentialId =
            credentialJwt.payload["vc"]!!.jsonObject["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: randomUUID()

        logger.debug("Got JWT credential: {}", credentialJwt)

        WalletCredential(
            wallet = walletId,
            id = credentialId,
            document = document,
            disclosures = null,
            addedOn = Clock.System.now(),
            manifest = manifest.toString(),
//                delete = false,
            deletedOn = null,
            pending = pending,
        )
    }

    //TODO: move to related entity
    private fun parseSdJwtCredentialResponse(
        credentialJwt: JwsUtils.JwsParts, document: String, manifest: JsonObject?, pending: Boolean
    ) = let {
        val credentialId =
            credentialJwt.payload["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: randomUUID()

        logger.debug("Got SD-JWT credential: $credentialJwt")

        val disclosures = credentialJwt.signature.split("~").drop(1)
        logger.debug("Disclosures (${disclosures.size}): $disclosures")

        val disclosuresString = disclosures.joinToString("~")

        val credentialWithoutDisclosures = document.substringBefore("~")

        WalletCredential(
            wallet = walletId,
            id = credentialId,
            document = credentialWithoutDisclosures,
            disclosures = disclosuresString,
            addedOn = Clock.System.now(),
            manifest = manifest?.toString(),
//                delete = false,
            deletedOn = null,
            pending = pending,
        )
    }

    private suspend fun validateTrustedIssuer(credential: WalletCredential, isEntra: Boolean) =
        isEntra.takeIf { it }?.let {
            trustUseCase.status(credential, true)
        }?: throw IllegalArgumentException("Silent claim for this credential type not supported.")//TrustStatus.NotFound

    private data class CredentialDataResult(
        val credential: WalletCredential,
        val type: String?,
    )

    private fun parseIssuerDid(credential: JsonObject?) = credential?.jsonObject?.get("issuer")?.let {
        if (it is JsonObject) it.jsonObject["id"]?.jsonPrimitive?.content
        else it.jsonPrimitive.content
    } ?: EventDataNotAvailable

}
