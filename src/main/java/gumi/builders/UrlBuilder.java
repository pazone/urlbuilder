/*
Copyright 2011 Mikael Gueck

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package gumi.builders;

import com.google.common.base.Charsets;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import gumi.builders.url.RuntimeMalformedURLException;
import gumi.builders.url.RuntimeURISyntaxException;
import java.net.IDN;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Build and manipulate URLs easily.
 *
 * URL: http://www.ietf.org/rfc/rfc1738.txt
 * URI: http://tools.ietf.org/html/rfc3986
 * @author Mikael Gueck gumi@iki.fi
 */
public class UrlBuilder {

    private static final Charset DEFAULT_ENCODING = Charsets.UTF_8;

    private static final Pattern URI_PATTERN =
            Pattern.compile("^(([^:/?#]+):)?(//([^/?#]*))?([^?#]*)(\\?([^#]*))?(#(.*))?");

    /** Possible username and password, or only username. */
    private static final Pattern AUTHORITY_PATTERN =
            Pattern.compile("([^:]*)(:([0-9]*))?");

    private static final String DEFAULT_SCHEME = "http";

    private final Charset inputEncoding;

    private final Charset outputEncoding;

    private final String protocol;

    private final String hostName;

    private final Integer port;

    private final String path;

    private final Multimap<String, String> queryParameters;

    private final String anchor;

    /**
     * The builder public constructor isn't meant to be used,
     * but it's there if you need it.
     */
    public UrlBuilder() {
        this.inputEncoding = DEFAULT_ENCODING;
        this.outputEncoding = DEFAULT_ENCODING;
        this.protocol = DEFAULT_SCHEME;
        this.hostName = null;
        this.port = null;
        this.path = null;
        this.queryParameters = ImmutableMultimap.of();
        this.anchor = null;
    }

    private UrlBuilder(final Charset inputEncoding, final Charset outputEncoding,
            final String protocol, final String hostName, final Integer port,
            final String path, final Multimap<String, String> queryParameters,
            final String anchor)
    {
        this.inputEncoding = inputEncoding;
        this.outputEncoding = outputEncoding;
        this.protocol = protocol;
        this.hostName = hostName;
        this.port = port;
        this.path = path;
        this.queryParameters = ImmutableMultimap.copyOf(queryParameters);
        this.anchor = anchor;
    }
    
    public static UrlBuilder of(final Charset inputEncoding, final Charset outputEncoding,
            final String protocol, final String hostName, final Integer port,
            final String path, final Multimap<String, String> queryParameters,
            final String anchor) {
        return new UrlBuilder(inputEncoding, outputEncoding, protocol, hostName, port, path, queryParameters, anchor);
    }

    public UrlBuilder(final UrlBuilder parent) {
        this.inputEncoding = parent.inputEncoding;
        this.outputEncoding = parent.outputEncoding;
        this.protocol = parent.protocol;
        this.hostName = parent.hostName;
        this.port = parent.port;
        this.path = parent.path;
        this.queryParameters = ImmutableMultimap.copyOf(parent.queryParameters);
        this.anchor = parent.anchor;
    }

    public static UrlBuilder fromString(final String url) {
        return fromString(url, DEFAULT_ENCODING);
    }

    public static UrlBuilder fromEmpty() {
        return new UrlBuilder();
    }

    public static UrlBuilder fromString(final String url, final String inputEncoding) {
        return fromString(url, Charset.forName(inputEncoding));
    }
    
    public static UrlBuilder fromString(final String url, final Charset inputEncoding) {
        if (url.isEmpty()) {
            return new UrlBuilder();
        }
        final Matcher m = URI_PATTERN.matcher(url);
        String protocol = null, hostName = null, path = null, anchor = null;
        Integer port = null;
        Multimap<String, String> queryParameters = null;
        if (m.find()) {
            protocol = m.group(2);
            if (m.group(4) != null) {
                final Matcher n = AUTHORITY_PATTERN.matcher(m.group(4));
                if (n.find()) {
                    hostName = IDN.toUnicode(n.group(1));
                    if (n.group(3) != null) {
                        port = Integer.parseInt(n.group(3));
                    }
                }
            }
            path = decodePath(m.group(5), inputEncoding);
            queryParameters = decodeQueryParameters(m.group(7), inputEncoding);
            anchor = m.group(9);
        }
        return new UrlBuilder(inputEncoding, DEFAULT_ENCODING, protocol, hostName, port, path, queryParameters, anchor);
    }

    public static UrlBuilder fromUri(final URI uri) {
        try {
            return fromUrl(uri.toURL());
        } catch (MalformedURLException e) {
            return new UrlBuilder();
        }
    }

    public static UrlBuilder fromUrl(final URL url) {
        return new UrlBuilder(DEFAULT_ENCODING, DEFAULT_ENCODING,
                url.getProtocol(), url.getHost(), url.getPort(), url.getPath(),
                decodeQueryParameters(url.getQuery(), DEFAULT_ENCODING), url.getRef());
    }

    private static Multimap<String, String> decodeQueryParameters(final String query, final Charset inputEncoding) {
        final Multimap<String, String> ret = HashMultimap.create();
        if (query == null || query.isEmpty()) {
            return ret;
        }
        for (final String part : query.split("&")) {
            final String[] kvp = part.split("=", 2);
            final String key, value;
            key = urlDecode(kvp[0], inputEncoding);
            if (kvp.length == 2) {
                value = urlDecode(kvp[1], inputEncoding);
            } else {
                value = null;
            }
            ret.put(key, value);
        }
        return ret;
    }

    private static boolean isUrlSafe(final char c) {
        return ('a' <= c && 'z' >= c) ||
                ('A' <= c && 'Z' >= c) ||
                ('0' <= c && '9' >= c) ||
                (c == '-' || c == '_' || c == '.' || c == '~' || c == ' ');
    }

    private static String urlEncode(final String input, final Charset charset) {
        final StringBuilder sb = new StringBuilder();
        final CharBuffer cb = CharBuffer.allocate(1);
        for (final char c : input.toCharArray()) {
            if (c == ' ') {
                sb.append('+');
            } else if (isUrlSafe(c)) {
                sb.append(c);
            } else {
                cb.put(0, c);
                cb.rewind();
                final ByteBuffer bb = charset.encode(cb);
                for (int i = 0; i < bb.limit(); i++) {
                    sb.append('%');
                    sb.append(String.format("%1$02X", bb.get(i)));
                }
            }
        }
        return sb.toString();
    }

    private static byte[] nextDecodeableSequence(final String input, final int position) {
        final int len = input.length();
        final byte[] data = new byte[len];
        int j = 0;
        for (int i = position; i < len; i++) {
            final char c0 = input.charAt(i);
            if (c0 != '%' || (len < i + 2)) {
                return Arrays.copyOfRange(data, 0, j);
            } else {
                data[j++] = (byte) Integer.parseInt(input.substring(i + 1, i + 3), 16);
                i += 2;
            }
        }
        return Arrays.copyOfRange(data, 0, j);
    }

    private static String urlDecode(final String input, final Charset charset) {
        final StringBuilder sb = new StringBuilder();
        final int len = input.length();
        int j = 0, i = 0;
        for (i = 0; i < len; i++) {
            final char c0 = input.charAt(i);
            if (c0 == '+') {
                sb.append(' ');
            } else if (c0 != '%') {
                sb.append(c0);
            } else if (len < i + 2) {
                // the string will end before we will be able to read a sequence
                i += 2;
            } else {
                final byte[] bytes = nextDecodeableSequence(input, i);
                sb.append(charset.decode(ByteBuffer.wrap(bytes)));
                i += bytes.length * 3 - 1;
            }
        }
        return sb.toString();
    }

    protected String encodeQueryParameters() {
        final StringBuilder sb = new StringBuilder();
        for (final Map.Entry<String, String> e : this.queryParameters.entries()) {
            sb.append(urlEncode(e.getKey(), this.outputEncoding));
            if (e.getValue() != null) {
                sb.append('=');
                sb.append(urlEncode(e.getValue(), this.outputEncoding));
            }
            sb.append('&');
        }
        sb.deleteCharAt(sb.length() - 1);
        return sb.toString();
    }

    private static String encodePath(final String input, final Charset encoding) {
        final StringBuilder sb = new StringBuilder();
        if (input == null || input.isEmpty()) {
            return sb.toString();
        }
        final StringTokenizer st = new StringTokenizer(input, "/", true);
        while (st.hasMoreElements()) {
            final String element = st.nextToken();
            if ("/".equals(element)) {
                sb.append(element);
            } else if (element != null && !element.isEmpty()) {
                sb.append(urlEncode(element, encoding));
            }
        }
        return sb.toString();
    }

    private static String decodePath(final String input, final Charset encoding) {
        final StringBuilder sb = new StringBuilder();
        if (input == null || input.isEmpty()) {
            return sb.toString();
        }
        final StringTokenizer st = new StringTokenizer(input, "/", true);
        while (st.hasMoreElements()) {
            final String element = st.nextToken();
            if ("/".equals(element)) {
                sb.append(element);
            } else if (element != null && !element.isEmpty()) {
                sb.append(urlDecode(element, encoding));
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        if (this.protocol == null) {
            sb.append(DEFAULT_SCHEME);
        } else {
            sb.append(this.protocol);
        }
        sb.append("://");
        if (this.hostName != null) {
            sb.append(IDN.toASCII(this.hostName));
        }
        if (this.port != null) {
            sb.append(':');
            sb.append(this.port);
        }
        if (this.path != null) {
            sb.append(encodePath(this.path, this.outputEncoding));
        }
        if (!this.queryParameters.isEmpty()) {
            sb.append('?');
            sb.append(this.encodeQueryParameters());
        }
        if (this.anchor != null) {
            sb.append('#');
            sb.append(this.anchor);
        }
        return sb.toString();
    }

    public URI toUriWithException() throws URISyntaxException {
        return new URI(this.toString());
    }

    public URI toUri() throws RuntimeURISyntaxException {
        try {
            return toUriWithException();
        } catch (final URISyntaxException e) {
            throw new RuntimeURISyntaxException(e);
        }
    }

    public URL toUrlWithException() throws MalformedURLException {
        return new URL(this.toString());
    }

    public URL toUrl() throws RuntimeMalformedURLException {
        try {
            return toUrlWithException();
        } catch (final MalformedURLException e) {
            throw new RuntimeMalformedURLException(e);
        }
    }

    public UrlBuilder encodeAs(final Charset charset) {
        return of(inputEncoding, charset, protocol, hostName, port, path, queryParameters, anchor);
    }

    public UrlBuilder encodeAs(final String charsetName) {
        return of(inputEncoding, Charset.forName(charsetName), protocol, hostName, port, path, queryParameters, anchor);
    }


    public UrlBuilder withProtocol(final String protocol) {
        return of(inputEncoding, outputEncoding, protocol, hostName, port, path, queryParameters, anchor);
    }

    public UrlBuilder withHost(final String hostName) {
        return of(inputEncoding, outputEncoding, protocol, IDN.toUnicode(hostName), port, path, queryParameters, anchor);
    }

    public UrlBuilder withPort(final int port) {
        return of(inputEncoding, outputEncoding, protocol, hostName, port, path, queryParameters, anchor);
    }

    public UrlBuilder withPath(final String path) {
        return withPath(path, this.inputEncoding);
    }

    public UrlBuilder withPath(final String path, final Charset encoding) {
        return of(inputEncoding, outputEncoding, protocol, hostName, port, decodePath(path, encoding), queryParameters, anchor);
    }

    public UrlBuilder withPath(final String path, final String encoding) {
        return withPath(path, Charset.forName(encoding));
    }

    public UrlBuilder withQuery(final String query) {
        return of(inputEncoding, outputEncoding, protocol, hostName, port, path, decodeQueryParameters(query, inputEncoding), anchor);
    }

    public UrlBuilder withAnchor(final String anchor) {
        return of(inputEncoding, outputEncoding, protocol, hostName, port, path, queryParameters, anchor);
    }

    public UrlBuilder addParameter(final String key, final String value) {
        final Multimap<String, String> qp = HashMultimap.create(this.queryParameters);
        qp.put(key, value);
        return of(inputEncoding, outputEncoding, protocol, hostName, port, path, qp, anchor);
    }

    public UrlBuilder setParameter(final String key, final String value) {
        final Multimap<String, String> qp = HashMultimap.create(this.queryParameters);
        qp.removeAll(key);
        qp.put(key, value);
        return of(inputEncoding, outputEncoding, protocol, hostName, port, path, qp, anchor);
    }

    public UrlBuilder removeParameter(final String key, final String value) {
        final Multimap<String, String> qp = HashMultimap.create(this.queryParameters);
        qp.remove(key, value);
        return of(inputEncoding, outputEncoding, protocol, hostName, port, path, qp, anchor);
    }

    public UrlBuilder removeParameters(final String key) {
        final Multimap<String, String> qp = HashMultimap.create(this.queryParameters);
        qp.removeAll(key);
        return of(inputEncoding, outputEncoding, protocol, hostName, port, path, qp, anchor);
    }

}
