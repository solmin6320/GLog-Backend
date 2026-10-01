package com.jandilog.testsupport.auth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

// 실제 HTTP로 /graphql을 부른다. 보안 필터 체인까지 거치는 호출을 만들기 위해 MockMvc 대신 쓴다
public class GraphQlHttpClient {

	private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
	private final URI uri;
	private final ObjectMapper mapper;

	public GraphQlHttpClient(int port, ObjectMapper mapper) {
		this.uri = URI.create("http://localhost:" + port + "/graphql");
		this.mapper = mapper;
	}

	public GraphQlResponse post(String bearerToken, String query) {
		return post(bearerToken, query, Map.of());
	}

	public GraphQlResponse post(String bearerToken, String query, Map<String, Object> variables) {
		return send(bearerToken == null ? null : "Bearer " + bearerToken, query, variables);
	}

	// Authorization 헤더 값을 그대로 넣는다 (null이면 헤더 없음)
	public GraphQlResponse send(String authorization, String query, Map<String, Object> variables) {
		try {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("query", query);
			payload.put("variables", variables);
			HttpRequest.Builder request = HttpRequest.newBuilder(uri)
					.header("Content-Type", "application/json")
					.header("Accept", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
			if (authorization != null) {
				request.header("Authorization", authorization);
			}
			HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
			JsonNode body = response.body() == null || response.body().isBlank()
					? MissingNode.getInstance() : mapper.readTree(response.body());
			return new GraphQlResponse(response.statusCode(), body, response.headers(), response.body());
		} catch (IOException e) {
			throw new IllegalStateException("GraphQL 호출 실패", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("GraphQL 호출 중단", e);
		}
	}

	// GraphQL이 아닌 경로(헬스체크, 노출되지 않은 actuator 등)를 GET으로 부른다
	public GraphQlResponse get(String path, String bearerToken) {
		try {
			HttpRequest.Builder request = HttpRequest.newBuilder(uri.resolve(path)).GET();
			if (bearerToken != null) {
				request.header("Authorization", "Bearer " + bearerToken);
			}
			HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
			JsonNode body;
			try {
				body = response.body() == null || response.body().isBlank()
						? MissingNode.getInstance() : mapper.readTree(response.body());
			} catch (IOException notJson) {
				body = MissingNode.getInstance();
			}
			return new GraphQlResponse(response.statusCode(), body, response.headers(), response.body());
		} catch (IOException e) {
			throw new IllegalStateException("GET 호출 실패", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("GET 호출 중단", e);
		}
	}

	public record GraphQlResponse(int status, JsonNode body, HttpHeaders headers, String rawBody) {

		public JsonNode data() {
			return body.path("data");
		}

		public boolean hasErrors() {
			return body.path("errors").isArray() && !body.path("errors").isEmpty();
		}

		public String errorCode() {
			return body.path("errors").path(0).path("extensions").path("code").asText(null);
		}

		public String errorClassification() {
			return body.path("errors").path(0).path("extensions").path("classification").asText(null);
		}

		public boolean dataIsNull() {
			return data().isNull() || data().isMissingNode();
		}

		public String errorMessage() {
			return body.path("errors").path(0).path("message").asText(null);
		}

		public String header(String name) {
			return headers.firstValue(name).orElse(null);
		}

	}

}
