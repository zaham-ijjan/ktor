/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.plugins.openapi

import io.ktor.server.http.content.*
import io.ktor.server.routing.*
import io.swagger.codegen.v3.*
import io.swagger.codegen.v3.generators.html.*
import java.io.*

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
