package app.Mainfabric

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Serializable data class CreateInitiativeRequest(val title: String, val description: String)

@Serializable
data class InitiativeDto(
    val id: String,
    val title: String,
    val description: String,
    val voteCount: Int,
    val votedByMe: Boolean,
    val isMine: Boolean,
    val createdAt: String
)

@Serializable
data class InitiativeListResponse(val items: List<InitiativeDto>, val limit: Int, val offset: Long, val total: Long)

@Serializable data class VoteResponse(val voteCount: Int, val votedByMe: Boolean)

class InitiativeService(config: ApplicationConfig) {
    private val voteSecret = config.property("voting.secret").getString().toByteArray()

    /** Анонимный идентификатор голоса: без секрета по нему нельзя восстановить пользователя,
     *  а для разных инициатив у одного человека получаются несвязанные значения. */
    private fun voterHash(userId: UUID, initiativeId: UUID): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(voteSecret, "HmacSHA256"))
        return mac.doFinal("$userId:$initiativeId".toByteArray())
    }

    private fun toDto(row: ResultRow, userId: UUID, voted: Boolean) = InitiativeDto(
        id = row[Initiatives.id].value.toString(),
        title = row[Initiatives.title],
        description = row[Initiatives.description],
        voteCount = row[Initiatives.voteCount],
        votedByMe = voted,
        isMine = row[Initiatives.authorId].value == userId,
        createdAt = row[Initiatives.createdAt].toString()
    )

    private fun findRow(id: UUID): ResultRow =
        Initiatives.selectAll().where { Initiatives.id eq id }.firstOrNull()
            ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "Инициатива не найдена")

    private fun hasVoted(userId: UUID, id: UUID): Boolean {
        val hash = voterHash(userId, id)
        return !Votes.selectAll().where { (Votes.initiativeId eq id) and (Votes.voterHash eq hash) }.empty()
    }

    /** Пересчитываем счётчик по таблице голосов, чтобы он не расходился с реальностью. */
    private fun recount(id: UUID): Int {
        val count = Votes.selectAll().where { Votes.initiativeId eq id }.count().toInt()
        Initiatives.update({ Initiatives.id eq id }) { it[voteCount] = count }
        return count
    }

    suspend fun list(userId: UUID, limit: Int, offset: Long): InitiativeListResponse =
        withContext(Dispatchers.IO) {
            transaction {
                val total = Initiatives.selectAll().count()
                val rows = Initiatives.selectAll()
                    .orderBy(Initiatives.voteCount to SortOrder.DESC, Initiatives.createdAt to SortOrder.DESC)
                    .limit(limit)
                    .offset(offset)
                    .toList()

                val ids = rows.map { it[Initiatives.id] }
                val hashes = rows.map { voterHash(userId, it[Initiatives.id].value) }
                val votedIds: Set<UUID> = if (rows.isEmpty()) emptySet() else
                    Votes.selectAll()
                        .where { (Votes.initiativeId inList ids) and (Votes.voterHash inList hashes) }
                        .map { it[Votes.initiativeId].value }
                        .toSet()

                InitiativeListResponse(
                    items = rows.map { toDto(it, userId, it[Initiatives.id].value in votedIds) },
                    limit = limit,
                    offset = offset,
                    total = total
                )
            }
        }

    suspend fun get(userId: UUID, id: UUID): InitiativeDto = withContext(Dispatchers.IO) {
        transaction { toDto(findRow(id), userId, hasVoted(userId, id)) }
    }

    suspend fun create(userId: UUID, title: String, description: String): InitiativeDto {
        val t = title.trim()
        val d = description.trim()
        if (t.length !in 3..120) {
            throw ApiException(HttpStatusCode.BadRequest, "validation_failed", "Название должно быть от 3 до 120 символов")
        }
        if (d.length !in 10..5000) {
            throw ApiException(HttpStatusCode.BadRequest, "validation_failed", "Описание должно быть от 10 до 5000 символов")
        }
        return withContext(Dispatchers.IO) {
            transaction {
                val newId = Initiatives.insertAndGetId {
                    it[Initiatives.authorId] = userId
                    it[Initiatives.title] = t
                    it[Initiatives.description] = d
                }.value
                toDto(findRow(newId), userId, false)
            }
        }
    }

    suspend fun delete(userId: UUID, id: UUID) = withContext(Dispatchers.IO) {
        transaction {
            val row = findRow(id)
            if (row[Initiatives.authorId].value != userId) {
                throw ApiException(HttpStatusCode.Forbidden, "forbidden", "Удалять можно только свои инициативы")
            }
            Initiatives.deleteWhere { Initiatives.id eq id }   // голоса удаляются каскадом
            Unit
        }
    }

    suspend fun vote(userId: UUID, id: UUID): VoteResponse = withContext(Dispatchers.IO) {
        transaction {
            findRow(id)
            val hash = voterHash(userId, id)
            Votes.insertIgnore {      // повторный голос игнорируется
                it[Votes.initiativeId] = id
                it[Votes.voterHash] = hash
            }
            VoteResponse(recount(id), true)
        }
    }

    suspend fun unvote(userId: UUID, id: UUID): VoteResponse = withContext(Dispatchers.IO) {
        transaction {
            findRow(id)
            val hash = voterHash(userId, id)
            Votes.deleteWhere { (Votes.initiativeId eq id) and (Votes.voterHash eq hash) }
            VoteResponse(recount(id), false)
        }
    }
}

private fun ApplicationCall.initiativeId(): UUID =
    parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_id", "Некорректный id")

fun Route.initiativeRoutes(service: InitiativeService) {
    authenticate("auth-jwt") {
        route("/initiatives") {
            get {
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 50)
                val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                call.respond(service.list(call.userId(), limit, offset))
            }
            post {
                val body = call.receive<CreateInitiativeRequest>()
                call.respond(HttpStatusCode.Created, service.create(call.userId(), body.title, body.description))
            }
            route("/{id}") {
                get { call.respond(service.get(call.userId(), call.initiativeId())) }
                delete {
                    service.delete(call.userId(), call.initiativeId())
                    call.respond(HttpStatusCode.NoContent)
                }
                route("/vote") {
                    put { call.respond(service.vote(call.userId(), call.initiativeId())) }
                    delete { call.respond(service.unvote(call.userId(), call.initiativeId())) }
                }
            }
        }
    }
}