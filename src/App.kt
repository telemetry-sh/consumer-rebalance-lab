package lab

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.system.exitProcess

private val assets = mapOf(
    "/" to ("index.html" to "text/html; charset=utf-8"),
    "/index.html" to ("index.html" to "text/html; charset=utf-8"),
    "/styles.css" to ("styles.css" to "text/css; charset=utf-8"),
    "/app.js" to ("app.js" to "text/javascript; charset=utf-8"),
)

private fun environment(name: String, fallback: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: fallback

private fun parseQuery(query: String?): Map<String, String> {
    if (query.isNullOrBlank()) return emptyMap()
    return query.split("&").mapNotNull { pair ->
        val separator = pair.indexOf('=')
        if (separator < 1) return@mapNotNull null
        val key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8)
        val value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8)
        key to value
    }.toMap()
}

private fun HttpExchange.respond(
    status: Int,
    contentType: String,
    body: String,
    extraHeaders: Map<String, String> = emptyMap(),
) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.set("Content-Type", contentType)
    responseHeaders.set("Cache-Control", "no-store")
    responseHeaders.set("X-Content-Type-Options", "nosniff")
    for ((name, value) in extraHeaders) responseHeaders.set(name, value)
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private fun handle(exchange: HttpExchange, publicDirectory: Path) {
    try {
        if (exchange.requestMethod != "GET") {
            exchange.respond(
                405,
                "application/json; charset=utf-8",
                "{\"error\":\"method not allowed\"}",
                mapOf("Allow" to "GET"),
            )
            return
        }
        when (val path = exchange.requestURI.path) {
            "/healthz" -> exchange.respond(200, "text/plain; charset=utf-8", "ok")
            "/api/simulate" -> {
                val config = Config.fromQuery(parseQuery(exchange.requestURI.rawQuery))
                exchange.respond(
                    200,
                    "application/json; charset=utf-8",
                    Simulator.simulate(config).toJson(),
                )
            }

            else -> {
                val asset = assets[path]
                if (asset == null) {
                    exchange.respond(
                        404,
                        "application/json; charset=utf-8",
                        "{\"error\":\"not found\"}",
                    )
                    return
                }
                val file = publicDirectory.resolve(asset.first)
                if (!Files.isRegularFile(file) || Files.size(file) > 2 * 1024 * 1024) {
                    exchange.respond(
                        500,
                        "application/json; charset=utf-8",
                        "{\"error\":\"asset unavailable\"}",
                    )
                    return
                }
                exchange.respond(200, asset.second, Files.readString(file))
            }
        }
    } catch (error: Exception) {
        System.err.println("request failed: ${error.message}")
        runCatching {
            exchange.respond(
                500,
                "application/json; charset=utf-8",
                "{\"error\":\"internal server error\"}",
            )
        }
    } finally {
        exchange.close()
    }
}

private fun runServer() {
    val host = environment("HOST", "127.0.0.1")
    if (host !in setOf("127.0.0.1", "localhost", "0.0.0.0")) {
        System.err.println("HOST must be 127.0.0.1, localhost, or 0.0.0.0")
        exitProcess(2)
    }
    val port = environment("PORT", "8080").toIntOrNull()
    if (port == null || port !in 0..65535) {
        System.err.println("PORT must be between 0 and 65535")
        exitProcess(2)
    }
    val bindHost = if (host == "localhost") "127.0.0.1" else host
    val publicDirectory = Path.of(environment("PUBLIC_DIR", "public"))
    val server = HttpServer.create(InetSocketAddress(bindHost, port), 0)
    val executor = Executors.newFixedThreadPool(4)
    server.executor = executor
    server.createContext("/") { exchange -> handle(exchange, publicDirectory) }
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop(0)
        executor.shutdownNow()
    })
    server.start()
    println(
        """{"event":"server.started","url":"http://$bindHost:${server.address.port}","runtime":"kotlin-2.4"}""",
    )
}

fun main(arguments: Array<String>) {
    when {
        arguments.isEmpty() -> runServer()
        arguments.contentEquals(arrayOf("--json")) -> println(Simulator.simulate().toJson())
        else -> {
            System.err.println("usage: consumer-rebalance-lab [--json]")
            exitProcess(2)
        }
    }
}
