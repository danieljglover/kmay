package uk.kmay.media;

import android.app.Instrumentation;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

/** Exercises the real Android views and preference upgrade on the separate debug app. */
public class BrowserDeviceTest {
    final Instrumentation device=InstrumentationRegistry.getInstrumentation();
    final Context context=device.getTargetContext();
    JSONObject movie() throws Exception {return new JSONObject().put("id","open:fixture").put("type","movie").put("name","A very long title for the KMay television interface").put("_direct","https://example.invalid/test.mp4").put("description","A synopsis without artwork, rating or cast.");}
    MainActivity launch(JSONArray saved)throws Exception{
        assertTrue(context.getPackageName().endsWith(".debug"));
        context.getSharedPreferences("kmay",0).edit().clear().putString("saved",saved.toString()).putString("hiddenRows",new JSONArray(BrowserScreen.HOME_ROWS).toString()).commit();
        return (MainActivity)device.startActivitySync(new Intent().setClassName(context,"uk.kmay.media.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    @Test public void migrationAndCollectionsPreserveExistingData()throws Exception{
        SharedPreferences prefs=context.getSharedPreferences("browser-migration-test",0);JSONObject m=movie();
        JSONArray saved=new JSONArray().put(m),history=new JSONArray().put(new JSONObject(m.toString()).put("_videoId",m.getString("id")));
        prefs.edit().clear().putString("saved",saved.toString()).putString("history",history.toString()).putLong("position:"+m.getString("id"),45000).putString("proxyUrl","https://example.invalid").putString("addons","[]").commit();
        LibraryStore first=new LibraryStore(prefs),second=new LibraryStore(prefs);
        assertEquals(saved.toString(),second.array("saved").toString());assertEquals(45000,prefs.getLong("position:"+m.getString("id"),0));assertEquals("https://example.invalid",prefs.getString("proxyUrl",""));
        first.toggle("collection:Weekend",m);assertTrue(second.contains("collection:Weekend",m));
        first.remember(m,m.getString("id"),"Fixture",60000,120000);assertEquals(120000,prefs.getLong("duration:"+m.getString("id"),0));assertNotNull(first.resume(m));
        first.remember(m,m.getString("id"),"Fixture",100000,120000);assertTrue(first.watched(m.getString("id")));assertNull(first.resume(m));assertEquals(0,prefs.getLong("position:"+m.getString("id"),-1));
        first.clearProgress();assertFalse(first.watched(m.getString("id")));assertTrue(first.contains("collection:Weekend",m));assertTrue(first.contains("saved",m));
        prefs.edit().clear().commit();
    }
    @Test public void sidebarDetailsAndReturnRestoreFocus()throws Exception{
        JSONObject m=movie();MainActivity a=launch(new JSONArray().put(m));
        try{
            device.runOnMainSync(()->{device.setInTouchMode(false);a.library();});device.waitForIdleSync();
            device.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);device.waitForIdleSync();
            View[] original=new View[1];
            device.runOnMainSync(()->{original[0]=a.browser.rails.get(0).findViewHolderForAdapterPosition(0).itemView;device.setInTouchMode(false);assertTrue("Poster focus: touch="+original[0].isInTouchMode()+" shown="+original[0].isShown()+" size="+original[0].getWidth()+"x"+original[0].getHeight(),original[0].requestFocus());});
            device.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);device.waitForIdleSync();
            device.runOnMainSync(()->{assertEquals("details",a.page);assertNotNull(find(a.root,"More options"));assertNotNull(find(a.root,"Read synopsis"));});
            device.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);device.waitForIdleSync();
            device.runOnMainSync(()->{assertEquals("library",a.page);assertSame(original[0],a.getCurrentFocus());
                find(a.root,"Toggle sidebar").performClick();assertTrue(a.prefs.getBoolean("sidebarCompact",false));
                a.browser.collection="Weekend";a.browser.store.putStrings("collections",Arrays.asList("Weekend"));a.browser.store.toggle("collection:Weekend",m);a.library();assertNotNull(find(a.root,"Delete collection"));
                a.settings();assertNotNull(find(a.root,"Autoplay next episode: on"));find(a.root,"Autoplay next episode: on").performClick();assertFalse(a.prefs.getBoolean("autoplayNext",true));
                a.browser.customize();assertEquals("customize",a.page);assertNotNull(find(a.root,"Show"));
            });
        }finally{device.runOnMainSync(a::finish);}
    }
    @Test public void nextEpisodePromptCanBeCancelled()throws Exception{
        JSONObject series=new JSONObject().put("id","series:fixture").put("type","series").put("name","Fixture series").put("videos",new JSONArray().put(new JSONObject().put("season",1).put("episode",1).put("title","First")).put(new JSONObject().put("season",1).put("episode",2).put("title","Next")));
        MainActivity a=launch(new JSONArray());PlayerScreen[] player={null};
        try{
            device.runOnMainSync(()->{a.selected=series;player[0]=new PlayerScreen(a,new JSONObject(),"series:fixture:1:1","First");assertNotNull(player[0].following());player[0].offerNext();assertTrue(player[0].nextDialog.isShowing());});
            screenshot("next-episode-dialog");
            device.runOnMainSync(()->player[0].nextDialog.getButton(-2).performClick());
            device.waitForIdleSync();device.runOnMainSync(()->{assertFalse(player[0].nextDialog.isShowing());assertNull(a.playback);assertEquals("home",a.page);player[0].close();});
        }finally{device.runOnMainSync(a::finish);}
    }
    @Test public void nativePlaybackPauseSeekAndResume()throws Exception{
        MainActivity a=launch(new JSONArray());
        try{
            device.runOnMainSync(()->a.browser.start(a.openFilms().get(0),false,true));
            waitForPlayback(a);
            device.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE);device.waitForIdleSync();
            long[] before={0};device.runOnMainSync(()->{assertFalse(a.playback.player.isPlaying());before[0]=a.playback.player.getCurrentPosition();});
            device.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);device.waitForIdleSync();
            device.runOnMainSync(()->{assertEquals(before[0]+10000,a.playback.player.getCurrentPosition(),250);assertFalse(a.playback.player.isPlaying());assertTrue(a.playback.player.getDuration()>0);a.closePlayer();assertEquals("sources",a.page);assertTrue(a.prefs.getLong("position:open:bbb",0)>0);a.sources(true);});
            waitForPlayback(a);
            device.runOnMainSync(()->{assertTrue("Resume must use saved position",a.playback.player.getCurrentPosition()>=before[0]+9000);a.playback.tracks();});
            device.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);device.waitForIdleSync();
            device.runOnMainSync(()->a.closePlayer());
        }finally{device.runOnMainSync(a::finish);}
    }
    @Test public void seasonTabsWorkWithoutKeyboardFocus()throws Exception{
        JSONObject series=new JSONObject().put("id","tt0000001").put("type","series").put("name","Season fixture")
            .put("videos",new JSONArray().put(new JSONObject().put("season",1).put("episode",1).put("title","First"))
                .put(new JSONObject().put("season",2).put("episode",1).put("title","Second")));
        MainActivity a=launch(new JSONArray());
        try{
            device.runOnMainSync(()->{device.setInTouchMode(true);a.selected=series;a.browser.details(series,false);a.root.clearFocus();find(a.root,"Season 2").performClick();});
            device.waitForIdleSync();
            device.runOnMainSync(()->{assertEquals("details",a.page);assertNotNull(find(a.root,"✓ Season 2"));assertNotNull(find(a.root,"S2 · E1   Second"));a.manualEpisode(series);});
            screenshot("manual-episode-dialog");
            device.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        }finally{device.runOnMainSync(a::finish);}
    }
    void screenshot(String name)throws Exception{
        device.waitForIdleSync();
        Thread.sleep(700); // Allow the dialog's window animation to finish before capture.
        android.graphics.Bitmap bitmap=device.getUiAutomation().takeScreenshot();
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),name+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
        bitmap.recycle();
    }
    void waitForPlayback(MainActivity a)throws Exception{
        long end=System.currentTimeMillis()+45000;boolean[] ready={false};
        while(System.currentTimeMillis()<end){device.runOnMainSync(()->ready[0]=a.playback!=null&&a.playback.player!=null&&a.playback.player.getPlaybackState()==androidx.media3.common.Player.STATE_READY);if(ready[0])return;Thread.sleep(150);}
        fail("Native test HLS stream did not become ready within 45 seconds");
    }
    static View find(View v,String text){
        if(text.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;
        if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View result=find(((ViewGroup)v).getChildAt(i),text);if(result!=null)return result;}
        return null;
    }
}
