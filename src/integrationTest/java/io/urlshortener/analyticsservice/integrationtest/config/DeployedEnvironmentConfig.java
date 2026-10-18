package io.urlshortener.analyticsservice.integrationtest.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Wires the shared integration tests to already-deployed {@code analytics-service}, {@code url-service} and
 * {@code redirect-service} instances over real HTTP.
 *
 * <p>
 *     Needs nothing but their URLs: the traffic is generated through url-service and redirect-service, and the result
 *     is read from analytics-service, so no AWS or database access is required. The tests that do need direct access
 *     (fault injection, archive and mirror assertions) are {@code local}-only.
 * </p>
 *
 * <p>
 *     Active only under the {@code deployed} profile<br/>
 *     {@link LocalEnvironmentConfig} supplies the equivalent beans for the {@code local} profile.
 * </p>
 */
@Configuration
@Profile("deployed")
public class DeployedEnvironmentConfig {

	/**
	 * @param baseUrl the deployed analytics-service base URL.
	 * @return a client for it. A 4xx/5xx response is returned, not thrown, so tests can assert on error statuses.
	 */
	@Bean
	public RestClient analyticsApiClient(@Value("${integration-test.base-url}") final String baseUrl) {
		return client(baseUrl, HttpClient.Redirect.NORMAL);
	}

	/**
	 * @param baseUrl the deployed url-service base URL.
	 * @return a client for it. A 4xx/5xx response is returned, not thrown, so tests can assert on error statuses.
	 */
	@Bean
	public RestClient linksApiClient(@Value("${integration-test.url-service-base-url}") final String baseUrl) {
		return client(baseUrl, HttpClient.Redirect.NORMAL);
	}

	/**
	 * @param baseUrl the deployed redirect-service base URL.
	 * @return a client for it that does not follow redirects, since the tests assert on the 302 itself.
	 */
	@Bean
	public RestClient redirectApiClient(@Value("${integration-test.redirect-service-base-url}") final String baseUrl) {
		return client(baseUrl, HttpClient.Redirect.NEVER);
	}

	private static RestClient client(final String baseUrl, final HttpClient.Redirect redirect) {
		final HttpClient httpClient = HttpClient.newBuilder().followRedirects(redirect).build();
		return RestClient.builder()
				.baseUrl(baseUrl)
				.requestFactory(new JdkClientHttpRequestFactory(httpClient))
				.defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
				.build();
	}

}
