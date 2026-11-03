package app.Mainfabric

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database

fun Application.configureDatabase() {
    val cfg = environment.config

    val hikari = HikariConfig().apply {
        jdbcUrl = cfg.property("database.url").getString()
        username = cfg.property("database.user").getString()
        password = cfg.property("database.password").getString()
        maximumPoolSize = cfg.property("database.maxPoolSize").getString().toInt()
        driverClassName = "org.postgresql.Driver"
    }
    val dataSource = HikariDataSource(hikari)

    // Применяет файлы из resources/db/migration (сейчас V1__init.sql)
    Flyway.configure()
        .dataSource(dataSource)
        .load()
        .migrate()

    // Подключаем Exposed к тому же пулу соединений
    Database.connect(dataSource)

    monitor.subscribe(ApplicationStopped) { dataSource.close() }
}