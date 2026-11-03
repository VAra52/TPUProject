package app.Mainfabric

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    val config = environment.config
    val jwtService = JwtService(config)
    val authService = AuthService(config, jwtService, environment.log)

    routing {
        get("/health") { call.respondText("ok") }

        authRoutes(authService)

        authenticate("auth-jwt") {
            get("/me") { call.respond(MeResponse(call.userId().toString())) }
        }
    }
}