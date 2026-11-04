// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.runtime.engine;

import java.io.File;
import java.io.IOException;

final class Paths {
    private Paths() {}
    static String relative(String value) {
        if (value == null || value.length() == 0 || value.length() > 1024 || value.startsWith("/"))
            throw new IllegalArgumentException("Relative path required");
        for (String part : value.split("/", -1)) {
            if (part.length() == 0 || part.equals(".") || part.equals(".."))
                throw new IllegalArgumentException("Ambiguous path");
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < 32 || c == 127 || c == '\\' || c == ':' || c == '\t')
                    throw new IllegalArgumentException("Invalid path character");
            }
        }
        return value;
    }
    static File inside(File root, String relative) throws IOException {
        File result = new File(root, relative(relative));
        String prefix = root.getCanonicalPath() + File.separator;
        if (!result.getCanonicalPath().startsWith(prefix)) throw new IOException("Path escapes bundle");
        return result;
    }
}
