package com.darkrich.blog.module.importer;

import com.darkrich.blog.common.BusinessException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * 管理员导入链接时用的 HTTP 客户端。
 *
 * <p>必须防 SSRF：目标由管理员填写，但不能让服务器去打内网、本机或云元数据。
 * 校验在「发请求之前」做：协议、端口、禁止 userinfo、解析出的每个 IP 都必须是公网地址。
 * Java HttpClient 内部可能再次解析 DNS（DNS rebinding 的理论窗口），个人博客这个量级先挡明显误用。
 *
 * <p>重定向自己跟：HttpClient 的自动跟随不会对 Location 再做公网校验。
 */
@Component
public class SafeHttpClient {

    static final int MAX_BODY_BYTES = 1_500_000;
    private static final int MAX_REDIRECTS = 5;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final String UA =
            "Mozilla/5.0 (compatible; DarkrichBlogImporter/1.0; +https://blog.darkrich.com)";
    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost", "metadata.google.internal", "metadata.google.com",
            "kubernetes.default", "kubernetes.default.svc");

    private final HttpClient client;

    public SafeHttpClient() {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /** 测试可注入，避免单测打真实网络。 */
    SafeHttpClient(HttpClient client) {
        this.client = client;
    }

    public record Fetched(URI url, String contentType, String body) {
    }

    public Fetched get(URI uri) {
        return exchange("GET", uri, null);
    }

    public Fetched postJson(URI uri, String json) {
        return exchange("POST", uri, json);
    }

    /**
     * 解析并做 SSRF 预检。失败抛 400，文案给管理员看，不带解析出的内网地址。
     */
    public static URI parsePublicHttpUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BusinessException.badRequest("链接为空");
        }
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            throw BusinessException.badRequest("链接格式不正确");
        }
        requirePublic(uri);
        return uri;
    }

    static void requirePublic(URI uri) {
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw BusinessException.badRequest("只支持 http(s) 链接");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw BusinessException.badRequest("只支持 http(s) 链接");
        }
        // 限制 80/443：挡住打到 22/3306/6379 这类内网服务端口
        int port = uri.getPort();
        boolean httpBadPort = scheme.equals("http") && port != -1 && port != 80;
        boolean httpsBadPort = scheme.equals("https") && port != -1 && port != 443;
        if (httpBadPort || httpsBadPort) {
            throw BusinessException.badRequest("只允许访问 80 / 443 端口");
        }
        if (uri.getUserInfo() != null) {
            throw BusinessException.badRequest("链接不能包含用户名和密码");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (BLOCKED_HOSTS.contains(host) || host.endsWith(".localhost") || host.endsWith(".internal")) {
            throw BusinessException.badRequest("不能访问内网或本机地址");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw BusinessException.badRequest("无法解析该域名");
        }
        if (addresses.length == 0) {
            throw BusinessException.badRequest("无法解析该域名");
        }
        for (InetAddress addr : addresses) {
            if (!isPublicAddress(addr)) {
                throw BusinessException.badRequest("不能访问内网或本机地址");
            }
        }
    }

    /** 公网单播才放行。链路本地（含 169.254.169.254 元数据）、站点本地、回环、组播、IPv6 ULA 全拦。 */
    static boolean isPublicAddress(InetAddress addr) {
        if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = addr.getAddress();
        // IPv6 unique local fc00::/7
        if (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc) {
            return false;
        }
        return true;
    }

    private Fetched exchange(String method, URI start, String jsonBody) {
        URI current = start;
        boolean first = true;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            requirePublic(current);
            HttpRequest.Builder builder = HttpRequest.newBuilder(current)
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.1");
            if (first && jsonBody != null) {
                builder.header("Content-Type", "application/json; charset=utf-8");
                builder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            } else {
                // 重定向一律改 GET，避免把 POST 体带到未知 Location
                builder.GET();
            }
            first = false;

            HttpResponse<byte[]> response;
            try {
                response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw BusinessException.badRequest("访问该链接被中断");
            } catch (IOException e) {
                throw BusinessException.badRequest("无法访问该链接");
            }

            int code = response.statusCode();
            if (code >= 300 && code < 400) {
                String location = response.headers().firstValue("Location").orElse("");
                if (location.isBlank()) {
                    throw BusinessException.badRequest("目标站点重定向缺少地址");
                }
                current = current.resolve(location);
                continue;
            }
            if (code >= 400) {
                throw BusinessException.badRequest("目标站点返回 " + code);
            }
            byte[] body = response.body() == null ? new byte[0] : response.body();
            if (body.length > MAX_BODY_BYTES) {
                throw BusinessException.badRequest("页面过大，无法导入");
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("text/html");
            return new Fetched(current, contentType, new String(body, charsetOf(contentType)));
        }
        throw BusinessException.badRequest("重定向次数过多");
    }

    private static Charset charsetOf(String contentType) {
        int idx = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (idx < 0) {
            return StandardCharsets.UTF_8;
        }
        String name = contentType.substring(idx + 8).split("[;\\s]", 2)[0].replace("\"", "").trim();
        try {
            return Charset.forName(name);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }
}
