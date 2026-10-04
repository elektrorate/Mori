package com.mori.downloader;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class MoriDriveValidationTest {
    private static final String MD5 = "0123456789abcdef0123456789abcdef";

    @Test public void rejectsPathsAndControlCharacters() throws Exception {
        assertEquals("report.pdf", MoriDriveValidation.fileName(" report.pdf "));
        for (String name : new String[]{"", "..", " .. ", "../video.mp4", "a\\b.pdf", "a\nb.pdf", "a\nb\nc.pdf", "a\u0000b"}) {
            assertThrows(IOException.class, () -> MoriDriveValidation.fileName(name));
        }
    }

    @Test public void trustsOnlyServerAcknowledgedRanges() throws Exception {
        assertEquals(0, MoriDriveValidation.offset(null, 100));
        assertEquals(43, MoriDriveValidation.offset("bytes=0-42", 100));
        for (String range : new String[]{"bytes=1-42", "bytes=0-100", "bytes=0--1", "garbage",
                "bytes=0-9999999999999999999999999"}) {
            assertThrows(IOException.class, () -> MoriDriveValidation.offset(range, 100));
        }
    }

    @Test public void deletionRequiresIdSizeChecksumAndExactParent() {
        assertTrue(MoriDriveValidation.verified("id", "id", 42, 42, MD5, MD5, "folder", "folder", 1, false));
        assertFalse(MoriDriveValidation.verified("id", "other", 42, 42, MD5, MD5, "folder", "folder", 1, false));
        assertFalse(MoriDriveValidation.verified("id", "id", 42, 41, MD5, MD5, "folder", "folder", 1, false));
        assertFalse(MoriDriveValidation.verified("id", "id", 42, 42, MD5, "", "folder", "folder", 1, false));
        assertFalse(MoriDriveValidation.verified("id", "id", 42, 42, MD5, MD5, "folder", "root", 1, false));
        assertFalse(MoriDriveValidation.verified("id", "id", 42, 42, MD5, MD5, "folder", "folder", 2, false));
        assertFalse(MoriDriveValidation.verified("id", "id", 42, 42, MD5, MD5, "folder", "folder", 1, true));
        assertFalse(MoriDriveValidation.verified("id", "id", 42, 42, MD5, MD5, "folder", "folder", 1, null));
        assertFalse(MoriDriveValidation.verified("id", "id", 42, 42, MD5, MD5, "folder", "folder", 1, "false"));
    }

    @Test public void preservesPublicContentIdsWithoutCredentialsOrTracking() {
        assertEquals("https://www.facebook.com/story.php?id=123&story_fbid=456", MoriDriveBackend.publicSource(
            "https://www.facebook.com/story.php?id=123&story_fbid=456&access_token=private&utm_source=tracker"));
        assertEquals("https://www.facebook.com/watch?v=987", MoriDriveBackend.publicSource(
            "https://www.facebook.com/watch?v=987&token=private"));
        assertEquals("https://www.terabox.com/sharing/link?surl=Public_Id", MoriDriveBackend.publicSource(
            "https://www.terabox.com/sharing/link?surl=Public_Id&pwd=private&access_token=private"));
        assertEquals("https://www.terabox.com/sharing/link?surl=Other_Id", MoriDriveBackend.publicSource(
            "https://www.terabox.com/sharing/link?surl=Other_Id"));
        assertEquals("https://facebook.com.attacker.invalid/story.php", MoriDriveBackend.publicSource(
            "https://facebook.com.attacker.invalid/story.php?id=123&story_fbid=456"));
    }

    @Test public void sharePolicyServesOnlyPackagedLocalOrigins() {
        assertTrue(MoriSharePolicy.page("https://localhost/share.html"));
        assertTrue(MoriSharePolicy.page("http://localhost/share.html"));
        assertTrue(MoriSharePolicy.page("file:///android_asset/public/share.html"));
        assertEquals("js/share.js", MoriSharePolicy.asset("https://localhost/js/share.js"));
        for (String url : new String[]{"https://localhost.attacker.invalid/share.html", "https://localhost:444/share.html",
                "https://user@localhost/share.html", "file:///data/data/com.mori.downloader/files/secret",
                "https://localhost/%2e%2e/private", "https://localhost/index.html", "content://app/share.html"}) {
            assertNull(MoriSharePolicy.asset(url));
            assertFalse(MoriSharePolicy.page(url));
        }
    }

    @Test public void remoteResourcesCannotSupplyExecutableContent() {
        assertTrue(MoriSharePolicy.remoteType("https://cdn.example/photo.jpg", "image/jpeg"));
        assertTrue(MoriSharePolicy.remoteType("https://cdn.example/movie.mp4", "video/mp4"));
        assertTrue(MoriSharePolicy.remoteType("https://fonts.googleapis.com/css2", "text/css"));
        assertTrue(MoriSharePolicy.remoteType("https://fonts.gstatic.com/font.woff2", "font/woff2"));
        assertFalse(MoriSharePolicy.remoteType("https://cdn.example/page.html", "text/html"));
        assertFalse(MoriSharePolicy.remoteType("https://cdn.example/code.js", "application/javascript"));
        assertFalse(MoriSharePolicy.remoteType("https://cdn.example/active.svg", "image/svg+xml"));
        assertFalse(MoriSharePolicy.remoteType("https://cdn.example/style.css", "text/css"));
        assertFalse(MoriSharePolicy.remoteType("http://cdn.example/photo.jpg", "image/jpeg"));
        assertTrue(MoriSharePolicy.CSP.contains("frame-src 'none'"));
        assertTrue(MoriSharePolicy.CSP.contains("worker-src 'none'"));
    }
}
