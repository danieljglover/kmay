package uk.kmay.media;

import org.junit.Test;
import static org.junit.Assert.*;
import android.util.Log;
import android.content.*;
import android.view.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import java.util.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs on Android, not the workstation JVM. Uses synthetic data only. */
public class SearchDeviceTest {
    @Test public void testRapidImeAndVoiceReplacementWithFocusedResults()throws Exception{
        android.app.Instrumentation device=InstrumentationRegistry.getInstrumentation();
        Context context=device.getTargetContext();assertTrue(context.getPackageName().endsWith(".debug"));
        JSONArray saved=new JSONArray();
        for(int i=0;i<60;i++)saved.put(new JSONObject().put("id","open:stress"+i).put("name","Fixture title "+i).put("type","movie"));
        context.getSharedPreferences("kmay",0).edit().clear().putString("saved",saved.toString()).commit();
        MainActivity a=(MainActivity)device.startActivitySync(new Intent().setClassName(context,"uk.kmay.media.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            device.runOnMainSync(()->{a.search();a.searchScreen.input.setText("Fixture");a.searchScreen.run(true);});
            device.waitForIdleSync();SearchScreen screen=a.searchScreen;
            AtomicBoolean reset=new AtomicBoolean();
            device.runOnMainSync(()->{
                device.setInTouchMode(false);assertTrue(screen.grid.findViewHolderForAdapterPosition(0).itemView.requestFocus());
                screen.adapter.registerAdapterDataObserver(new androidx.recyclerview.widget.RecyclerView.AdapterDataObserver(){@Override public void onChanged(){reset.set(true);}});
                Intent speech=new Intent().putStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS,new ArrayList<>(Arrays.asList("Fixture title 59")));
                screen.voiceResult(android.app.Activity.RESULT_OK,speech);
                assertTrue("Replacing a focused result list must notify a full reset",reset.get());
            });
            device.waitForIdleSync();
            for(int i=0;i<25;i++){
                final int iteration=i;
                device.runOnMainSync(()->{
                    screen.input.requestFocus();screen.input.selectAll();
                    android.view.inputmethod.InputConnection ime=screen.input.onCreateInputConnection(new android.view.inputmethod.EditorInfo());
                    ime.beginBatchEdit();ime.setComposingText("Fixture "+iteration,1);ime.commitText("Fixture title "+iteration,1);ime.endBatchEdit();
                    ime.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
                });
                Thread.sleep(35);
            }
            device.waitForIdleSync();
            device.runOnMainSync(()->{assertFalse(a.isFinishing());assertEquals("Fixture title 24",screen.request.query);assertEquals(screen.displayed.size(),screen.adapter.getItemCount());});
        }finally{device.runOnMainSync(a::finish);}
    }
    @Test public void testHttpPaginationPartialFailureAndRetry()throws Exception{
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        assertTrue(context.getPackageName().endsWith(".debug"));
        try(FixtureServer server=new FixtureServer()){
            String base="http://127.0.0.1:"+server.socket.getLocalPort();
            JSONObject manifest=new JSONObject("{name:'Search test',catalogs:[{id:'good',type:'movie',extra:[{name:'search'},{name:'skip'}]},{id:'fail',type:'movie',extra:[{name:'search'}]}]}");
            JSONArray addons=new JSONArray().put(new JSONObject().put("base",base).put("manifest",manifest));
            context.getSharedPreferences("kmay",0).edit().clear().putString("addons",addons.toString()).commit();
            MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent().setClassName(context,"uk.kmay.media.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try{
                instrumentation.runOnMainSync(()->{activity.search();activity.searchScreen.input.setText("Qzxfixture");activity.searchScreen.run(true);});
                waitForSearch(instrumentation,activity.searchScreen);
                SearchScreen screen=activity.searchScreen;
                instrumentation.runOnMainSync(()->{
                    assertEquals(100,screen.results.size());assertEquals(View.VISIBLE,screen.retry.getVisibility());assertEquals(View.VISIBLE,screen.more.getVisibility());
                    assertEquals(base,screen.displayed.get(0).item().optString("_base"));screen.more.performClick();
                });
                waitForSearch(instrumentation,screen);
                instrumentation.runOnMainSync(()->{assertEquals(101,screen.results.size());assertEquals(View.GONE,screen.more.getVisibility());server.fail.set(false);screen.retry.performClick();});
                waitForSearch(instrumentation,screen);
                instrumentation.runOnMainSync(()->{
                    assertEquals(101,screen.results.size());
                    for(String key:screen.failedPages.keySet())assertFalse(key.contains("|fail"));
                    assertEquals(2,screen.results.get("movie:imdb:tt900000").references.size());
                });
            }finally{instrumentation.runOnMainSync(activity::finish);}
        }
    }
    void waitForSearch(android.app.Instrumentation instrumentation,SearchScreen screen)throws Exception{
        long end=System.currentTimeMillis()+12000;int[] pending={1};
        do{instrumentation.waitForIdleSync();instrumentation.runOnMainSync(()->pending[0]=screen.pending);if(pending[0]==0)return;Thread.sleep(50);}while(System.currentTimeMillis()<end);
        fail("Search did not finish within its provider deadline");
    }
    static final class FixtureServer implements AutoCloseable {
        final ServerSocket socket=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));
        final ExecutorService jobs=Executors.newCachedThreadPool();final AtomicBoolean fail=new AtomicBoolean(true);
        FixtureServer()throws Exception{jobs.execute(()->{while(!socket.isClosed())try{Socket client=socket.accept();jobs.execute(()->respond(client));}catch(IOException e){break;}});}
        void respond(Socket client){try(Socket connection=client){
            BufferedReader input=new BufferedReader(new InputStreamReader(connection.getInputStream(),StandardCharsets.UTF_8));String request=input.readLine();String line;while((line=input.readLine())!=null&&!line.isEmpty()){}
            boolean bad=request.contains("/fail/")&&fail.get();JSONArray items=new JSONArray();
            int start=request.contains("skip=100")?100:0,count=start==100?1:100;
            if(request.contains("/fail/"))count=1;
            for(int i=start;i<start+count;i++){
                JSONObject item=new JSONObject().put("id","tt"+(900000+i)).put("name","Qzxfixture "+i).put("type","movie");
                if(request.contains("/fail/"))item.put("id","provider-specific").put("imdb_id","tt900000");items.put(item);
            }
            byte[] body=new JSONObject().put("metas",items).toString().getBytes(StandardCharsets.UTF_8);
            OutputStream output=connection.getOutputStream();output.write(("HTTP/1.1 "+(bad?"503 Unavailable":"200 OK")+"\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));output.write(body);output.flush();
        }catch(Exception ignored){}}
        @Override public void close()throws IOException{socket.close();jobs.shutdownNow();}
    }

    @Test public void testRemoteFocusFiltersHistoryAndDetails()throws Exception{
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        // Debug uses its own application ID, so test data cannot replace the personal library.
        assertTrue(context.getPackageName().endsWith(".debug"));
        JSONArray saved=new JSONArray();
        for(int i=0;i<40;i++)saved.put(new JSONObject().put("id","open:test"+i).put("name","Qzxfixture "+String.format(Locale.ROOT,"%02d",i)).put("type",i==39?"series":"movie").put("_direct","https://example.invalid/test.mp4"));
        context.getSharedPreferences("kmay",0).edit().clear().putString("saved",saved.toString()).commit();
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent().setClassName(context,"uk.kmay.media.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            instrumentation.runOnMainSync(()->{activity.search();activity.searchScreen.input.setText("Qzxfixture");activity.searchScreen.run(true);});
            instrumentation.waitForIdleSync();
            SearchScreen screen=activity.searchScreen;
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->{
                assertEquals(40,screen.displayed.size());
                instrumentation.setInTouchMode(false);assertTrue(screen.grid.findViewHolderForAdapterPosition(0).itemView.requestFocus());
            });
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->assertTrue("Right selected "+screen.focusedKey,screen.focusedKey.endsWith("open:test1")));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            instrumentation.waitForIdleSync();
            String[] focus={""};instrumentation.runOnMainSync(()->focus[0]=screen.focusedKey);
            int columns=((androidx.recyclerview.widget.GridLayoutManager)screen.grid.getLayoutManager()).getSpanCount();
            assertTrue("Down selected "+focus[0],focus[0].endsWith("open:test"+(1+columns)));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->{
                View focused=activity.getCurrentFocus();int[] position=new int[2],gridPosition=new int[2];focused.getLocationOnScreen(position);screen.grid.getLocationOnScreen(gridPosition);
                assertTrue("Focused poster is clipped at top",position[1]>=gridPosition[1]);
                assertTrue("Focused poster is clipped at bottom",position[1]+focused.getHeight()<=gridPosition[1]+screen.grid.getHeight());
                focus[0]=screen.focusedKey;
            });
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->assertEquals("details",activity.page));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->{
                assertEquals("search",activity.page);assertEquals(focus[0],screen.focusedKey);assertTrue(screen.grid.hasFocus());
                screen.filters.get(2).performClick();assertEquals(1,screen.displayed.size());assertEquals("series",screen.displayed.get(0).item().optString("type"));
                for(int i=0;i<25;i++){screen.changing=true;screen.input.setText("Recent "+i);screen.changing=false;screen.remember();}
                assertEquals(20,activity.arrayPref("searchHistory").length());
                screen.input.setText("");screen.run(false);screen.historyToggle.performClick();
                assertEquals(0,activity.arrayPref("searchHistory").length());assertFalse(activity.prefs.getBoolean("searchHistoryEnabled",true));
                screen.input.setText("Not stored");screen.remember();assertEquals(0,activity.arrayPref("searchHistory").length());
                screen.input.setText("");activity.home();activity.search();
                assertTrue("Clearing before leaving must not restore stale results",screen.displayed.isEmpty());
            });
        }finally{instrumentation.runOnMainSync(activity::finish);}
    }

    @Test public void testThousandTitleSearchLatency()throws Exception{
        List<JSONObject> fixture=new ArrayList<>();
        for(int i=0;i<1000;i++)fixture.add(new JSONObject().put("id","tt"+(10000+i)).put("name","Amélie Adventure "+i).put("type","movie"));
        List<Long> measurements=new ArrayList<>();
        for(int run=0;run<35;run++){
            long start=System.nanoTime();
            SearchModel.Request request=new SearchModel.Request("amelie","");
            Map<String,SearchModel.Result> results=new LinkedHashMap<>();
            for(JSONObject item:fixture)if(request.accepts(item)&&SearchModel.rank(item.optString("name"),request.query,true)<4){SearchModel.Result result=new SearchModel.Result(item,true);results.put(result.key,result);}
            assertEquals(1000,SearchModel.ranked(results.values(),request).size());
            long millis=(System.nanoTime()-start)/1_000_000;
            if(run>=5)measurements.add(millis);
        }
        Collections.sort(measurements);long p95=measurements.get(28);
        Log.i("KMaySearchTest","fixture=1000 samples=30 local_p95_ms="+p95);
        assertTrue("1000 title search p95 was "+p95+"ms",p95<=100);
    }
}
