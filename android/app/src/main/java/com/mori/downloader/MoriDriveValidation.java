package com.mori.downloader;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MoriDriveValidation {
    private MoriDriveValidation() {}

    static String fileName(String name) throws IOException {
        if (name != null) name = name.trim();
        if (name == null || name.trim().isEmpty() || name.length() > 240
                || name.equals(".") || name.equals("..") || name.matches("(?s).*[\\\\/\\p{Cntrl}].*")) {
            throw new IOException("INVALID_FILENAME");
        }
        return name;
    }

    static long offset(String range, long size) throws IOException {
        if (range == null) return 0;
        Matcher match = Pattern.compile("bytes=0-([0-9]+)").matcher(range);
        if (!match.matches()) throw new IOException("INVALID_UPLOAD_RANGE");
        try {
            long last = Long.parseLong(match.group(1));
            if (last >= size) throw new IOException("INVALID_UPLOAD_RANGE");
            return last + 1;
        } catch (NumberFormatException e) { throw new IOException("INVALID_UPLOAD_RANGE"); }
    }

    static boolean verified(String expectedId, String id, long expectedSize, long size,
            String expectedMd5, String md5, String folder, String parent, int parentCount, Object trashed) {
        return Boolean.FALSE.equals(trashed) && !expectedId.isEmpty() && expectedId.equals(id)
            && expectedSize > 0 && expectedSize == size
            && expectedMd5.matches("[a-f0-9]{32}") && expectedMd5.equalsIgnoreCase(md5)
            && !folder.isEmpty() && folder.equals(parent) && parentCount == 1;
    }

    static String[] sourceKeys(String host) {
        if (host.equals("youtube.com") || host.endsWith(".youtube.com")) return new String[]{"v", "list"};
        if (host.equals("facebook.com") || host.endsWith(".facebook.com") || host.equals("fb.watch")) {
            return new String[]{"id", "story_fbid", "fbid", "v"};
        }
        for (String domain : new String[]{"terabox.com", "teraboxapp.com", "teraboxlink.com", "teraboxshare.com", "teraboxurl.com",
                "1024tera.com", "nephobox.com", "4funbox.com", "mirrobox.com", "momerybox.com", "tibibox.com"}) {
            if (host.equals(domain) || host.endsWith("." + domain)) return new String[]{"surl", "shareid", "uk", "fid", "shorturl"};
        }
        return new String[0];
    }
}
