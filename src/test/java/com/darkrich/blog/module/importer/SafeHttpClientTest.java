package com.darkrich.blog.module.importer;

import com.darkrich.blog.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeHttpClientTest {

    @Test
    void rejectsNonHttpSchemesAndUserinfo() {
        assertThrows(BusinessException.class, () -> SafeHttpClient.parsePublicHttpUrl("file:///etc/passwd"));
        assertThrows(BusinessException.class, () -> SafeHttpClient.parsePublicHttpUrl("javascript:alert(1)"));
        assertThrows(BusinessException.class, () -> SafeHttpClient.parsePublicHttpUrl("https://user:pass@example.com/"));
        assertThrows(BusinessException.class, () -> SafeHttpClient.parsePublicHttpUrl("https://example.com:8080/"));
    }

    @Test
    void rejectsLoopbackAndPrivateAddresses() throws Exception {
        assertFalse(SafeHttpClient.isPublicAddress(InetAddress.getByName("127.0.0.1")));
        assertFalse(SafeHttpClient.isPublicAddress(InetAddress.getByName("::1")));
        assertFalse(SafeHttpClient.isPublicAddress(InetAddress.getByName("10.0.0.1")));
        assertFalse(SafeHttpClient.isPublicAddress(InetAddress.getByName("192.168.1.1")));
        assertFalse(SafeHttpClient.isPublicAddress(InetAddress.getByName("169.254.169.254")));
        assertFalse(SafeHttpClient.isPublicAddress(InetAddress.getByName("0.0.0.0")));
        assertTrue(SafeHttpClient.isPublicAddress(InetAddress.getByName("8.8.8.8")));
        assertThrows(BusinessException.class, () -> SafeHttpClient.requirePublic(URI.create("http://127.0.0.1/")));
        assertThrows(BusinessException.class, () -> SafeHttpClient.requirePublic(URI.create("http://localhost/x")));
        assertThrows(BusinessException.class, () -> SafeHttpClient.requirePublic(URI.create("http://10.1.2.3/")));
    }

    @Test
    void extractsJuejinPostId() {
        assertTrue("123".equals(ArticleUrlFetcher.juejinPostId(URI.create("https://juejin.cn/post/123"))));
        assertTrue("99".equals(ArticleUrlFetcher.juejinPostId(URI.create("https://www.juejin.im/post/99?a=1"))));
        assertTrue(ArticleUrlFetcher.juejinPostId(URI.create("https://juejin.cn/user/1")) == null);
    }
}
