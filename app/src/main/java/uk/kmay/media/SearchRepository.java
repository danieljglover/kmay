package uk.kmay.media;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A bounded search pipeline independent of the activity and playback executor. */
final class SearchRepository implements AutoCloseable {
    interface Listener { void complete(SearchModel.Provider provider,int offset,List<JSONObject> items,boolean failed,long millis); }
    interface Fetcher { String fetch(String url, Ticket ticket) throws Exception; }
    static final class Ticket {
        volatile HttpURLConnection connection;
        volatile Future<?> future;
        volatile ScheduledFuture<?> deadline;
        final AtomicBoolean finished=new AtomicBoolean();
        void stop(ExecutorService cleanup){
            if(future!=null)future.cancel(true);
            if(deadline!=null)deadline.cancel(false);
            // Disconnect may drain a TLS socket. TextWatcher and IME callbacks run on the UI thread.
            HttpURLConnection active=connection;
            if(active!=null)cleanup.submit(active::disconnect);
        }
    }
    static final class Cache {
        static final long TTL=300_000;
        final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>(16,.75f,true);
        int bytes;
        static final class Entry {
            final String json;final long time;final int size;
            Entry(String json,long time){this.json=json;this.time=time;size=json.getBytes(StandardCharsets.UTF_8).length;}
        }
        synchronized String get(String key,long now){
            Entry e=entries.get(key);if(e==null)return null;
            if(now-e.time>=TTL){entries.remove(key);bytes-=e.size;return null;}return e.json;
        }
        synchronized void put(String key,String json,long now){
            Entry e=new Entry(json,now);if(e.size>5_000_000)return;
            Entry old=entries.put(key,e);if(old!=null)bytes-=old.size;bytes+=e.size;
            while(entries.size()>30||bytes>5_000_000){String first=entries.keySet().iterator().next();bytes-=entries.remove(first).size;}
        }
        synchronized void clear(){entries.clear();bytes=0;}
    }
    final ThreadPoolExecutor workers=(ThreadPoolExecutor)Executors.newFixedThreadPool(4);
    final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    final ExecutorService cleanup=Executors.newSingleThreadExecutor();
    final List<Ticket> tickets=new ArrayList<>();
    final Cache cache=new Cache();
    final Executor delivery;
    final Fetcher fetcher;
    volatile int generation;
    String configuration="";
    final long timeout;

    SearchRepository(Executor delivery){this(delivery,SearchRepository::http,8000);}
    SearchRepository(Executor delivery,Fetcher fetcher,long timeout){this.delivery=delivery;this.fetcher=fetcher;this.timeout=timeout;}
    void configure(String configuration){if(!this.configuration.equals(configuration)){cancel();cache.clear();this.configuration=configuration;}}
    void cancel(){generation++;synchronized(tickets){for(Ticket ticket:tickets){ticket.finished.set(true);ticket.stop(cleanup);}tickets.clear();}workers.purge();}

    static String encode(String value){try{return URLEncoder.encode(value,"UTF-8").replace("+","%20");}catch(UnsupportedEncodingException e){throw new AssertionError(e);}}
    static String url(SearchModel.Provider p,String query,int offset){
        return p.base+"/catalog/"+encode(p.type)+"/"+encode(p.id)+"/search="+encode(query)+(p.paging?"&skip="+offset:"")+".json";
    }
    void fetch(SearchModel.Provider provider,String query,int offset,Listener listener){
        int current=generation;
        String url=url(provider,query,offset),key=configuration+"|"+url;
        Ticket ticket=new Ticket();synchronized(tickets){tickets.add(ticket);}
        long start=System.nanoTime();
        ticket.future=workers.submit(()->{
            if(ticket.finished.get())return;
            try {
            ticket.deadline=timer.schedule(()->{
                finish(ticket,current,provider,offset,Collections.emptyList(),true,start,listener);
                ticket.stop(cleanup);
            },timeout,TimeUnit.MILLISECONDS);
                String json=cache.get(key,System.currentTimeMillis());
                if(json==null) {
                    json=fetcher.fetch(url,ticket);
                    // Cache only valid responses, and never repopulate after cancellation.
                    new JSONObject(json).getJSONArray("metas");
                    if(!ticket.finished.get()&&current==generation)cache.put(key,json,System.currentTimeMillis());
                }
                List<JSONObject> items=new ArrayList<>();
                for(JSONObject raw:Net.objects(new JSONObject(json).getJSONArray("metas"))) {
                    if(raw.optString("id").isEmpty()||raw.optString("name").isEmpty())continue;
                    JSONObject item=new JSONObject(raw.toString());
                    if(item.optString("type").isEmpty())item.put("type",provider.type);
                    item.put("_base",provider.base);
                    items.add(item);
                }
                finish(ticket,current,provider,offset,items,false,start,listener);
            } catch(Exception e){finish(ticket,current,provider,offset,Collections.emptyList(),true,start,listener);}
            finally {if(ticket.deadline!=null)ticket.deadline.cancel(false);}
        });
    }
    private void finish(Ticket ticket,int current,SearchModel.Provider provider,int offset,List<JSONObject> items,boolean failed,long start,Listener listener){
        if(!ticket.finished.compareAndSet(false,true))return;
        synchronized(tickets){tickets.remove(ticket);}
        long millis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        delivery.execute(()->{if(current==generation)listener.complete(provider,offset,items,failed,millis);});
    }
    static String http(String url,Ticket ticket)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();ticket.connection=connection;
        if(ticket.finished.get())throw new InterruptedIOException();
        connection.setConnectTimeout(8000);connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("User-Agent","KMay/1.1 Android");
        try{
            if(connection.getResponseCode()!=200)throw new IOException("Provider unavailable");
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int read;
                while((read=input.read(buffer))!=-1){
                    if(ticket.finished.get()||Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                    output.write(buffer,0,read);if(output.size()>8_000_000)throw new IOException("Response too large");
                }
                return output.toString("UTF-8");
            }
        }finally{connection.disconnect();ticket.connection=null;}
    }
    @Override public void close(){cancel();workers.shutdownNow();timer.shutdownNow();cleanup.shutdown();cache.clear();}
}
