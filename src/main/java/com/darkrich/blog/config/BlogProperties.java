package com.darkrich.blog.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 博客自定义配置（前缀 {@code blog}）。
 *
 * <p>全部带校验：口令哈希格式不对、JWT 密钥过短时应用直接启动失败，
 * 比起运行时才发现“登录永远失败”或“密钥可被爆破”，这里宁可早崩。
 *
 * @param admin  唯一管理员账号
 * @param jwt    登录令牌配置
 * @param cookie 令牌 Cookie 配置
 * @param login  登录防爆破配置
 */
@Validated
@ConfigurationProperties(prefix = "blog")
public record BlogProperties(
        @Valid @NotNull Admin admin,
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull Cookie cookie,
        @Valid @DefaultValue Login login,
        @Valid @DefaultValue Translation translation) {

    // record 有多个构造器时 Boot 无法推断绑定哪个，会退回 JavaBean 绑定并报 "No default constructor found"，
    // 所以必须在规范构造器上显式标注。
    @ConstructorBinding
    public BlogProperties {
    }

    /** Backward-compatible constructor for focused unit tests and small integrations. */
    public BlogProperties(Admin admin, Jwt jwt, Cookie cookie, Login login) {
        this(admin, jwt, cookie, login,
                new Translation(false, "https://api.openai.com/v1", "", "gpt-4o-mini", 2));
    }

    /**
     * @param username     管理员用户名
     * @param passwordHash BCrypt 哈希（不存明文）。限定 $2a/$2b/$2y 前缀，拦住“误把明文密码填进来”的情况
     */
    public record Admin(
            @NotBlank String username,
            @NotBlank @Pattern(regexp = "^\\$2[aby]\\$\\d{2}\\$.{53}$", message = "必须是 BCrypt 哈希")
            String passwordHash) {
    }

    /**
     * @param secret HMAC-SHA256 密钥。RFC 7518 要求至少 256 bit，即 32 字节；
     *               这里按字符数卡 32，ASCII 下恰好满足，更短的密钥 jjwt 也会拒绝
     * @param ttl    令牌有效期
     */
    public record Jwt(
            @NotBlank @Size(min = 32, message = "JWT 密钥至少 32 个字符") String secret,
            @NotNull @DefaultValue("12h") Duration ttl) {
    }

    /**
     * @param name   Cookie 名
     * @param secure 是否仅 HTTPS 传输。线上必须为 true，仅本地 http 开发时关闭
     */
    public record Cookie(
            @NotBlank @DefaultValue("blog_token") String name,
            @DefaultValue("true") boolean secure) {
    }

    /**
     * @param maxFailures 窗口内允许的最大失败次数，超过即锁定
     * @param window      统计窗口，同时也是锁定时长
     */
    public record Login(
            @Min(1) @Max(100) @DefaultValue("5") int maxFailures,
            @NotNull @DefaultValue("15m") Duration window) {
    }

    /**
     * OpenAI-compatible translation provider. Disabled by default so a local or
     * existing production deployment never starts making paid outbound calls
     * until the operator explicitly enables it.
     */
    public record Translation(
            @DefaultValue("false") boolean enabled,
            @NotBlank @DefaultValue("https://api.openai.com/v1") String baseUrl,
            @DefaultValue("") String apiKey,
            @NotBlank @DefaultValue("gpt-4o-mini") String model,
            @Min(1) @Max(10) @DefaultValue("2") int batchSize) {
    }
}
