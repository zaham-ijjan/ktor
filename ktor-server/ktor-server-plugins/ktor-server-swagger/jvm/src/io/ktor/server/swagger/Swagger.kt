package io.ktor.server.swagger

import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import java.io.*

/**
 * Create a get endpoint with [SwaggerUI] at [path] rendered from open api file located at [swaggerFile].
 */
public fun Routing.swaggerUI(path: String, swaggerFile: String, block: SwaggerConfig.() -> Unit = {}) {
    val file = File(swaggerFile)
    val fileName = file.name
    val config = SwaggerConfig().apply(block)

    route(path) {
        get(swaggerFile) {
            call.respondFile(file)
        }
        get {
            val fullPath = call.request.path()
            call.respondHtml {
                head {
                    title { +"Swagger UI" }
                    link(
                        href = "https://unpkg.com/swagger-ui-dist@${config.version}/swagger-ui.css",
                        rel = "stylesheet"
                    )
                    config.customStyle?.let {
                        link(href = it, rel = "stylesheet")
                    }
                }
                body {
                    div { id = "swagger-ui" }
                    script(src = "https://unpkg.com/swagger-ui-dist@${config.version}/swagger-ui-bundle.js") {
                        attributes["crossorigin"] = "anonymous"
                    }

                    val src = "https://unpkg.com/swagger-ui-dist@${config.version}/swagger-ui-standalone-preset.js"
                    script(src = src) {
                        attributes["crossorigin"] = "anonymous"
                    }

                    script {
                        unsafe {
                            +"""
window.onload = function() {
    window.ui = SwaggerUIBundle({
        url: '$fullPath/$fileName',
        dom_id: '#swagger-ui',
        presets: [
            SwaggerUIBundle.presets.apis,
            SwaggerUIStandalonePreset
        ],
        layout: 'StandaloneLayout'
    });
}
                            """.trimIndent()
                        }
                    }
                }
            }
        }
    }
}
