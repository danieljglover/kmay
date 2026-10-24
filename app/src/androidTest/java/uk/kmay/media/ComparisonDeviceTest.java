package uk.kmay.media;

import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ComparisonDeviceTest {
    final BrowserDeviceTest support=new BrowserDeviceTest();
    final android.app.Instrumentation device=support.device;
    MainActivity launch(JSONArray saved)throws Exception{return support.launch(saved);}
    void screenshot(String name)throws Exception{support.screenshot(name);}
    void waitForPlayback(MainActivity a)throws Exception{support.waitForPlayback(a);}
    static android.view.View find(android.view.View view,String text){return BrowserDeviceTest.find(view,text);}
    @Test public void fallbackIncludesAnotherStreamFromTheSameProvider()throws Exception{
        MainActivity a=launch(new JSONArray());
        JSONObject first=new JSONObject().put("url","https://example.invalid/one").put("_provider","same");
        JSONObject second=new JSONObject().put("url","https://example.invalid/two").put("_provider","same");
        try{device.runOnMainSync(()->{a.resolved.add(first);a.resolved.add(second);a.attemptedProviders.add("same");a.attemptedStreams.add(first.optString("url"));assertSame(second,a.nextUntestedSource());a.attemptedStreams.add(second.optString("url"));assertNull(a.nextUntestedSource());});}
        finally{device.runOnMainSync(a::finish);}
    }
    @Test public void comparisonProgressCancellationAndNoResults()throws Exception{
        MainActivity a=launch(new JSONArray());
        try{
            JSONObject pending=new JSONObject().put("state","running").put("elapsed_seconds",8).put("working",1)
                .put("providers",new JSONArray().put(new JSONObject().put("name","Working provider").put("state","ready"))
                    .put(new JSONObject().put("name","Another provider").put("state","testing")));
            device.runOnMainSync(()->{
                a.selected=a.openFilms().get(0);a.videoId="open:bbb";a.shell("sources");a.sourceStatus=a.text("",15,MainActivity.INK);a.body.addView(a.sourceStatus);a.sourceList=a.column();a.body.addView(a.sourceList);
                a.comparison=new ComparisonScreen(a);a.comparison.finishing=true;a.comparison.render(pending);
                assertEquals(80,a.comparison.progress.getProgress());assertTrue(a.comparison.rows.getText().toString().contains("Testing video samples"));
            });
            screenshot("comparison-progress");
            device.runOnMainSync(()->{find(a.root,"Cancel").performClick();assertNull(a.comparison);assertNull(a.playback);assertEquals("details",a.page);});
            Thread.sleep(1100);device.runOnMainSync(()->assertNull(a.playback));
            JSONObject empty=new JSONObject().put("state","complete").put("streams",new JSONArray());
            device.runOnMainSync(()->{
                a.shell("sources");a.sourceStatus=a.text("",15,MainActivity.INK);a.body.addView(a.sourceStatus);a.sourceList=a.column();a.body.addView(a.sourceList);
                a.comparison=new ComparisonScreen(a);a.comparison.render(empty);
                assertNotNull(find(a.root,"Retry comparison"));assertNotNull(find(a.root,"Choose source manually"));assertNull(a.playback);
            });
            screenshot("comparison-no-results");
        }finally{device.runOnMainSync(a::finish);}
    }

    @Test public void liveProxyComparisonAutomaticallyPlays()throws Exception{
        String token=InstrumentationRegistry.getArguments().getString("proxyToken","");
        org.junit.Assume.assumeFalse("Pass proxyToken to run live integration",token.isEmpty());
        MainActivity a=launch(new JSONArray());
        try{
            device.runOnMainSync(()->{
                a.prefs.edit().putBoolean("proxyEnabled",true).putString("proxyUrl",InstrumentationRegistry.getArguments().getString("proxyUrl","http://127.0.0.1:8788")).putString("proxyToken",token).commit();
                a.browser.start(a.openFilms().get(0),false,true);
                assertNotNull(a.comparison);assertEquals("Choosing the best source",a.sourceStatus.getText().toString());
            });
            waitForPlayback(a);
            device.runOnMainSync(()->{assertNull(a.comparison);assertFalse(a.resolved.isEmpty());assertEquals("open-film",a.resolved.get(0).optString("provider_id"));assertTrue(a.resolved.get(0).optDouble("sample_mbps")>0);});
            Thread.sleep(1500);screenshot("comparison-live-autoplay");
            device.runOnMainSync(a::closePlayer);
        }finally{device.runOnMainSync(a::finish);}
    }
}
