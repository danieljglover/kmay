package uk.kmay.media;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class Net {
    static final String CATALOG = "https://v3-cinemeta.strem.io";
    static String segment(String s) { return UriEncode.encode(s); }
    static JSONObject json(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(12000); c.setReadTimeout(25000);
        c.setRequestProperty("User-Agent", "KMay/1.0 Android");
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if(code != 200) throw new IOException("Service returned HTTP " + code);
            try(InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int n;
                while((n=in.read(buf))!=-1) { out.write(buf,0,n); if(out.size()>8_000_000) throw new IOException("Response too large"); }
                try { return new JSONObject(out.toString("UTF-8")); }
                catch(JSONException e) { throw new IOException("Service did not return valid catalogue data"); }
            }
        } finally { c.disconnect(); }
    }
    static boolean http(String url) { return url.startsWith("https://") || url.startsWith("http://"); }
    static List<JSONObject> objects(JSONArray a) {
        List<JSONObject> out = new ArrayList<>();
        if(a!=null) for(int i=0;i<a.length();i++) { JSONObject o=a.optJSONObject(i);if(o!=null)out.add(o); }
        return out;
    }
    static String message(Exception e) { return e instanceof java.net.SocketTimeoutException ? "The service took too long to respond. Try again." : e instanceof java.net.UnknownHostException ? "Cannot reach the service. Check your connection." : e.getMessage()==null ? "The service is unavailable." : e.getMessage(); }
    static boolean supports(JSONObject manifest,String resource,String type,String id) {
        JSONArray resources=manifest.optJSONArray("resources");
        if(resources==null)return false;
        for(int i=0;i<resources.length();i++) {
            Object r=resources.opt(i); JSONObject spec=r instanceof JSONObject?(JSONObject)r:manifest;
            if(!(r instanceof JSONObject?((JSONObject)r).optString("name"):String.valueOf(r)).equals(resource))continue;
            JSONArray types=spec.optJSONArray("types");if(types==null)types=manifest.optJSONArray("types");
            boolean ok=types==null; if(types!=null)for(int j=0;j<types.length();j++)if(type.equals(types.optString(j)))ok=true;
            if(!ok)continue;
            JSONArray prefixes=spec.optJSONArray("idPrefixes");if(prefixes==null)prefixes=manifest.optJSONArray("idPrefixes");
            if(prefixes==null)return true;
            for(int j=0;j<prefixes.length();j++)if(id.startsWith(prefixes.optString(j)))return true;
        }
        return false;
    }
    private static class UriEncode { static String encode(String v) { return android.net.Uri.encode(v); } }
}
