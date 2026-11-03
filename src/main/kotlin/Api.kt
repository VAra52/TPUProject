package app.Mainfabric

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(val error: String, val message: String)

class ApiException(
    val status: HttpStatusCode,
    val error: String,
    message: String
) : RuntimeException(message)