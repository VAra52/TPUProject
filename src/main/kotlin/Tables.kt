package app.Mainfabric

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.timestamp

object Users : UUIDTable("users") {
    val email = varchar("email", 255).uniqueIndex()
    val createdAt = timestamp("created_at").databaseGenerated()
}

object LoginCodes : UUIDTable("login_codes") {
    val email = varchar("email", 255).index()
    val codeHash = varchar("code_hash", 64)
    val expiresAt = long("expires_at")          // миллисекунды с 1970 года
    val attempts = integer("attempts").default(0)
}

object Initiatives : UUIDTable("initiatives") {
    val authorId = reference("author_id", Users)
    val title = varchar("title", 120)
    val description = text("description")
    val voteCount = integer("vote_count").default(0)
    val createdAt = timestamp("created_at").databaseGenerated()
}

// Намеренно без user_id и времени: голоса анонимны
object Votes : Table("votes") {
    val initiativeId = reference("initiative_id", Initiatives)
    val voterHash = binary("voter_hash")
    override val primaryKey = PrimaryKey(initiativeId, voterHash)
}