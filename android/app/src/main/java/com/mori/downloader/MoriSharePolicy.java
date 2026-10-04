package com.mori.downloader;

import java.net.URI;
import java.util.Locale;

/** Privileged documents are packaged assets only; remote content is never a document or script. */
final class MoriSharePolicy {
    static final String PAGE = "https://localhost/share.html";
    static final String CSP = "default-src 'none'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; "
        + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
        + "img-src https: data: blob:; media-src https: blob:; font-src 'self' https://fonts.gstatic.com data:; "
        + "connect-src 'self' https:; frame-src 'none'; child-src 'none'; object-src 'none'; "
        + "worker-src 'none'; base-uri 'none'; form-action 'none'";

    private MoriSharePolicy() {}

    static String asset(String raw) {
        try {
            URI uri = new URI(raw);
            if (uri.getRawUserInfo() != null) return null;
            String path = uri.getPath();
            String asset;
            if ("file".equals(uri.getScheme()) && (uri.getRawAuthority() == null || uri.getRawAuthority().isEmpty())
                    && path.startsWith("/android_asset/public/")) {
                asset = path.substring("/android_asset/public/".length());
            } else if (("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    && "localhost".equals(uri.getHost()) && (uri.getPort() == -1
                        || uri.getPort() == ("https".equals(uri.getScheme()) ? 443 : 80))) {
                asset = path.startsWith("/") ? path.substring(1) : "";
            } else return null;
            if (asset.isEmpty() || asset.indexOf('\\') >= 0 || asset.indexOf('\0') >= 0) return null;
            for (String segment : asset.split("/")) if (segment.equals(".") || segment.equals("..")) return null;
            if (asset.endsWith(".html") && !asset.equals("share.html")) return null;
            return asset;
        } catch (Exception ignored) { return null; }
    }

    static boolean page(String raw) { return "share.html".equals(asset(raw)); }

    static boolean external(String raw) {
        try {
            URI uri = new URI(raw);
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                && uri.getHost() != null && !uri.getHost().equals("localhost") && uri.getRawUserInfo() == null;
        } catch (Exception ignored) { return false; }
    }

    static boolean remoteType(String raw, String mime) {
        try {
            URI uri = new URI(raw);
            if (!external(raw) || !"https".equals(uri.getScheme()) || mime == null) return false;
            String type = mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (type.startsWith("image/")) return !type.equals("image/svg+xml");
            if (type.startsWith("audio/") || type.startsWith("video/")) return true;
            if ("fonts.gstatic.com".equals(uri.getHost())) {
                return type.startsWith("font/") || type.equals("application/font-woff")
                    || type.equals("application/x-font-ttf") || type.equals("application/octet-stream");
            }
            return "fonts.googleapis.com".equals(uri.getHost()) && type.equals("text/css");
        } catch (Exception ignored) { return false; }
    }
}
