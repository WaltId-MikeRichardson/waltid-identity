package id.walt.issuer


import id.walt.oid4vc.data.ResponseMode
import id.walt.oid4vc.data.ResponseType
import id.walt.oid4vc.errors.*
import id.walt.oid4vc.providers.TokenTarget
import id.walt.oid4vc.requests.AuthorizationRequest
import id.walt.oid4vc.requests.BatchCredentialRequest
import id.walt.oid4vc.requests.CredentialRequest
import id.walt.oid4vc.requests.TokenRequest
import id.walt.oid4vc.responses.AuthorizationErrorCode
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.smiley4.ktorswaggerui.dsl.get
import io.github.smiley4.ktorswaggerui.dsl.route
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.minutes


@Serializable
data class UserData(val email: String, val password: String, val id: String? = null)

object OidcApi : CIProvider() {

    val logger = KotlinLogging.logger { }

    private fun Application.oidcRoute(build: Route.() -> Unit) {
        routing {
            //   authenticate("authenticated") {
            /*route("oidc", {
                tags = listOf("oidc")
            }) {*/
            build.invoke(this)
            /*}*/
        }
        //}
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun Application.oidcApi() = oidcRoute {
        route("", {
            tags = listOf("oidc")
        }) {
            get("/.well-known/openid-configuration") {
                call.respond(metadata.toJSON())
            }
            get("/.well-known/openid-credential-issuer") {
                call.respond(metadata.toJSON())
            }
        }

        route("", {
            tags = listOf("oidc")
        }) {

            post("/par") {
                val authReq = AuthorizationRequest.fromHttpParameters(call.receiveParameters().toMap())
                try {
                    val session = initializeAuthorization(authReq, 5.minutes)
                    call.respond(getPushedAuthorizationSuccessResponse(session).toJSON())
                } catch (exc: AuthorizationError) {
                    logger.error(exc) { "Authorization error: " }
                    call.respond(HttpStatusCode.BadRequest, exc.toPushedAuthorizationErrorResponse().toJSON())
                }
            }
            get("/authorize") {
                val authReq = runBlocking { AuthorizationRequest.fromHttpParametersAuto(call.parameters.toMap()) }
                try {
                    val authResp = if (authReq.responseType == ResponseType.code.name) {
                        processCodeFlowAuthorization(authReq)
                    } else if (authReq.responseType.contains(ResponseType.token.name)) {
                        processImplicitFlowAuthorization(authReq)
                    } else {
                        throw AuthorizationError(authReq, AuthorizationErrorCode.unsupported_response_type, "Response type not supported")
                    }
                    val redirectUri = if (authReq.isReferenceToPAR) {
                        getPushedAuthorizationSession(authReq).authorizationRequest?.redirectUri
                    } else {
                        authReq.redirectUri
                    } ?: throw AuthorizationError(
                        authReq,
                        AuthorizationErrorCode.invalid_request,
                        "No redirect_uri found for this authorization request"
                    )
                    call.response.apply {
                        status(HttpStatusCode.Found)
                        val defaultResponseMode =
                            if (authReq.responseType == ResponseType.code.name) ResponseMode.query else ResponseMode.fragment
                        header(HttpHeaders.Location, authResp.toRedirectUri(redirectUri, authReq.responseMode ?: defaultResponseMode))
                    }
                } catch (authExc: AuthorizationError) {
                    logger.error(authExc) { "Authorization error: " }
                    call.response.apply {
                        status(HttpStatusCode.Found)
                        header(HttpHeaders.Location, URLBuilder(authExc.authorizationRequest.redirectUri!!).apply {
                            parameters.appendAll(parametersOf(authExc.toAuthorizationErrorResponse().toHttpParameters()))
                        }.buildString())
                    }
                }
            }
            post("/token") {
                val params = call.receiveParameters().toMap()

                println("/token params: $params")

                val tokenReq = TokenRequest.fromHttpParameters(params)
                println("/token tokenReq from params: $tokenReq")

                try {
                    val tokenResp = processTokenRequest(tokenReq)
                    println("/token tokenResp: $tokenResp")

                    val sessionId = Json.parseToJsonElement(
                        Base64.decode(
                            (tokenResp.accessToken
                                ?: throw IllegalArgumentException("No access token was responded with tokenResp?")).split(".")[1]
                        ).decodeToString()
                    ).jsonObject["sub"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("Could not get session ID from token response!")
                    val nonceToken = tokenResp.cNonce ?: throw IllegalArgumentException("No nonce token was responded with the tokenResp?")
                    OidcApi.mapSessionIdToToken(sessionId, nonceToken)  // TODO: Hack as this is non stateless because of oidc4vc lib API

                    call.respond(tokenResp.toJSON())
                } catch (exc: TokenError) {
                    logger.error(exc) { "Token error: " }
                    call.respond(HttpStatusCode.BadRequest, exc.toAuthorizationErrorResponse().toJSON())
                }
            }
            post("/credential") {
                val accessToken = call.request.header(HttpHeaders.Authorization)?.substringAfter(" ")
                if (accessToken.isNullOrEmpty() || !verifyTokenSignature(TokenTarget.ACCESS, accessToken)) {
                    call.respond(HttpStatusCode.Unauthorized)
                } else {
                    val credReq = CredentialRequest.fromJSON(call.receive<JsonObject>())
                    try {
                        call.respond(generateCredentialResponse(credReq, accessToken).toJSON())
                    } catch (exc: CredentialError) {
                        logger.error(exc) { "Credential error: " }
                        call.respond(HttpStatusCode.BadRequest, exc.toCredentialErrorResponse().toJSON())
                    }
                }
            }
            post("/credential_deferred") {
                val accessToken = call.request.header(HttpHeaders.Authorization)?.substringAfter(" ")
                if (accessToken.isNullOrEmpty() || !verifyTokenSignature(TokenTarget.DEFERRED_CREDENTIAL, accessToken)) {
                    call.respond(HttpStatusCode.Unauthorized)
                } else {
                    try {
                        call.respond(generateDeferredCredentialResponse(accessToken).toJSON())
                    } catch (exc: DeferredCredentialError) {
                        logger.error(exc) { "DeferredCredentialError: " }
                        call.respond(HttpStatusCode.BadRequest, exc.toCredentialErrorResponse().toJSON())
                    }
                }
            }
            post("/batch_credential") {
                val accessToken = call.request.header(HttpHeaders.Authorization)?.substringAfter(" ")
                if (accessToken.isNullOrEmpty() || !verifyTokenSignature(TokenTarget.ACCESS, accessToken)) {
                    call.respond(HttpStatusCode.Unauthorized)
                } else {
                    val req = BatchCredentialRequest.fromJSON(call.receive())
                    try {
                        call.respond(generateBatchCredentialResponse(req, accessToken).toJSON())
                    } catch (exc: BatchCredentialError) {
                        logger.error(exc) { "BatchCredentialError: " }
                        call.respond(HttpStatusCode.BadRequest, exc.toBatchCredentialErrorResponse().toJSON())
                    }
                }
            }
        }
    }

    /*
    private val sessionCache = mutableMapOf<String, IssuanceSession>()

    override fun generateCredential(credentialRequest: CredentialRequest): CredentialResult {
        return doGenerateCredential(credentialRequest)
    }

    override fun getDeferredCredential(credentialID: String): CredentialResult {
        TODO("Not yet implemented")
    }

    override fun getSession(id: String): IssuanceSession? {
        return sessionCache[id]
    }

    override fun putSession(id: String, session: IssuanceSession): IssuanceSession? {
        return sessionCache.put(id, session)
    }

    override fun removeSession(id: String): IssuanceSession? {
        return sessionCache.remove(id)
    }

    //private val CI_TOKEN_KEY = KeyService.getService().generate(KeyAlgorithm.RSA)
    //private val CI_DID_KEY = KeyService.getService().generate(KeyAlgorithm.EdDSA_Ed25519)
    //val CI_ISSUER_DID = DidService.create(DidMethod.key, CI_DID_KEY.id)
    override fun signToken(target: TokenTarget, payload: JsonObject, header: JsonObject?, keyId: String?): String {

        TODO()
        //return JwtService.getService().sign(keyId ?: CI_TOKEN_KEY.id, payload.toString())
    }

    override fun verifyTokenSignature(target: TokenTarget, token: String): Boolean = TODO() //JwtService.getService().verify(token).verified

    private fun doGenerateCredential(credentialRequest: CredentialRequest): CredentialResult {
        TODO()
        /*if(credentialRequest.format == CredentialFormat.mso_mdoc) throw CredentialError(credentialRequest, CredentialErrorCode.unsupported_credential_format)
        val types = credentialRequest.types ?: credentialRequest.credentialDefinition?.types ?: throw CredentialError(credentialRequest, CredentialErrorCode.unsupported_credential_type)
        val proofHeader = credentialRequest.proof?.jwt?.let { parseTokenHeader(it) } ?: throw CredentialError(credentialRequest, CredentialErrorCode.invalid_or_missing_proof, message = "Proof must be JWT proof")
        val holderKid = proofHeader[JWTClaims.Header.keyID]?.jsonPrimitive?.content ?: throw CredentialError(credentialRequest, CredentialErrorCode.invalid_or_missing_proof, message = "Proof JWT header must contain kid claim")
        return Signatory.getService().issue(
            types.last(),
            ProofConfig(CI_ISSUER_DID, subjectDid = resolveDIDFor(holderKid)),
            issuer = W3CIssuer(baseUrl),
            storeCredential = false).let {
            when(credentialRequest.format) {
                CredentialFormat.ldp_vc -> Json.decodeFromString<JsonObject>(it)
                else -> JsonPrimitive(it)
            }
        }.let { CredentialResult(credentialRequest.format, it) }*/
    }

    private fun resolveDIDFor(keyId: String): String {
        TODO()

        //return DidUrl.from(keyId).did
    }

     */
}
