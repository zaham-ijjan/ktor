/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.plugins.openapi

import io.swagger.codegen.v3.*
import io.swagger.codegen.v3.generators.html.*
import io.swagger.parser.*
import io.swagger.v3.parser.core.models.*

/**
 * Configuration for openApi endpoint.
 */
public class OpenAPIConfig {
    /**
     * Parser to use for parsing openAPI.
     */
    public var parser: OpenAPIParser = OpenAPIParser()

    /**
     * OpenAPI generator options.
     */
    public var opts: ClientOptInput = ClientOptInput()

    /**
     * Generator to use for generating OpenAPI.
     */
    public var generator: Generator = DefaultGenerator()

    /**
     * Code generator for [OpenAPIConfig].
     *
     * See also [StaticHtml2Codegen], [StaticHtmlCodegen] and etc.
     */
    public var codegen: CodegenConfig = StaticHtml2Codegen()

    /**
     * Options for OpenAPI format parser.
     */
    public var options: ParseOptions = ParseOptions()
}
