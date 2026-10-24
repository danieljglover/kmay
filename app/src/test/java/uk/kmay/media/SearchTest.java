package uk.kmay.media;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class SearchTest {
    @Test public void cancellingAnActiveConnectionNeverDisconnectsOnCallerThread()throws Exception{
        Thread caller=Thread.currentThread();
        CountDownLatch connected=new CountDownLatch(1),disconnected=new CountDownLatch(1);
        AtomicReference<Thread> cleanupThread=new AtomicReference<>();
        SearchRepository repo=new SearchRepository(Runnable::run,(u,t)->{
            t.connection=new java.net.HttpURLConnection(new java.net.URL(u)){
                public void connect(){} public boolean usingProxy(){return false;}
                public void disconnect(){cleanupThread.set(Thread.currentThread());disconnected.countDown();}
            };
            connected.countDown();Thread.sleep(5000);return "{metas:[]}";
        },8000);
        try{
            repo.fetch(provider(),"first",0,(p,o,i,f,m)->fail("Cancelled request delivered"));
            assertTrue(connected.await(2,TimeUnit.SECONDS));repo.cancel();
            assertTrue(disconnected.await(2,TimeUnit.SECONDS));
            assertNotSame("Socket cleanup must not run in the text/voice input callback",caller,cleanupThread.get());
        }finally{repo.close();}
    }
    JSONObject item(String id,String name,String type,String base)throws Exception{return new JSONObject().put("id",id).put("name",name).put("type",type).put("_base",base);}
    SearchModel.Provider provider(){return new SearchModel.Provider("https://test.invalid","titles","movie","Test",true,Collections.emptyList());}

    @Test public void normalizationAndRanking()throws Exception{
        assertEquals("amelie 2001",SearchModel.normalize("  Amélie — 2001  "));
        assertEquals(0,SearchModel.rank("Amélie","amelie",false));
        assertEquals(1,SearchModel.rank("Alien Resurrection","alien",false));
        assertEquals(2,SearchModel.rank("The Alien","alien",false));
        assertEquals(3,SearchModel.rank("Alien","Alin",true));
        assertEquals(4,SearchModel.rank("Alien","Alin",false));
        SearchModel.Result exact=new SearchModel.Result(item("1","Alien","movie","a"),false);
        SearchModel.Result prefix=new SearchModel.Result(item("2","Alien 2","movie","a"),true);
        assertSame(exact,SearchModel.ranked(Arrays.asList(prefix,exact),new SearchModel.Request("alien","")).get(0));
        SearchModel.Result popular=new SearchModel.Result(item("99","Alien","movie","a"),false);popular.providerRank=0;exact.providerRank=12;
        assertSame(popular,SearchModel.ranked(Arrays.asList(exact,popular),new SearchModel.Request("alien","")).get(0));
    }
    @Test public void identityPreservesProviderAndType()throws Exception{
        JSONObject a=item("tt123","Film","movie","a"),b=item("tt123","Film","movie","b");
        assertEquals(SearchModel.identity(a),SearchModel.identity(b));
        SearchModel.Result merged=new SearchModel.Result(a,false);merged.merge(new SearchModel.Result(b,true));merged.merge(new SearchModel.Result(b,true));
        assertEquals(2,merged.references.size());assertTrue(merged.local);assertSame(a,merged.item());
        assertNotEquals(SearchModel.identity(a),SearchModel.identity(item("tt123","Film","series","a")));
        assertNotEquals(SearchModel.identity(item("123","Film","movie","a")),SearchModel.identity(item("123","Film","movie","b")));
    }
    @Test public void providerRequirementsAndPagination()throws Exception{
        JSONArray addons=new JSONArray("[{base:'https://example.test',manifest:{name:'Demo',catalogs:[{id:'valid',type:'movie',extra:[{name:'search'},{name:'skip',isRequired:true,options:['0','100','200']}]},{id:'needsGenre',type:'movie',extra:[{name:'search'},{name:'genre',isRequired:true}]},{id:'noSearch',type:'movie'}]}}]");
        SearchModel.Providers providers=SearchModel.providers(addons,"movie");
        assertEquals(2,providers.available.size());assertEquals(2,providers.unsupported);
        SearchModel.Provider p=providers.available.get(1);assertEquals(100,p.next(0));assertEquals(-1,p.next(200));
        assertEquals("https://example.test/catalog/movie/valid/search=A%26B%20%2F&skip=100.json",SearchRepository.url(p,"A&B /",100));
    }
    @Test public void cacheExpiresEvictsAndClears(){
        SearchRepository.Cache cache=new SearchRepository.Cache();cache.put("a","{}",100);
        assertEquals("{}",cache.get("a",200));assertNull(cache.get("a",300100));
        for(int i=0;i<31;i++)cache.put("key"+i,"{}",0);
        assertEquals(30,cache.entries.size());assertNull(cache.get("key0",1));cache.clear();assertEquals(0,cache.bytes);
        String large=new String(new char[2_000_000]).replace('\0','x');
        cache.put("a",large,0);cache.put("b",large,0);cache.put("c",large,0);assertNull(cache.get("a",1));assertTrue(cache.bytes<=5_000_000);
    }
    @Test public void configurationInvalidatesCacheAndMetadataSurvives()throws Exception{
        AtomicInteger requests=new AtomicInteger();
        SearchRepository repo=new SearchRepository(Runnable::run,(url,t)->{requests.incrementAndGet();return "{metas:[{id:'tt123',name:'Alien',moviedb_id:'348'}]}";},1000);
        try{
            repo.configure("first");fetchAndWait(repo);fetchAndWait(repo);assertEquals(1,requests.get());
            repo.configure("second");fetchAndWait(repo);assertEquals(2,requests.get());
        }finally{repo.close();}
    }
    void fetchAndWait(SearchRepository repo)throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicReference<List<JSONObject>> result=new AtomicReference<>();AtomicBoolean failed=new AtomicBoolean();
        repo.fetch(provider(),"alien",0,(p,o,items,error,ms)->{result.set(items);failed.set(error);done.countDown();});
        assertTrue(done.await(2,TimeUnit.SECONDS));assertFalse(failed.get());assertEquals("348",result.get().get(0).getString("moviedb_id"));assertEquals("https://test.invalid",result.get().get(0).getString("_base"));
    }
    @Test public void cancelledDeliveryCannotOverwriteNewQuery()throws Exception{
        BlockingQueue<Runnable> deliveries=new LinkedBlockingQueue<>();
        SearchRepository repo=new SearchRepository(deliveries::add,(u,t)->"{metas:[]}",1000);
        AtomicInteger calls=new AtomicInteger();
        try{
            repo.fetch(provider(),"old",0,(p,o,i,f,m)->calls.incrementAndGet());
            Runnable stale=deliveries.poll(2,TimeUnit.SECONDS);assertNotNull(stale);repo.cancel();stale.run();assertEquals(0,calls.get());
            repo.fetch(provider(),"new",0,(p,o,i,f,m)->calls.incrementAndGet());deliveries.poll(2,TimeUnit.SECONDS).run();assertEquals(1,calls.get());
        }finally{repo.close();}
    }
    @Test public void deadlineCompletesOnceAndMalformedDataFails()throws Exception{
        AtomicInteger calls=new AtomicInteger();CountDownLatch done=new CountDownLatch(1);
        SearchRepository repo=new SearchRepository(Runnable::run,(u,t)->{Thread.sleep(1000);return "{metas:[]}";},40);
        try{repo.fetch(provider(),"slow",0,(p,o,i,f,m)->{assertTrue(f);calls.incrementAndGet();done.countDown();});assertTrue(done.await(2,TimeUnit.SECONDS));assertEquals(1,calls.get());}finally{repo.close();}
        CountDownLatch malformed=new CountDownLatch(1);AtomicBoolean failed=new AtomicBoolean();
        repo=new SearchRepository(Runnable::run,(u,t)->"<html>Bad gateway</html>",1000);
        try{repo.fetch(provider(),"bad",0,(p,o,i,f,m)->{failed.set(f);malformed.countDown();});assertTrue(malformed.await(2,TimeUnit.SECONDS));assertTrue(failed.get());assertTrue(repo.cache.entries.isEmpty());}finally{repo.close();}
    }
    @Test public void slowProviderDoesNotBlockFastProvider()throws Exception{
        CountDownLatch release=new CountDownLatch(1),fast=new CountDownLatch(1),slow=new CountDownLatch(1);
        SearchRepository repo=new SearchRepository(Runnable::run,(u,t)->{if(u.contains("slow"))release.await();return "{metas:[]}";},2000);
        try{repo.fetch(provider(),"slow",0,(p,o,i,f,m)->slow.countDown());repo.fetch(provider(),"fast",0,(p,o,i,f,m)->fast.countDown());assertTrue(fast.await(1,TimeUnit.SECONDS));assertEquals(1,slow.getCount());release.countDown();assertTrue(slow.await(1,TimeUnit.SECONDS));}finally{release.countDown();repo.close();}
    }
}
