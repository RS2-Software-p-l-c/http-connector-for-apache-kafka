/*
 * Copyright 2023 Aiven Oy and http-connector-for-apache-kafka project contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.aiven.kafka.connect.http.sender;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.sink.SinkRecord;

import io.aiven.kafka.connect.http.config.HttpSinkConfig;
import io.aiven.kafka.connect.http.converter.RecordValueConverter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

abstract class AbstractHttpSender {

    private static final Logger log = LoggerFactory.getLogger(AbstractHttpSender.class);

    protected final HttpClient httpClient;
    protected final HttpSinkConfig config;
    protected final HttpRequestBuilder httpRequestBuilder;

    protected static final String HTTP_HEADER_UNIX_PLACEHOLDER = "${unix-timestamp}";

    protected AbstractHttpSender(
        final HttpSinkConfig config, final HttpRequestBuilder httpRequestBuilder, final HttpClient httpClient
    ) {
        this.config = Objects.requireNonNull(config);
        this.httpRequestBuilder = Objects.requireNonNull(httpRequestBuilder);
        this.httpClient = Objects.requireNonNull(httpClient);
    }

    public final HttpResponse<String> send(final String body) {
        final var requestBuilder =
            httpRequestBuilder.build(config).POST(HttpRequest.BodyPublishers.ofString(body));
        return sendWithRetries(requestBuilder, HttpResponseHandler.ON_HTTP_ERROR_RESPONSE_HANDLER,
                               config.maxRetries());
    }

    public final HttpResponse<String> send(final SinkRecord record, final RecordValueConverter recordValueConverter) {
        final var requestBuilder =
            httpRequestBuilder.build(config)
                .POST(HttpRequest.BodyPublishers.ofString(recordValueConverter.convert(record)));
        record.headers().forEach(header -> requestBuilder.header(header.key(), header.value().toString()));

        return sendWithRetries(requestBuilder, HttpResponseHandler.ON_HTTP_ERROR_RESPONSE_HANDLER,
                               config.maxRetries());
    }

    /**
     * Sends an HTTP body using {@code httpSender}, respecting the configured retry policy.
     *
     * @return whether the sending was successful.
     */
    protected HttpResponse<String> sendWithRetries(
        final Builder requestBuilderWithPayload, final HttpResponseHandler httpResponseHandler,
        final int retries
    ) {
        int remainingRetries = retries;
        while (remainingRetries >= 0) {
            try {
                try {
                    final HttpRequest request = refreshHeaders(requestBuilderWithPayload.build());
                    final var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                    log.debug("Server replied with status code {} and body {}", response.statusCode(), response.body());
                    // Handle the response
                    httpResponseHandler.onResponse(response, remainingRetries);
                    return response;
                } catch (final IOException e) {
                    log.info("Sending failed, will retry in {} ms ({} retries remain)", config.retryBackoffMs(),
                             remainingRetries, e);
                    remainingRetries -= 1;
                    TimeUnit.MILLISECONDS.sleep(config.retryBackoffMs());
                }
            } catch (final InterruptedException e) {
                log.error("Sending failed due to InterruptedException, stopping", e);
                throw new ConnectException(e);
            }
        }
        log.error("Sending failed and no retries remain, stopping");
        throw new ConnectException("Sending failed and no retries remain, stopping");
    }

    /**
     * Refreshes the headers in the given HTTP request by replacing placeholders with dynamically generated values.
     * Specifically, it replaces occurrences of {@code HTTP_HEADER_UNIX_PLACEHOLDER} with the current system timestamp.
     *
     * @param request - The original HTTP request to be modified.
     *
     * @return A new {@link HttpRequest} instance with updated headers.
     */
    protected HttpRequest refreshHeaders(final HttpRequest request) {
        // Duplicate the request
        final HttpRequest.Builder newRequestBuilder = HttpRequest.newBuilder(request.uri())
            .method(request.method(), request.bodyPublisher().orElse(null));

        // Iterate through the headers and replace the placeholders
        request.headers().map().forEach((key, values) -> {
            for (final String value : values) {
                final String updatedValue = value.replace(HTTP_HEADER_UNIX_PLACEHOLDER,
                                                          String.valueOf(System.currentTimeMillis()));
                newRequestBuilder.header(key, updatedValue);
            }
        });

        return newRequestBuilder.build();
    }
}
