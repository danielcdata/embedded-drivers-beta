package com.cdata.changelog;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

/**
 * CData Changelog Review — MCP Server
 * See README.md for installation and configuration instructions.
 */
public class ChangelogReviewServer {

    // ============================================================
    //  CONFIGURATION
    // ============================================================

    private static final String BASE_URL       = "https://downloads.cdata.com/cdataoembuilds";
    private static final String CHANGELOG_ROOT = BASE_URL + "/changelogs";

    private static final Map<String, String>  EDITION_SUBPATHS;
    private static final Map<String, Integer> HARDCODED_RELEASES;

    static {
        Map<String, String> sp = new LinkedHashMap<String, String>();
        sp.put("JDBC",               "jdbc");
        sp.put("ADO .NET FRAMEWORK", "ado/net40");
        sp.put("ADO .NET STANDARD",  "ado/netstandard20");
        sp.put("ODBC UNIX",          "odbc/linux/x64");
        sp.put("ODBC WINDOWS",       "odbc/net40/x64");
        sp.put("PYTHON MAC",         "python/mac");
        sp.put("PYTHON UNIX",        "python/unix");
        sp.put("PYTHON WINDOWS",     "python/win");
        EDITION_SUBPATHS = Collections.unmodifiableMap(sp);

        Map<String, Integer> hc = new LinkedHashMap<String, Integer>();
        hc.put("v25u1", 9434);
        HARDCODED_RELEASES = Collections.unmodifiableMap(hc);
    }

    // ============================================================
    //  HTTP HELPERS
    // ============================================================

    private static final class HttpResult {
        final int    status;
        final String body;
        HttpResult(int status, String body) { this.status = status; this.body = body; }
    }

    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024; // 10 MB

    private static HttpResult httpGet(String urlStr) throws IOException {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(30_000);
        conn.setInstanceFollowRedirects(true);
        int status = conn.getResponseCode();
        InputStream is = (status < 400) ? conn.getInputStream() : conn.getErrorStream();
        String body = "";
        if (is != null) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n, total = 0;
                while ((n = is.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_RESPONSE_BYTES)
                        throw new IOException("Response exceeded " + MAX_RESPONSE_BYTES + " bytes from: " + urlStr);
                    baos.write(buf, 0, n);
                }
                body = baos.toString("UTF-8");
            } finally {
                is.close();
            }
        }
        conn.disconnect();
        return new HttpResult(status, body);
    }

    // ============================================================
    //  SHARED UTILITIES
    // ============================================================

    /** Builds a successful MCP tool response. */
    private static CallToolResult ok(String text) {
        return CallToolResult.builder()
            .content(Collections.singletonList((McpSchema.Content) new TextContent(text)))
            .isError(false)
            .build();
    }

    /** Builds an error MCP tool response. */
    private static CallToolResult err(String message) {
        return CallToolResult.builder()
            .content(Collections.singletonList((McpSchema.Content) new TextContent(message)))
            .isError(true)
            .build();
    }

    /** Trims trailing whitespace from tool output. */
    private static String stripTrailing(String s) {
        int i = s.length() - 1;
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) i--;
        return i < 0 ? "" : s.substring(0, i + 1);
    }

    // ============================================================
    //  RELEASE CLASS
    // ============================================================

    /** A CData connector release identified by major version year and U-number. */
    private static final class Release {
        final int year;          // e.g. 2025
        final int releaseNumber; // e.g. 1

        Release(int yy, int rel) {
            this.year          = 2000 + yy;
            this.releaseNumber = rel;
        }

        String tag()   { return String.format("v%du%d", year % 100, releaseNumber); }
        String label() { return String.format("%d U%d", year, releaseNumber); }
    }

    // ============================================================
    //  S3 OPERATIONS
    // ============================================================

    private static final Pattern PAT_S3_KEY           = Pattern.compile("<Key>([^<]+)</Key>");
    private static final Pattern PAT_S3_CONTINUATION  = Pattern.compile("<NextContinuationToken>([^<]+)</NextContinuationToken>");
    private static final Pattern PAT_S3_RELEASE       = Pattern.compile("<Prefix>v(\\d{2})u(\\d+)/</Prefix>");
    private static final Pattern PAT_RELEASE_TAG      = Pattern.compile("v(\\d{2})u(\\d+)");

    /** Unescapes XML entities in S3 ListObjectsV2 responses. */
    private static String xmlUnescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&apos;", "'").replace("&quot;", "\"");
    }

    /** Lists all object keys under a given S3 prefix, handling pagination via continuation tokens. */
    private static List<String> listS3Objects(String prefix) throws IOException {
        List<String> keys = new ArrayList<String>();
        String continuationToken = null;
        do {
            StringBuilder url = new StringBuilder(BASE_URL)
                .append("/?list-type=2&prefix=")
                .append(URLEncoder.encode(prefix, "UTF-8"));
            if (continuationToken != null)
                url.append("&continuation-token=").append(URLEncoder.encode(continuationToken, "UTF-8"));
            HttpResult res = httpGet(url.toString());
            if (res.status != 200)
                throw new IOException("S3 list returned HTTP " + res.status + " for prefix: " + prefix);
            Matcher km = PAT_S3_KEY.matcher(res.body);
            while (km.find()) keys.add(xmlUnescape(km.group(1)));
            if (res.body.contains("<IsTruncated>true</IsTruncated>")) {
                Matcher tm = PAT_S3_CONTINUATION.matcher(res.body);
                continuationToken = tm.find() ? tm.group(1) : null;
            } else {
                continuationToken = null;
            }
        } while (continuationToken != null);
        return keys;
    }

    /** Discovers all available releases from S3 prefixes and hardcoded entries. Used by list_releases to show options and by get_changelog to validate after_release_number. */
    private static List<Release> listAvailableReleases() throws IOException {
        HttpResult res = httpGet(BASE_URL + "/?list-type=2&prefix=v&delimiter=/");
        if (res.status != 200) throw new IOException("S3 release discovery returned HTTP " + res.status);

        List<Release> releases = new ArrayList<Release>();

        Matcher m = PAT_S3_RELEASE.matcher(res.body);
        while (m.find())
            releases.add(new Release(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));

        for (String key : HARDCODED_RELEASES.keySet()) {
            Matcher hm = PAT_RELEASE_TAG.matcher(key);
            if (hm.matches())
                releases.add(new Release(Integer.parseInt(hm.group(1)), Integer.parseInt(hm.group(2))));
        }

        Collections.sort(releases, new Comparator<Release>() {
            @Override public int compare(Release a, Release b) {
                if (a.year != b.year) return Integer.compare(b.year, a.year);
                return Integer.compare(b.releaseNumber, a.releaseNumber);
            }
        });
        return releases;
    }

    // ============================================================
    //  CSV HELPERS
    // ============================================================

    /** Finds the index of a column name in a CSV header line. Returns -1 if not found. */
    private static int csvColumnIndex(String headerLine, String columnName) {
        String[] fields = csvSplitLine(headerLine);
        for (int i = 0; i < fields.length; i++)
            if (fields[i].trim().equals(columnName)) return i;
        return -1;
    }

    /** Splits a CSV line respecting quoted fields and escaped double-quotes. Used to reliably extract the Version column for build number filtering. */
    private static String[] csvSplitLine(String line) {
        List<String> fields = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        char[] chars = line.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '"') {
                if (inQuotes && i + 1 < chars.length && chars[i + 1] == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(cur.toString());
                cur = new StringBuilder();
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields.toArray(new String[0]);
    }

    // ============================================================
    //  TOOL: list_releases
    // ============================================================

    private static McpSchema.JsonSchema listReleasesSchema() {
        return new McpSchema.JsonSchema("object",
            Collections.<String, Object>emptyMap(), Collections.<String>emptyList(), null, null, null);
    }

    private static CallToolResult handleListReleases(Map<String, Object> args) {
        try {
            List<Release> releases = listAvailableReleases();
            if (releases.isEmpty()) return ok("No releases found.");
            StringBuilder sb = new StringBuilder("Available releases (newest first):\n");
            for (Release r : releases) {
                sb.append(String.format("  %s  (major_version: %d, release_number: %d)%n",
                    r.label(), r.year, r.releaseNumber));
            }
            return ok(stripTrailing(sb.toString()));
        } catch (Exception e) {
            e.printStackTrace(System.err);
            return err("Error listing releases: " + e.getMessage());
        }
    }

    // ============================================================
    //  TOOL: get_changelog
    // ============================================================

    // -- Schema --

    private static Map<String, Object> schemaProperty(String type, String description) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("type", type);
        m.put("description", description);
        return m;
    }

    /** Builds a string property constrained to a fixed set of values (rendered as a JSON Schema enum). */
    private static Map<String, Object> schemaEnum(String description, Collection<String> values) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("type", "string");
        m.put("description", description);
        m.put("enum", new ArrayList<String>(values));
        return m;
    }

    private static McpSchema.JsonSchema getChangelogSchema() {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("edition",              schemaEnum("Driver edition.", EDITION_SUBPATHS.keySet()));
        props.put("provider_name",         schemaProperty("string",  "Connector provider name (e.g. Salesforce)"));
        props.put("major_version",        schemaProperty("integer", "Major version year from list_releases (e.g. 2025). Each major version has its own independent changelog."));
        props.put("after_release_number", schemaProperty("integer", "The U-number exactly as shown by list_releases. For '2025 U1' use 1, for '2025 U2' use 2. Must be >= 1. Do NOT subtract or compute — use the number directly."));
        props.put("after_date",           schemaProperty("string",  "Return entries after this date (ISO 8601 format, e.g. '2025-10-28'). Use for date-based queries like 'changes in the last month'."));
        props.put("after_build",          schemaProperty("integer", "Return entries after this build number. Only use if the user provides a specific build number. Prefer after_date or after_release_number instead."));
        return new McpSchema.JsonSchema("object", props,
            Arrays.asList("edition", "provider_name", "major_version"), null, null, null);
    }

    // -- Argument parsing --

    /** Reads an optional string from MCP tool arguments. Returns null if absent or empty. */
    private static String stringArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) return null;
        String s = val.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** Safely reads an optional integer from MCP tool arguments, handling both Number and String types. */
    private static Integer optIntArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            String sv = ((String) val).trim();
            return sv.isEmpty() ? null : Integer.parseInt(sv);
        }
        return null;
    }

    // -- Build number discovery --

    /** Extracts the build number from a changelog version string (e.g. "25.0.9434" -> 9434). Returns -1 on failure. */
    private static int versionToBuildNumber(String versionStr) {
        String[] parts = versionStr.trim().split("\\.");
        if (parts.length >= 3) {
            try { return Integer.parseInt(parts[parts.length - 1]); }
            catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    /** Converts an ISO 8601 date (e.g. "2025-10-28") to a CData build number (days since 2000-01-01 UTC). */
    private static int dateToBuildNumber(String iso) {
        String[] parts = iso.split("-");
        if (parts.length != 3) throw new IllegalArgumentException("Invalid date format, expected YYYY-MM-DD: " + iso);
        int y = Integer.parseInt(parts[0]);
        int m = Integer.parseInt(parts[1]);
        int d = Integer.parseInt(parts[2]);
        if (m < 1 || m > 12) throw new IllegalArgumentException("Invalid month " + m + " in date: " + iso);
        if (d < 1 || d > 31) throw new IllegalArgumentException("Invalid day " + d + " in date: " + iso);
        if (y < 2000 || y > 2100) throw new IllegalArgumentException("Year out of range in date: " + iso);
        long epoch2000 = 946684800000L; // 2000-01-01T00:00:00 UTC in millis
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setLenient(false);
        cal.set(y, m - 1, d, 0, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        try { cal.getTimeInMillis(); } // triggers validation in non-lenient mode
        catch (Exception e) { throw new IllegalArgumentException("Invalid date: " + iso); }
        return (int) ((cal.getTimeInMillis() - epoch2000) / 86400000L);
    }

    /** Patterns for parsing bld-* marker filenames per edition (e.g. "bld-cdata.jdbc.MongoDB.9434"). */
    private static final Pattern PAT_BLD_ADO    = Pattern.compile("^bld-System\\.Data\\.CData\\.(.+)\\.(\\d+)$");
    private static final Pattern PAT_BLD_JDBC   = Pattern.compile("^bld-cdata\\.jdbc\\.(.+)\\.(\\d+)$");
    private static final Pattern PAT_BLD_ODBC   = Pattern.compile("^bld-[Cc][Dd]ata\\.[Oo][Dd][Bb][Cc]\\.(.+)\\.(\\d+)$");
    private static final Pattern PAT_BLD_PYTHON = Pattern.compile("^bld-(.+)\\.(\\d+)$");

    /** Returns the bld-* marker filename pattern for an edition (uppercased). */
    private static Pattern bldPatternFor(String editionUpper) {
        if      (editionUpper.startsWith("ADO"))    return PAT_BLD_ADO;
        else if (editionUpper.equals("JDBC"))       return PAT_BLD_JDBC;
        else if (editionUpper.startsWith("ODBC"))   return PAT_BLD_ODBC;
        else if (editionUpper.startsWith("PYTHON")) return PAT_BLD_PYTHON;
        else throw new IllegalArgumentException("Unknown edition: " + editionUpper);
    }

    /**
     * Resolves a release (major version + U-number) to its build number
     * via hardcoded releases or S3 build marker lookup.
     * Lists all bld-* markers for the edition and matches provider name case-insensitively.
     */
    private static int releaseToBuildNumber(int majorVersion, int releaseNumber, String edition, String objName) throws IOException {
        String releaseTag = String.format("v%du%d", majorVersion % 100, releaseNumber);

        if (HARDCODED_RELEASES.containsKey(releaseTag))
            return HARDCODED_RELEASES.get(releaseTag);

        List<Release> releases = listAvailableReleases();
        String releaseErr = validateRelease(majorVersion, releaseNumber, releases);
        if (releaseErr != null)
            throw new IllegalArgumentException(releaseErr);

        String eu = edition.toUpperCase(Locale.ROOT);
        Pattern pat = bldPatternFor(eu);

        String releasePath = releaseTag + "/" + EDITION_SUBPATHS.get(edition);
        for (String key : listS3Objects(releasePath + "/bld-")) {
            String filename = key.substring(key.lastIndexOf('/') + 1);
            Matcher m = pat.matcher(filename);
            if (m.matches() && m.group(1).equalsIgnoreCase(objName))
                return Integer.parseInt(m.group(2));
        }
        throw new IllegalArgumentException("No build found for '" + objName + "' in " +
                edition + " / " + releaseTag + " (major version " + majorVersion + ")." +
                sourceHint(objName, edition, majorVersion));
    }

    /**
     * Validates that a release exists.
     * Returns null if valid, or an error message listing available releases.
     */
    private static String validateRelease(int year, int releaseNumber, List<Release> releases) {
        for (Release r : releases) {
            if (r.year == year && r.releaseNumber == releaseNumber) return null; // found
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Release '%d U%d' does not exist.\n\nAvailable releases:\n", year, releaseNumber));
        for (Release r : releases)
            sb.append(String.format("  %s  (year=%d, release_number=%d)%n", r.label(), r.year, r.releaseNumber));
        sb.append("\nPlease ask the user which release they want.");
        return stripTrailing(sb.toString());
    }

    // -- Source (provider name) discovery --

    /** True if the string contains at least one uppercase letter. */
    private static boolean hasUpper(String s) {
        for (int i = 0; i < s.length(); i++)
            if (Character.isUpperCase(s.charAt(i))) return true;
        return false;
    }

    /**
     * Lists the provider names available for an edition + major version, with canonical casing.
     *
     * The authoritative set is the changelog directory tree (every connector that has a changelog
     * for that major version — exactly what get_changelog can fetch). Those directory names are
     * lowercase, so we harvest canonical CamelCase from the build markers across the major version's
     * releases and apply it where available, leaving any unmatched name in its lowercase form.
     */
    private static List<String> collectSources(String edition, int majorVersion) throws IOException {
        String eu = edition.toUpperCase(Locale.ROOT);
        String subpath = EDITION_SUBPATHS.get(eu);
        String changelogPath = subpath.indexOf('/') >= 0 ? subpath.substring(0, subpath.indexOf('/')) : subpath;
        String yy = String.valueOf(majorVersion % 100);

        // Authoritative set: the (lowercase) connector directories under the changelog tree.
        Set<String> authoritative = new TreeSet<String>();
        String clPrefix = "changelogs/v" + yy + "/" + changelogPath + "/";
        for (String key : listS3Objects(clPrefix)) {
            if (!key.startsWith(clPrefix)) continue;
            String rest = key.substring(clPrefix.length());
            int slash = rest.indexOf('/');
            if (slash > 0) authoritative.add(rest.substring(0, slash));
        }

        // Casing map: lowercase -> canonical CamelCase, harvested from build markers. Prefer a value
        // that carries uppercase letters so a stray lowercase marker can't override the canonical name.
        Map<String, String> casing = new HashMap<String, String>();
        Pattern pat = bldPatternFor(eu);
        for (Release r : listAvailableReleases()) {
            if (r.year != majorVersion) continue;
            for (String key : listS3Objects(r.tag() + "/" + subpath + "/bld-")) {
                String filename = key.substring(key.lastIndexOf('/') + 1);
                Matcher m = pat.matcher(filename);
                if (!m.matches()) continue;
                String name = m.group(1);
                String lower = name.toLowerCase(Locale.ROOT);
                String existing = casing.get(lower);
                if (existing == null || (!hasUpper(existing) && hasUpper(name)))
                    casing.put(lower, name);
            }
        }

        Set<String> result = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        if (authoritative.isEmpty()) {
            result.addAll(casing.values()); // unexpected: no changelog tree — fall back to markers
        } else {
            for (String lower : authoritative) {
                String canon = casing.get(lower);
                result.add(canon != null ? canon : lower);
            }
        }
        return new ArrayList<String>(result);
    }

    /** Builds guidance for a bad provider name: close matches if any, else a pointer to list_sources. */
    private static String sourceHint(String attempted, String edition, int majorVersion) {
        try {
            List<String> all = collectSources(edition, majorVersion);
            List<String> near = new ArrayList<String>();
            String needle = attempted.toLowerCase(Locale.ROOT);
            for (String s : all) {
                String sl = s.toLowerCase(Locale.ROOT);
                if (sl.contains(needle) || needle.contains(sl)) near.add(s);
            }
            if (!near.isEmpty())
                return " Did you mean: " + String.join(", ", near.subList(0, Math.min(near.size(), 15))) + "?";
        } catch (Exception ignored) {}
        return " Call list_sources with edition='" + edition + "' and major_version=" + majorVersion +
               " to see all valid provider names.";
    }

    // -- Handler --

    private static CallToolResult handleGetChangelog(Map<String, Object> args) {
        // -- Validate required params --
        Integer majorVersion = optIntArg(args, "major_version");
        if (majorVersion == null)
            return err("major_version is required. Call list_releases to see available major versions.");

        String editionRaw = stringArg(args, "edition");
        if (editionRaw == null)
            return err("edition is required.");
        String edition = editionRaw.toUpperCase(Locale.ROOT);
        if (!EDITION_SUBPATHS.containsKey(edition))
            return err("Unknown edition '" + editionRaw + "'. Valid editions: " + String.join(", ", EDITION_SUBPATHS.keySet()));

        String objName = stringArg(args, "provider_name");
        if (objName == null)
            return err("provider_name is required.");

        // -- Resolve baseline build number from exactly one "after" param --
        Integer afterBuild         = optIntArg(args, "after_build");
        Integer afterReleaseNumber = optIntArg(args, "after_release_number");
        String  afterDate          = stringArg(args, "after_date");

        int afterCount = (afterBuild != null ? 1 : 0) + (afterReleaseNumber != null ? 1 : 0) + (afterDate != null ? 1 : 0);
        if (afterCount == 0)
            return err("Provide exactly one of: after_release_number, after_date, or after_build.");
        if (afterCount > 1)
            return err("Provide only one of: after_release_number, after_date, or after_build.");

        int baselineBuild;
        try {
            if (afterDate != null) {
                baselineBuild = dateToBuildNumber(afterDate);
            } else if (afterBuild != null) {
                if (afterBuild < 1) return err("after_build must be a positive build number.");
                baselineBuild = afterBuild;
            } else {
                if (afterReleaseNumber < 1)
                    return err("after_release_number must be >= 1. Use the U-number directly from list_releases (e.g. 1 for U1, 2 for U2).");
                baselineBuild = releaseToBuildNumber(majorVersion, afterReleaseNumber, edition, objName);
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
            return err("Error resolving baseline build: " + e.getMessage());
        }

        // -- Fetch and filter changelog --
        String subpath = EDITION_SUBPATHS.get(edition);
        String changelogPath = subpath.indexOf('/') >= 0 ? subpath.substring(0, subpath.indexOf('/')) : subpath;
        String majorVersionTag = String.format("v%d", majorVersion % 100);
        String url = CHANGELOG_ROOT + "/" + majorVersionTag + "/" + changelogPath + "/" + objName.toLowerCase(Locale.ROOT) + "/changelog.csv";

        HttpResult res;
        try { res = httpGet(url); }
        catch (IOException e) {
            e.printStackTrace(System.err);
            return err("Error fetching changelog: " + e.getMessage());
        }

        if (res.status == 404)
            return err("No changelog found for '" + objName + "' (" + edition + ") in major version " + majorVersion + "." +
                sourceHint(objName, edition, majorVersion));
        if (res.status != 200)
            return err("HTTP " + res.status + " fetching changelog for '" + objName + "'.");

        String[] lines = res.body.replace("\r\n", "\n").replace("\r", "\n").split("\n");
        if (lines.length < 2)
            return ok("Changelog is empty for '" + objName + "' in major version " + majorVersion + ".");

        int versionCol = csvColumnIndex(lines[0], "Version");
        if (versionCol < 0)
            return err("Changelog CSV missing 'Version' column.");

        List<String> filtered = new ArrayList<String>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] fields = csvSplitLine(line);
            if (versionCol < fields.length && versionToBuildNumber(fields[versionCol]) > baselineBuild)
                filtered.add(line);
        }

        if (filtered.isEmpty())
            return ok("No changelog entries after build " + baselineBuild + " for '" + objName + "' in major version " + majorVersion + ".");

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Changelog: %s (%s) v%d — %d entr%s after build %d%n%n",
            objName, edition, majorVersion, filtered.size(), filtered.size() == 1 ? "y" : "ies", baselineBuild));
        sb.append(lines[0]).append('\n');
        for (String line : filtered)
            sb.append(line).append('\n');
        return ok(stripTrailing(sb.toString()));
    }

    // ============================================================
    //  TOOL: list_sources
    // ============================================================

    private static McpSchema.JsonSchema listSourcesSchema() {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("edition",       schemaEnum("Driver edition.", EDITION_SUBPATHS.keySet()));
        props.put("major_version", schemaProperty("integer", "Major version year from list_releases (e.g. 2025)."));
        return new McpSchema.JsonSchema("object", props,
            Arrays.asList("edition", "major_version"), null, null, null);
    }

    private static CallToolResult handleListSources(Map<String, Object> args) {
        Integer majorVersion = optIntArg(args, "major_version");
        if (majorVersion == null)
            return err("major_version is required. Call list_releases to see available major versions.");

        String editionRaw = stringArg(args, "edition");
        if (editionRaw == null)
            return err("edition is required.");
        String edition = editionRaw.toUpperCase(Locale.ROOT);
        if (!EDITION_SUBPATHS.containsKey(edition))
            return err("Unknown edition '" + editionRaw + "'. Valid editions: " + String.join(", ", EDITION_SUBPATHS.keySet()));

        List<String> sources;
        try {
            sources = collectSources(edition, majorVersion);
        } catch (Exception e) {
            e.printStackTrace(System.err);
            return err("Error listing sources: " + e.getMessage());
        }

        if (sources.isEmpty())
            return ok("No connectors found for " + edition + " in major version " + majorVersion + ".");

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%d connector%s available for %s in major version %d ",
            sources.size(), sources.size() == 1 ? "" : "s", edition, majorVersion));
        sb.append("(use one verbatim as provider_name in get_changelog):\n");
        for (String s : sources)
            sb.append("  ").append(s).append('\n');
        return ok(stripTrailing(sb.toString()));
    }

    // ============================================================
    //  MAIN
    // ============================================================

    public static void main(String[] args) throws Exception {
        StdioServerTransportProvider transport =
            new StdioServerTransportProvider(McpJsonDefaults.getMapper());

        McpServer.sync(transport)
            .serverInfo("cdata-changelog-review-mcp", "1.1.0")
            .capabilities(ServerCapabilities.builder().tools(true).build())

            .toolCall(
                McpSchema.Tool.builder()
                    .name("list_releases")
                    .description(
                        "List available CData connector releases, newest first. " +
                        "Step 1 of the workflow: list_releases → list_sources → get_changelog. " +
                        "MUST be called before get_changelog — only use release numbers returned here. " +
                        "Do NOT guess or assume release numbers — only releases returned by this tool are valid. " +
                        "No arguments required.")
                    .inputSchema(listReleasesSchema())
                    .build(),
                (exchange, request) -> handleListReleases(
                    request.arguments() != null ? request.arguments() : Collections.<String, Object>emptyMap()))

            .toolCall(
                McpSchema.Tool.builder()
                    .name("list_sources")
                    .description(
                        "List the valid provider_name values (connectors) for an edition and major version. " +
                        "Step 2 of the workflow: list_releases → list_sources → get_changelog. " +
                        "Call this before get_changelog whenever you are unsure of the exact connector name — " +
                        "do NOT guess provider names. Use a returned name verbatim as get_changelog's provider_name. " +
                        "Requires edition and major_version.")
                    .inputSchema(listSourcesSchema())
                    .build(),
                (exchange, request) -> handleListSources(
                    request.arguments() != null ? request.arguments() : Collections.<String, Object>emptyMap()))

            .toolCall(
                McpSchema.Tool.builder()
                    .name("get_changelog")
                    .description(
                        "Get changelog entries for a CData connector since a release, date, or build. " +
                        "Each major version has its own independent changelog. " +
                        "IMPORTANT: Call list_releases first. Do NOT invent or guess release numbers. " +
                        "If unsure of the exact provider_name, call list_sources first and use a name from it verbatim. " +
                        "The major_version is NOT the current calendar year — it is the version year from list_releases. " +
                        "Requires EXACTLY ONE of: " +
                        "after_release_number (U-number, e.g. 2 for U2), " +
                        "after_date (ISO 8601 date, e.g. '2025-10-28' — use for 'changes in the last month'), or " +
                        "after_build (integer build number). " +
                        "If the user doesn't specify a release, date, or build, ASK. " +
                        "If edition not specified, ASK.")
                    .inputSchema(getChangelogSchema())
                    .build(),
                (exchange, request) -> handleGetChangelog(
                    request.arguments() != null ? request.arguments() : Collections.<String, Object>emptyMap()))

            .build();

        System.err.println("CData Changelog Review MCP server started.");
        Thread.currentThread().join();
    }
}
