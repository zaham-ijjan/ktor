/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.swagger

/**
 * Configuration for Swagger UI endpoint.
 */
public class SwaggerConfig {
    internal var customStyle: String? = null

    /**
     * Specify Swagger UI version to use.
     */
    public var version: String = "4.14.0"

    /**
     * Set url for custom css style for swagger-ui.
     *
     * You can try: https://unpkg.com/swagger-ui-themes@3.0.1/themes/3.x/theme-monokai.css
     */
    public fun customStyle(path: String?) {
        customStyle = path
    }
}
