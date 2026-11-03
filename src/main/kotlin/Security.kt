package app.Mainfabric

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*

fun Application.configureSecurity() {
    val jwtService = JwtService(environment.config)
    authentication {
        jwt("auth-jwt") {
            realm = jwtService.realm
            verifier(jwtService.verifier)
            validate { credential ->
                if (credential.payload.subject != null) JWTPrincipal(credential.payload) else null
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Требуется вход"))
            }
        }
    }
}