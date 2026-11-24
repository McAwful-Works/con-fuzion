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
package app.confuzion.backend;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import app.confuzion.backend.controller.IntegrationProbeController;

/**
 * Lets anonymous callers reach the {@link IntegrationProbeController} probes,
 * ahead of the app's deny-by-default chain, so they test the runtime foundation
 * rather than sign-in. It lives outside the controller package so the
 * controller aspects don't advise it.
 *
 * @author mcawful
 */
@Configuration(proxyBeanMethods = false)
@Profile(IntegrationProbeController.PROFILE)
public class IntegrationProbeSecurity {
	/**
	 * Builds the probe chain.
	 *
	 * @param http the {@link HttpSecurity}
	 * @return the {@link SecurityFilterChain}
	 * @throws Exception when the configuration fails
	 */
	@Bean
	@Order(0)
	SecurityFilterChain probeSecurityFilterChain(HttpSecurity http) throws Exception {
		return http.securityMatcher("/api/test-probe/**")
				.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll()).build();
	}
}
