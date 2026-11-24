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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.util.UriUtils;

/**
 * Serves the built PWA that the {@code bootJar} task copies to
 * {@code classpath:/static/}. Hashed {@code /assets/**} files are cached for a
 * year. Everything else (the shell, the service worker, the manifest) is always
 * revalidated, and a path with no file extension that isn't under {@code /api}
 * falls back to {@code index.html} so client-side routes survive a reload.
 * <p>
 * {@code /api} is never shadowed: controllers and actuator endpoints are
 * matched before any resource handler, and an unmatched {@code /api/**} path
 * resolves to nothing, so it reaches the generic JSON 404. Boot's own static
 * resource handling is switched off in {@code application.properties} so this
 * class is the only one in charge of {@code /**}. Client routes must not have a
 * dot in their last segment, because that reads as a file name and gets a 404.
 *
 * @author mcawful
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {
	/**
	 * The classpath folder the SPA is copied into.
	 */
	private static final String STATIC_LOCATION = "classpath:/static/";

	/**
	 * The path prefix of the REST API.
	 */
	private static final String API_PREFIX = "/api";

	/**
	 * Sends {@code /} to the shell. A resource handler never serves an empty path.
	 *
	 * @param registry the {@link ViewControllerRegistry}
	 */
	@Override
	public void addViewControllers(ViewControllerRegistry registry) {
		registry.addViewController("/").setViewName("forward:/index.html");
	}

	/**
	 * Registers the cached asset handler and the SPA fallback handler.
	 *
	 * @param registry the {@link ResourceHandlerRegistry}
	 */
	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/assets/**").addResourceLocations(STATIC_LOCATION + "assets/")
				.setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
		registry.addResourceHandler("/**").addResourceLocations(STATIC_LOCATION).setCacheControl(CacheControl.noCache())
				.resourceChain(false).addResolver(new SpaFallbackResolver());
	}

	/**
	 * Resolves a file when it exists and otherwise falls back to {@code index.html}
	 * for route-like paths.
	 */
	static class SpaFallbackResolver extends PathResourceResolver {
		/**
		 * Returns the requested file, or {@code index.html} for a path that is a client
		 * route.
		 *
		 * @param resourcePath the path relative to the handler mapping
		 * @param location     the static location to look in
		 * @return the resource, or {@code null} when there is none
		 * @throws IOException when the lookup fails
		 */
		@Override
		protected Resource getResource(String resourcePath, Resource location) throws IOException {
			var resource = super.getResource(resourcePath, location);
			if (resource != null || !isClientRoute(resourcePath))
				return resource;
			var index = location.createRelative("index.html");
			return index.isReadable() ? index : null;
		}

		/**
		 * Returns whether the path should show the SPA shell: it isn't an API path and
		 * its last segment has no file extension.
		 *
		 * @param resourcePath the path relative to the handler mapping (no leading
		 *                     slash)
		 * @return {@code true} for a client-side route
		 */
		private static boolean isClientRoute(String resourcePath) {
			String path;
			try {
				path = "/" + UriUtils.decode(resourcePath, StandardCharsets.UTF_8);
			} catch (IllegalArgumentException e) {
				return false;
			}
			if (path.equals(API_PREFIX) || path.startsWith(API_PREFIX + "/"))
				return false;
			return path.indexOf('.', path.lastIndexOf('/')) < 0;
		}
	}
}
