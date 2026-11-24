/**
 * MIT License
 * <p>
 * Copyright (c) 2024 McAwful-Works
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package app.confuzion.backend.config;

import java.util.List;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocAnnotationsUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;

import app.confuzion.backend.model.record.ErrorResponseBodyRecord;
import app.confuzion.backend.model.record.ResponseRecord;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

/**
 * The OpenAPI document that springdoc serves and {@code docs/api/openapi.json}
 * commits. It adds what annotations can't express once: the session cookie and
 * CSRF header, and the error envelope on every operation (D-010).
 *
 * @author mcawful
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {
	/**
	 * The security scheme name for the session cookie.
	 */
	static final String SESSION_SCHEME = "session";

	/**
	 * The Spring Session cookie name.
	 */
	static final String SESSION_COOKIE = "SESSION";

	/**
	 * The cookie that carries the CSRF token (Spring's
	 * {@code CookieCsrfTokenRepository} default).
	 */
	static final String CSRF_COOKIE = "XSRF-TOKEN";

	/**
	 * The header that every state-changing request must echo the CSRF token in
	 * (Spring's {@code CookieCsrfTokenRepository} default).
	 */
	static final String CSRF_HEADER = "X-XSRF-TOKEN";

	/**
	 * Returns the document's info, server and security scheme. The server is
	 * {@code /} because the SPA calls the API on its own origin (D-003), which also
	 * keeps the document the same wherever it is generated.
	 *
	 * @return the base {@link OpenAPI}
	 */
	@Bean
	OpenAPI openApi() {
		return new OpenAPI().info(new Info().title("Con-Fuzion API").version("v1"))
				.servers(List.of(new Server().url("/")))
				.components(new Components().addSecuritySchemes(SESSION_SCHEME,
						new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.COOKIE)
								.name(SESSION_COOKIE)
								.description("Session cookie set by Discord sign-in. Every request other than GET must "
										+ "also send the " + CSRF_COOKIE + " cookie's value in the " + CSRF_HEADER
										+ " header.")))
				.addSecurityItem(new SecurityRequirement().addList(SESSION_SCHEME));
	}

	/**
	 * Adds the standard error responses to every operation, all with the error
	 * envelope as their body.
	 *
	 * @return the {@link OpenApiCustomizer}
	 */
	@Bean
	OpenApiCustomizer errorResponsesCustomizer() {
		return openApi -> {
			// Resolved the way springdoc resolves return types, so it gets the same name
			// and
			// OpenAPI 3.1 typing as the success envelopes.
			var errorEnvelope = SpringDocAnnotationsUtils.extractSchema(openApi.getComponents(),
					ResolvableType.forClassWithGenerics(ResponseRecord.class, ErrorResponseBodyRecord.class).getType(),
					null, null, openApi.getSpecVersion());
			var content = new Content().addMediaType(MediaType.APPLICATION_JSON_VALUE,
					new io.swagger.v3.oas.models.media.MediaType().schema(errorEnvelope));

			openApi.getPaths()
					.forEach((path, pathItem) -> pathItem.readOperationsMap()
							.forEach((method, operation) -> addErrorResponses(operation,
									method != PathItem.HttpMethod.GET, path.contains("{"), content)));
		};
	}

	/**
	 * Adds the error responses that apply to one operation, keeping any it already
	 * declares, and gives every error response the envelope as its body.
	 *
	 * @param operation    the {@link Operation}
	 * @param mutation     whether the operation changes state, so it needs the CSRF
	 *                     token
	 * @param hasPathParam whether the path has a parameter, which can name
	 *                     something that doesn't exist or that the caller can't see
	 * @param content      the error envelope {@link Content}
	 */
	private static void addErrorResponses(Operation operation, boolean mutation, boolean hasPathParam,
			Content content) {
		var responses = operation.getResponses();
		if (hasPathParam || operation.getRequestBody() != null || operation.getParameters() != null)
			responses.putIfAbsent("400", error("The request failed validation.", content));
		responses.putIfAbsent("401", error("Not signed in.", content));
		if (mutation)
			responses.putIfAbsent("403", error("CSRF token missing or invalid. Send the " + CSRF_COOKIE
					+ " cookie's value in the " + CSRF_HEADER + " header.", content));
		if (hasPathParam)
			responses.putIfAbsent("404", error("Not found, or not visible to the caller.", content));
		responses.putIfAbsent("429", error("Rate limited. Try again later.", content));
		responses.putIfAbsent("500", error("Unexpected server error.", content));
		responses.putIfAbsent("default", error("Any other error.", content));
		// springdoc gives an error declared with @ApiResponse the success body type;
		// every error is the envelope.
		responses.forEach((code, response) -> {
			if (code.startsWith("4") || code.startsWith("5"))
				response.setContent(content);
		});
	}

	/**
	 * Creates an error response.
	 *
	 * @param description the description
	 * @param content     the error envelope {@link Content}
	 * @return the {@link ApiResponse}
	 */
	private static ApiResponse error(String description, Content content) {
		return new ApiResponse().description(description).content(content);
	}
}
