package app.Mainfabric

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.Logger
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Serializable data class RequestCodeRequest(val email: String)
@Serializable data class VerifyCodeRequest(val email: String, val code: String)
@Serializable data class TokenResponse(val token: String, val expiresIn: Long)
@Serializable data class MeResponse(val userId: String)

class JwtService(config: ApplicationConfig) {
    val issuer = config.property("jwt.issuer").getString()
    val audience = config.property("jwt.audience").getString()
    val realm = config.property("jwt.realm").getString()
    private val expiresIn = config.property("jwt.expiresInSeconds").getString().toLong()
    private val algorithm = Algorithm.HMAC256(config.property("jwt.secret").getString())

    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(issuer)
        .withAudience(audience)
        .build()

    fun issue(userId: UUID): TokenResponse {
        val token = JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(userId.toString())
            .withExpiresAt(Instant.now().plusSeconds(expiresIn))
            .sign(algorithm)
        return TokenResponse(token, expiresIn)
    }
}

private sealed interface VerifyResult {
    data class Ok(val userId: UUID) : VerifyResult
    data object Invalid : VerifyResult
    data object Expired : VerifyResult
    data object Locked : VerifyResult
}

class AuthService(
    config: ApplicationConfig,
    private val jwt: JwtService,
    private val log: Logger
) {
    private val allowedDomains = config.property("auth.allowedDomains").getList().map { it.lowercase() }
    private val ttlMillis = config.property("auth.codeTtlMinutes").getString().toLong() * 60_000
    private val maxAttempts = config.property("auth.maxAttempts").getString().toInt()
    private val devPrintCode = config.property("auth.devPrintCode").getString().toBoolean()
    private val codeSecret = config.property("auth.codeSecret").getString().toByteArray()
    private val random = SecureRandom()

    suspend fun requestCode(rawEmail: String) {
        val email = normalizeEmail(rawEmail)
        val code = "%06d".format(random.nextInt(1_000_000))
        val codeHash = hash(email, code)
        val expiresAt = System.currentTimeMillis() + ttlMillis

        withContext(Dispatchers.IO) {
            transaction {
                LoginCodes.deleteWhere { LoginCodes.email eq email }   // старый код отменяется
                LoginCodes.insert {
                    it[LoginCodes.email] = email
                    it[LoginCodes.codeHash] = codeHash
                    it[LoginCodes.expiresAt] = expiresAt
                }
            }
        }

        if (devPrintCode) {
            log.info("DEV login code for {}: {}", email, code)
        } else {
            // TODO: отправка настоящего письма
            log.warn("Email sending is not implemented yet")
        }
    }

    suspend fun verify(rawEmail: String, code: String): TokenResponse {
        val email = normalizeEmail(rawEmail)
        val submittedHash = hash(email, code.trim())
        val now = System.currentTimeMillis()

        // Исключения бросаем ПОСЛЕ транзакции, иначе откатится увеличение счётчика попыток
        val result = withContext(Dispatchers.IO) {
            transaction {
                val row = LoginCodes.selectAll().where { LoginCodes.email eq email }.firstOrNull()
                when {
                    row == null || row[LoginCodes.expiresAt] < now -> {
                        LoginCodes.deleteWhere { LoginCodes.email eq email }
                        VerifyResult.Expired
                    }
                    row[LoginCodes.attempts] >= maxAttempts -> {
                        LoginCodes.deleteWhere { LoginCodes.email eq email }
                        VerifyResult.Locked
                    }
                    !MessageDigest.isEqual(
                        row[LoginCodes.codeHash].toByteArray(),
                        submittedHash.toByteArray()
                    ) -> {
                        val rowId = row[LoginCodes.id].value
                        val attempts = row[LoginCodes.attempts] + 1
                        LoginCodes.update({ LoginCodes.id eq rowId }) { it[LoginCodes.attempts] = attempts }
                        VerifyResult.Invalid
                    }
                    else -> {
                        LoginCodes.deleteWhere { LoginCodes.email eq email }
                        val existing = Users.selectAll().where { Users.email eq email }.firstOrNull()
                        val userId = existing?.get(Users.id)?.value
                            ?: Users.insertAndGetId { it[Users.email] = email }.value
                        VerifyResult.Ok(userId)
                    }
                }
            }
        }

        return when (result) {
            is VerifyResult.Ok -> jwt.issue(result.userId)
            VerifyResult.Invalid -> throw ApiException(HttpStatusCode.BadRequest, "invalid_code", "Неверный код")
            VerifyResult.Expired -> throw ApiException(HttpStatusCode.BadRequest, "code_expired", "Код не найден или просрочен, запросите новый")
            VerifyResult.Locked -> throw ApiException(HttpStatusCode.TooManyRequests, "too_many_attempts", "Слишком много попыток, запросите новый код")
        }
    }

    private fun normalizeEmail(raw: String): String {
        val email = raw.trim().lowercase()
        val valid = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email)
        if (!valid) throw ApiException(HttpStatusCode.BadRequest, "invalid_email", "Некорректный адрес почты")
        val domain = email.substringAfter('@')
        if (allowedDomains.none { domain == it || domain.endsWith(".$it") }) {
            throw ApiException(HttpStatusCode.BadRequest, "email_domain_not_allowed", "Нужна почта вашего университета")
        }
        return email
    }

    private fun hash(email: String, code: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(codeSecret, "HmacSHA256"))
        return mac.doFinal("$email:$code".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

fun Route.authRoutes(auth: AuthService) {
    route("/auth") {
        post("/request-code") {
            val body = call.receive<RequestCodeRequest>()
            auth.requestCode(body.email)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/verify") {
            val body = call.receive<VerifyCodeRequest>()
            call.respond(auth.verify(body.email, body.code))
        }
    }
}

/** id текущего пользователя из JWT (для защищённых маршрутов) */
fun ApplicationCall.userId(): UUID =
    UUID.fromString(principal<JWTPrincipal>()!!.payload.subject)