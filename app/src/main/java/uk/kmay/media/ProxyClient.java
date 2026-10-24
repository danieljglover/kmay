package uk.kmay.media;

import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

final class ProxyClient {
    static String normalize(String value) throws Exception {
        URI uri=new URI(value.trim());
        if(!("http".equals(uri.getScheme())||"https".equals(uri.getScheme()))||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getPath().isEmpty()||uri.getPath().equals("/")))throw new Exception("Use your server address, including its port, such as http://192.168.1.100:8788");
        return value.trim().replaceFirst("/+$", "");
    }
    static JSONObject request(String base,String token,String path,JSONObject payload) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(base+path).openConnection();
        c.setConnectTimeout(10000);c.setReadTimeout(75000);c.setInstanceFollowRedirects(false);
        if(path.startsWith("/v1/comparisons")){c.setConnectTimeout(5000);c.setReadTimeout(8000);}
        c.setRequestProperty("Authorization","Bearer "+token);c.setRequestProperty("Accept","application/json");
        try {
            if(payload!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(payload.toString().getBytes(StandardCharsets.UTF_8));}}
            int code=c.getResponseCode();
            if(code==401)throw new IOException("The server access token is incorrect.");
            if(code<200||code>=300)throw new IOException("Proxy returned HTTP "+code+". Check the server and retry.");
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int count;
                while((count=in.read(buffer))!=-1){out.write(buffer,0,count);if(out.size()>4_000_000)throw new IOException("Proxy response too large.");}
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally {c.disconnect();}
    }
    static JSONObject request(MainActivity a,String path,JSONObject payload) throws Exception {
        return request(a.prefs.getString("proxyUrl",""),a.prefs.getString("proxyToken",""),path,payload);
    }
    static JSONObject title(MainActivity a) throws Exception {
        JSONObject result=new JSONObject().put("type",a.selected.optString("type","movie")).put("id",a.videoId);
        String tmdb=a.selected.optString("moviedb_id","");if(!tmdb.isEmpty())result.put("tmdb_id",tmdb);
        String[] parts=a.videoId.split(":");
        if(result.getString("type").equals("series")&&parts.length>=3){result.put("season",Integer.parseInt(parts[parts.length-2]));result.put("episode",Integer.parseInt(parts[parts.length-1]));}
        return result;
    }
}
