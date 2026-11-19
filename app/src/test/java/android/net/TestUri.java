package android.net;

import android.os.Parcel;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pure-JVM {@link Uri} implementation for unit tests running without Robolectric.
 */
public final class TestUri extends Uri {
    private final String raw;

    private TestUri(String raw) {
        this.raw = raw;
    }

    public static Uri parse(String raw) {
        return new TestUri(raw);
    }

    @Override public boolean isHierarchical() { return true; }
    @Override public boolean isRelative() { return false; }
    @Override public String getScheme() {
        int idx = raw.indexOf("://");
        return idx > 0 ? raw.substring(0, idx) : null;
    }
    @Override public String getSchemeSpecificPart() {
        int idx = raw.indexOf(':');
        return idx >= 0 ? raw.substring(idx + 1) : "";
    }
    @Override public String getEncodedSchemeSpecificPart() { return getSchemeSpecificPart(); }
    @Override public String getAuthority() {
        int start = raw.indexOf("://");
        if (start < 0) return null;
        String rest = raw.substring(start + 3);
        int slash = rest.indexOf('/');
        return slash >= 0 ? rest.substring(0, slash) : rest;
    }
    @Override public String getEncodedAuthority() { return getAuthority(); }
    @Override public String getUserInfo() { return null; }
    @Override public String getEncodedUserInfo() { return null; }
    @Override public String getHost() { return getAuthority(); }
    @Override public int getPort() { return -1; }
    @Override public String getPath() {
        int start = raw.indexOf("://");
        String rest = start >= 0 ? raw.substring(start + 3) : raw;
        int q = rest.indexOf('?');
        if (q >= 0) rest = rest.substring(0, q);
        int slash = rest.indexOf('/');
        return slash >= 0 ? rest.substring(slash) : "";
    }
    @Override public String getEncodedPath() { return getPath(); }
    @Override public String getQuery() {
        int q = raw.indexOf('?');
        return q >= 0 ? raw.substring(q + 1) : null;
    }
    @Override public String getQueryParameter(String key) {
        String q = getQuery();
        if (q == null || key == null) return null;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && key.equals(pair.substring(0, eq))) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }
    @Override public String getEncodedQuery() { return getQuery(); }
    @Override public String getFragment() { return null; }
    @Override public String getEncodedFragment() { return null; }
    @Override public List<String> getPathSegments() {
        String p = getPath();
        if (p.isEmpty() || "/".equals(p)) return Collections.emptyList();
        if (p.startsWith("/")) p = p.substring(1);
        return Arrays.asList(p.split("/"));
    }
    @Override public String getLastPathSegment() {
        List<String> segments = getPathSegments();
        return segments.isEmpty() ? null : segments.get(segments.size() - 1);
    }
    @Override public Builder buildUpon() { throw new UnsupportedOperationException(); }
    @Override public String toString() { return raw; }
    @Override public boolean equals(Object o) {
        return o instanceof Uri && raw.equals(o.toString());
    }
    @Override public int hashCode() { return raw.hashCode(); }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {}
}
