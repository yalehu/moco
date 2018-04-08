package com.github.dreamhead.moco.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.github.dreamhead.moco.HttpMethod;
import com.github.dreamhead.moco.HttpProtocolVersion;
import com.github.dreamhead.moco.HttpRequest;
import com.github.dreamhead.moco.extractor.CookiesRequestExtractor;
import com.github.dreamhead.moco.extractor.FormsRequestExtractor;
import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.QueryStringEncoder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.dreamhead.moco.model.MessageContent.content;
import static com.github.dreamhead.moco.util.Maps.listValueToArray;
import static com.github.dreamhead.moco.util.Maps.simpleValueToArray;
import static com.google.common.collect.ImmutableMap.copyOf;

@JsonDeserialize(builder = DefaultHttpRequest.Builder.class)
public final class DefaultHttpRequest extends DefaultHttpMessage implements HttpRequest {
    private final Supplier<ImmutableMap<String, String>> formSupplier;
    private final Supplier<ImmutableMap<String, String>> cookieSupplier;

    private final HttpMethod method;

    private final String uri;
    private final ImmutableMap<String, String[]> queries;

    private DefaultHttpRequest(final HttpProtocolVersion version, final MessageContent content,
                               final HttpMethod method, final String uri,
                               final ImmutableMap<String, String[]> headers,
                               final ImmutableMap<String, String[]> queries) {
        super(version, content, headers);
        this.method = method;
        this.uri = uri;
        this.queries = queries;
        this.formSupplier = formSupplier();
        this.cookieSupplier = cookieSupplier();
    }

    @Override
    public HttpMethod getMethod() {
        return method;
    }

    @Override
    public String getUri() {
        return uri;
    }

    @JsonIgnore
    public ImmutableMap<String, String> getForms() {
        return formSupplier.get();
    }

    @JsonIgnore
    public ImmutableMap<String, String> getCookies() {
        return cookieSupplier.get();
    }

    @Override
    public ImmutableMap<String, String[]> getQueries() {
        return queries;
    }

    private Supplier<ImmutableMap<String, String>> formSupplier() {
        return Suppliers.memoize(new Supplier<ImmutableMap<String, String>>() {
            @Override
            public ImmutableMap<String, String> get() {
                Optional<ImmutableMap<String, String>> forms =
                        new FormsRequestExtractor().extract(DefaultHttpRequest.this);
                return toResult(forms);
            }
        });
    }

    private ImmutableMap<String, String> toResult(final Optional<ImmutableMap<String, String>> result) {
        if (result.isPresent()) {
            return result.get();
        }

        return ImmutableMap.of();
    }

    private Supplier<ImmutableMap<String, String>> cookieSupplier() {
        return Suppliers.memoize(new Supplier<ImmutableMap<String, String>>() {
            @Override
            public ImmutableMap<String, String> get() {
                Optional<ImmutableMap<String, String>> cookies =
                        new CookiesRequestExtractor().extract(DefaultHttpRequest.this);
                return toResult(cookies);
            }
        });
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper("HTTP Request")
                .omitNullValues()
                .add("uri", this.uri)
                .add("version", this.getVersion())
                .add("method", this.method)
                .add("queries", this.queries)
                .add("headers", this.getHeaders())
                .add("content", this.getContent())
                .toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    private static MessageContent toMessageContent(final FullHttpRequest request) {
        long contentLength = HttpUtil.getContentLength(request, -1);
        if (contentLength <= 0) {
            return content().build();
        }

        return content()
                .withCharset(HttpUtil.getCharset(request))
                .withContent(new ByteBufInputStream(request.content()))
                .build();
    }

    public static HttpRequest newRequest(final FullHttpRequest request) {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        ImmutableMap<String, String[]> queries = toQueries(decoder);

        return builder()
                .withVersion(HttpProtocolVersion.versionOf(request.protocolVersion().text()))
                .withHeaders(toHeaders(request.headers()))
                .withMethod(HttpMethod.valueOf(request.method().toString().toUpperCase()))
                .withUri(decoder.path())
                .withQueries(queries)
                .withContent(toMessageContent(request))
                .build();
    }

    private static ImmutableMap<String, String[]> toQueries(final QueryStringDecoder decoder) {
        ImmutableMap.Builder<String, String[]> builder = ImmutableMap.builder();
        for (Map.Entry<String, List<String>> entry : decoder.parameters().entrySet()) {
            List<String> value = entry.getValue();
            builder.put(entry.getKey(), value.toArray(new String[value.size()]));
        }
        return builder.build();
    }

    public FullHttpRequest toFullHttpRequest() {
        ByteBuf buffer = Unpooled.buffer();
        MessageContent content = getContent();
        if (content != null) {
            buffer.writeBytes(content.getContent());
        }

        QueryStringEncoder encoder = new QueryStringEncoder(uri);
        for (Map.Entry<String, String[]> entry : queries.entrySet()) {
            String[] values = entry.getValue();
            for (String value : values) {
                encoder.addParam(entry.getKey(), value);
            }
        }

        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.valueOf(getVersion().text()),
                io.netty.handler.codec.http.HttpMethod.valueOf(method.name()), encoder.toString(), buffer);

        for (Map.Entry<String, String[]> entry : getHeaders().entrySet()) {
            String key = entry.getKey();
            for (String value : entry.getValue()) {
                request.headers().add(key, value);
            }
        }

        return request;
    }

    private static ImmutableMap<String, String[]> toHeaders(final Iterable<Map.Entry<String, String>> httpHeaders) {
        Map<String, List<String>> headers = new HashMap<>();
        for (Map.Entry<String, String> entry : httpHeaders) {
            String key = entry.getKey();
            List<String> values = getValues(headers, key);
            values.add(entry.getValue());
            headers.put(key, values);
        }

        Map<String, String[]> results = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            List<String> value = entry.getValue();
            results.put(entry.getKey(), value.toArray(new String[value.size()]));
        }

        return copyOf(results);
    }

    private static List<String> getValues(final Map<String, List<String>> headers, final String key) {
        if (headers.containsKey(key)) {
            return headers.get(key);
        }

        return new ArrayList<>();
    }

    public static final class Builder extends DefaultHttpMessage.Builder<Builder> {
        private HttpMethod method;
        private String uri;
        private ImmutableMap<String, String[]> queries;

        public Builder withMethod(final HttpMethod method) {
            this.method = method;
            return this;
        }

        public Builder withUri(final String uri) {
            this.uri = uri;
            return this;
        }

        public Builder withQueries(final Map<String, String[]> queries) {
            if (queries != null) {
                this.queries = copyOf(queries);
            }

            return this;
        }

        public DefaultHttpRequest build() {
            return new DefaultHttpRequest(version, content, method, this.uri, this.headers, this.queries);
        }
    }
}
