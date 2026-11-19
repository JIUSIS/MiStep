package com.jiusis.mistep;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

final class DonorFeed {
    private static final int MAX_FILE_BYTES = 64 * 1024;
    private static final int TIMEOUT_MS = 5000;

    private final URL endpoint;
    private final SharedPreferences cache;

    DonorFeed(Context context) {
        int config = context.getResources().getIdentifier(
                "donor_feed_url", "string", context.getPackageName());
        endpoint = config == 0 ? null : parseEndpoint(context.getString(config).trim());
        cache = context.getSharedPreferences("donor_feed", Context.MODE_PRIVATE);
    }

    boolean isConfigured() {
        return endpoint != null;
    }

    List<String> cachedNames() {
        if (endpoint == null) return null;
        String document = cache.getString(endpoint.toExternalForm(), null);
        if (document == null) return null;
        try {
            return parseNames(document);
        } catch (JSONException invalidCache) {
            return null;
        }
    }

    List<String> refreshNames() throws IOException, JSONException {
        if (endpoint == null) throw new IOException("Donor feed not configured");
        HttpsURLConnection connection = (HttpsURLConnection) endpoint.openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-cache");
            if (connection.getResponseCode() != 200) throw new IOException("Donor feed unavailable");
            String document;
            try (InputStream body = connection.getInputStream()) {
                document = readDocument(body);
            }
            List<String> names = parseNames(document);
            String cacheKey = endpoint.toExternalForm();
            if (!document.equals(cache.getString(cacheKey, null))) {
                cache.edit().putString(cacheKey, document).apply();
            }
            return names;
        } finally {
            connection.disconnect();
        }
    }

    static URL parseEndpoint(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null) return null;
            return uri.toURL();
        } catch (IllegalArgumentException | MalformedURLException invalidUrl) {
            return null;
        }
    }

    static String readDocument(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(MAX_FILE_BYTES + 1);
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("Donor file too large");
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    static List<String> parseNames(String document) throws JSONException {
        JSONArray rows = parseObject(document).getJSONArray("donors");
        if (rows.length() > 500) throw new JSONException("Too many donor entries");
        ArrayList<String> names = new ArrayList<>(rows.length());
        for (int index = 0; index < rows.length(); index++) {
            if (!(rows.get(index) instanceof String value)) throw new JSONException("Invalid donor name");
            String name = value.strip();
            if (name.isEmpty() || name.codePointCount(0, name.length()) > 64
                    || name.codePoints().anyMatch(cp -> Character.isISOControl(cp)
                    || cp == 0x2028 || cp == 0x2029)) {
                throw new JSONException("Invalid donor name");
            }
            names.add(name);
        }
        return List.copyOf(names);
    }

    private static JSONObject parseObject(String document) throws JSONException {
        JSONTokener json = new JSONTokener(document);
        if (!(json.nextValue() instanceof JSONObject object) || json.nextClean() != 0) {
            throw new JSONException("Expected a JSON object");
        }
        return object;
    }

}
