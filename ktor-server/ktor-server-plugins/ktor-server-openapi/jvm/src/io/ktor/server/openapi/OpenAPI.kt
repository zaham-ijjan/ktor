/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.openapi

import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.swagger.codegen.v3.*
import io.swagger.codegen.v3.generators.html.*
import io.swagger.parser.*
import io.swagger.v3.parser.core.models.*
import kotlinx.html.*
import java.io.*

/**
 * Create a get endpoint with [SwaggerUI] ath [path] rendered from open api file at [filename].
 */
public fun Routing.swaggerUI(path: String, filename: String) {
    route(path) {
        get("api.json") {
            val file = File(filename)
            call.respondFile(file)
        }
        get {
            val fullPath = call.request.path()
            call.respondHtml {
                head {
                    title { +"Swagger UI" }
                    link(rel = "stylesheet", href = "https://unpkg.com/swagger-ui-dist@4.5.0/swagger-ui.css")
                }
                body {
                    div { id = "swagger-ui" }
                    script(src = "https://unpkg.com/swagger-ui-dist@4.5.0/swagger-ui-bundle.js") {
                        attributes["crossorigin"] = "anonymous"
                    }
                    script(src = "https://unpkg.com/swagger-ui-dist@4.5.0/swagger-ui-standalone-preset.js") {
                        attributes["crossorigin"] = "anonymous"
                    }

                    script {
                        unsafe {
                            +"""
window.onload = function() {
    window.ui = SwaggerUIBundle({
        url: '$fullPath/api.json',
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

/**
 * Create a get endpoint at [path] with documentation rendered from openApi file at [filename].
 *
 * The documentation is generated using [StaticHtml2Codegen] by default. It can be customized using config in [block].
 * See [OpenAPIConfig] for more details.
 */
public fun Routing.openApi(path: String, filename: String, block: OpenAPIConfig.() -> Unit = {}) {
    val config = OpenAPIConfig()
    with(config) {
        val swagger = parser.readContents(File(filename).readText(), null, options)

        opts.apply {
            config(codegen)
            opts(ClientOpts())
            openAPI(swagger.openAPI)
        }

        block(this)

        generator.opts(opts)
        generator.generate()

        static(path) {
            staticRootFolder = File("docs")
            files(".")
            default("index.html")
        }
    }
}
