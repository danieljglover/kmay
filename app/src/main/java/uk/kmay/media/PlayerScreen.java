package uk.kmay.media;

import android.app.AlertDialog;
import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.*;
import java.util.*;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class PlayerScreen {
    final MainActivity a;
    final JSONObject stream;
    final String id,label;
    ExoPlayer player;
    PlayerView playerView;
    WebView web;
    FrameLayout frame;
    LinearLayout toolbar;
    TextView message,cursor;
    TextView remaining;
    AlertDialog nextDialog;
    boolean nextOffered;
    int countdown;
    final Runnable nextTick=new Runnable(){public void run(){
        if(closed||nextDialog==null||!nextDialog.isShowing())return;
        if(countdown==0){nextDialog.dismiss();nextDialog=null;nextEpisode();return;}
        nextDialog.setMessage("Next episode starts in "+countdown+" seconds.");countdown--;a.handler.postDelayed(this,1000);
    }};
    final Runnable clock=new Runnable(){public void run(){
        if(closed)return;
        if(player!=null&&remaining!=null){long duration=player.getDuration(),position=player.getCurrentPosition();remaining.setText(duration>0?"−"+time(Math.max(0,duration-position))+" remaining":"Live stream");}
        a.handler.postDelayed(this,1000);
    }};
    boolean closed=false,webReady=false,pointer=false,wasPlaying=false;
    volatile boolean captured=false;
    String hostedUrl="";
    float pointerX=.5f,pointerY=.5f;
    long webPosition=0,webDuration=0;
    final Runnable progress=new Runnable(){public void run(){if(closed)return;if(player!=null&&player.getPlaybackState()==Player.STATE_READY){a.remember(player.getCurrentPosition(),player.getDuration());android.util.Log.i("KMayPlayback","NATIVE_PROGRESS seconds="+player.getCurrentPosition()/1000+" playing="+player.isPlaying());}else if(web!=null){web.evaluateJavascript("JSON.stringify(window.__kmayProgress||{})",value->{try{String decoded=(String)new JSONTokener(value).nextValue();JSONObject data=new JSONObject(decoded);long time=(long)(data.optDouble("currentTime",0)*1000),duration=(long)(data.optDouble("duration",0)*1000);if(time>0){webPosition=time;webDuration=duration;a.remember(time,duration);}}catch(Exception ignored){}});}a.handler.postDelayed(this,5000);}};
    PlayerScreen(MainActivity activity,JSONObject source,String videoId,String title){a=activity;stream=source;id=videoId;label=title;}
    void show(){
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);frame=new FrameLayout(a);frame.setBackgroundColor(Color.BLACK);a.setContentView(frame);
        if(stream.optBoolean("_web"))hosted();else nativePlayer();
        a.handler.postDelayed(progress,5000);
        a.handler.post(clock);
    }
    void nativePlayer(){
        Map<String,String> headers=new HashMap<>();JSONObject hints=stream.optJSONObject("behaviorHints");JSONObject proxy=hints==null?null:hints.optJSONObject("proxyHeaders");JSONObject request=proxy==null?null:proxy.optJSONObject("request");
        if(request!=null){Iterator<String> keys=request.keys();while(keys.hasNext()){String k=keys.next();headers.put(k,request.optString(k));}}
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0 KMay/1.0").setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(20000).setDefaultRequestProperties(headers);
        player=new ExoPlayer.Builder(a).setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(a,http))).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true);
        player.setHandleAudioBecomingNoisy(true);
        playerView=new PlayerView(a);playerView.setPlayer(player);playerView.setShowSubtitleButton(true);playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);playerView.setControllerShowTimeoutMs(3000);frame.addView(playerView,new FrameLayout.LayoutParams(-1,-1));
        addToolbar();playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener)visibility->{toolbar.setVisibility(visibility);});
        View controls=playerView.findViewById(androidx.media3.ui.R.id.exo_bottom_bar);if(controls!=null)controls.setBackgroundColor(0xe610111a);
        androidx.media3.ui.DefaultTimeBar timeline=playerView.findViewById(androidx.media3.ui.R.id.exo_progress);
        if(timeline!=null){timeline.setPlayedColor(MainActivity.ACCENT);timeline.setScrubberColor(MainActivity.ACCENT);timeline.setBufferedColor(0xff70677f);}
        MediaItem.Builder media=new MediaItem.Builder().setUri(stream.optString("url")).setMediaId(id).setMediaMetadata(new MediaMetadata.Builder().setTitle(label).build());
        String streamMime=stream.optString("mime");
        if(streamMime.toLowerCase(Locale.ROOT).contains("mpegurl"))media.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if(streamMime.toLowerCase(Locale.ROOT).contains("dash+xml"))media.setMimeType(MimeTypes.APPLICATION_MPD);
        else if(!streamMime.isEmpty())media.setMimeType(streamMime);
        List<MediaItem.SubtitleConfiguration> subtitles=new ArrayList<>();for(JSONObject s:Net.objects(stream.optJSONArray("subtitles"))){String url=s.optString("url");if(!Net.http(url))continue;String mime=url.toLowerCase().contains(".srt")?MimeTypes.APPLICATION_SUBRIP:MimeTypes.TEXT_VTT;subtitles.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(url)).setMimeType(mime).setLanguage(s.optString("lang","en")).setLabel(s.optString("lang","Subtitles")).build());}media.setSubtitleConfigurations(subtitles);
        player.addListener(new Player.Listener(){
            @Override public void onPlaybackStateChanged(int state){if(state==Player.STATE_READY){a.sourceWorked(stream);android.util.Log.i("KMayPlayback","READY duration="+player.getDuration());}if(state==Player.STATE_ENDED){a.remember(player.getDuration(),player.getDuration());offerNext();}}
            @Override public void onPlayerError(PlaybackException error){if(closed)return;if(!hostedUrl.isEmpty()){player.release();player=null;frame.removeAllViews();try{stream.put("url",hostedUrl);}catch(Exception ignored){}hosted();return;}if(a.prefs.getBoolean("proxyEnabled",false)&&a.tryNextSource(PlayerScreen.this))return;new AlertDialog.Builder(a).setTitle("This source couldn’t play").setMessage("The host may be unavailable or this device may not support the stream. Try another source.\n\n"+error.getErrorCodeName()).setPositiveButton("Other sources",(d,w)->a.closePlayer()).setNeutralButton("Retry",(d,w)->{player.prepare();player.play();}).setNegativeButton("Back",(d,w)->a.closePlayer()).setOnCancelListener(d->a.closePlayer()).show();}
        });
        player.setMediaItem(media.build());long start=a.prefs.getLong("position:"+id,0);if(start>0)player.seekTo(start);player.prepare();player.play();playerView.requestFocus();
    }
    void addToolbar(){
        toolbar=a.row();toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setPadding(a.dp(20),a.dp(8),a.dp(20),a.dp(8));toolbar.setBackgroundColor(0xE610111A);toolbar.addView(a.button("‹ Sources",a::closePlayer));TextView title=a.text(label,15,MainActivity.INK);title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);toolbar.addView(title,new LinearLayout.LayoutParams(0,-2,1));remaining=a.text("",12,MainActivity.ACCENT);remaining.setPadding(a.dp(8),a.dp(4),a.dp(12),a.dp(4));toolbar.addView(remaining);toolbar.addView(a.button("Options",this::options));FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,a.dp(76),Gravity.TOP);frame.addView(toolbar,lp);
    }
    String time(long millis){long seconds=millis/1000;return seconds/3600>0?String.format(Locale.ROOT,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60):String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);}
    JSONObject following(){
        if(a.selected==null||!a.selected.optString("type").equals("series"))return null;
        List<JSONObject> episodes=Net.objects(a.selected.optJSONArray("videos"));episodes.sort(Comparator.comparingInt((JSONObject e)->e.optInt("season",1)).thenComparingInt(e->e.optInt("episode",1)));
        for(int i=0;i<episodes.size()-1;i++)if(LibraryStore.episodeId(a.selected,episodes.get(i)).equals(id))return episodes.get(i+1);
        return null;
    }
    void offerNext(){
        if(closed||nextOffered||following()==null)return;nextOffered=true;
        nextDialog=new AlertDialog.Builder(a).setTitle("Up next · "+a.browser.episodeLabel(following())).setMessage("Ready for the next episode?")
            .setPositiveButton("Play next",(d,w)->nextEpisode()).setNegativeButton("Cancel",(d,w)->a.handler.removeCallbacks(nextTick)).create();
        nextDialog.setOnCancelListener(d->a.handler.removeCallbacks(nextTick));nextDialog.show();
        if(a.prefs.getBoolean("autoplayNext",true)){countdown=10;a.handler.post(nextTick);}
    }
    void hosted(){
        web=new WebView(a);web.setBackgroundColor(Color.BLACK);WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setCacheMode(WebSettings.LOAD_NO_CACHE);settings.setMediaPlaybackRequiresUserGesture(false);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(true);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);CookieManager.getInstance().setAcceptThirdPartyCookies(web,true);
        web.setWebChromeClient(new WebChromeClient(){@Override public boolean onCreateWindow(WebView v,boolean d,boolean g,android.os.Message m){return false;}});
        String source=stream.optString("url");String host=Uri.parse(source).getHost();String origin="https://"+host;
        installRemoteBridge();
        web.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest r){
                String url=r.getUrl().toString();String path=r.getUrl().getPath();
                if(!captured&&Net.http(url)&&path!=null&&(path.endsWith(".m3u8")||path.endsWith(".mpd"))){
                    synchronized(PlayerScreen.this){if(captured)return null;captured=true;}
                    Map<String,String> headers=new HashMap<>(r.getRequestHeaders());headers.putIfAbsent("Referer",origin+"/");headers.putIfAbsent("User-Agent",WebSettings.getDefaultUserAgent(a));
                    String cookie=CookieManager.getInstance().getCookie(url);if(cookie!=null)headers.put("Cookie",cookie);
                    a.runOnUiThread(()->{if(closed||web==null)return;hostedUrl=source;try{JSONObject h=new JSONObject();for(Map.Entry<String,String> entry:headers.entrySet())h.put(entry.getKey(),entry.getValue());stream.put("url",url).put("behaviorHints",new JSONObject().put("proxyHeaders",new JSONObject().put("request",h)));}catch(Exception ignored){}web.stopLoading();web.loadUrl("about:blank");frame.removeView(web);web.clearCache(true);web.destroy();web=null;frame.removeAllViews();nativePlayer();});
                }
                return null;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest r){return r.isForMainFrame()&&!"kmay.local".equals(r.getUrl().getHost());}
            @Override public void onPageFinished(WebView view,String url){webReady=true;message.setText("Centre: play / pause    Left / right: seek    Menu: source and pointer controls");a.handler.postDelayed(()->{if(!closed)message.setVisibility(View.GONE);},9000);}
            @Override public void onReceivedError(WebView view,WebResourceRequest r,WebResourceError e){if(r.isForMainFrame())message.setText("Player unavailable. Press Menu to try another source.");}
        });
        long start=a.prefs.getLong("position:"+id,0)/1000;
        String url=source+(source.contains("?")?"&":"?")+"primaryColor=d5b1fa&startAt="+start+"&autoPlay=true";
        String html="<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>html,body,iframe{margin:0;width:100%;height:100%;border:0;background:#000;overflow:hidden}</style></head><body><iframe id='player' src='"+url.replace("&","&amp;").replace("'","&#39;")+"' allow='autoplay; fullscreen; encrypted-media' allowfullscreen></iframe><script>window.__kmayProgress={};window.addEventListener('message',function(e){if(e.origin!=="+JSONObject.quote(origin)+")return;var d=e.data;if(typeof d==='string'){try{d=JSON.parse(d)}catch(x){return}}if(d&&d.type==='PLAYER_EVENT'&&d.data){window.__kmayProgress=d.data;}});</script></body></html>";
        frame.addView(web,new FrameLayout.LayoutParams(-1,-1));web.loadDataWithBaseURL("https://kmay.local/",html,"text/html","UTF-8",null);addToolbar();
        message=a.text("Loading "+stream.optString("name")+"…",14,MainActivity.INK);message.setGravity(Gravity.CENTER);message.setBackgroundColor(0xD910111A);FrameLayout.LayoutParams info=new FrameLayout.LayoutParams(-1,a.dp(42),Gravity.BOTTOM);frame.addView(message,info);
        cursor=a.text("⊕",30,MainActivity.ACCENT);cursor.setGravity(Gravity.CENTER);cursor.setVisibility(View.GONE);frame.addView(cursor,new FrameLayout.LayoutParams(a.dp(40),a.dp(40)));web.requestFocus();
        a.handler.postDelayed(()->{if(!closed&&toolbar!=null)toolbar.setVisibility(View.GONE);},7000);
        a.handler.postDelayed(()->{if(closed||player!=null||webPosition>0)return;if(a.tryNextSource(this))return;message.setText("This source is taking too long. Press Back to choose another.");message.setVisibility(View.VISIBLE);toolbar.setVisibility(View.VISIBLE);},40000);
    }
    void installRemoteBridge(){
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)||!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))return;
        WebViewCompat.addWebMessageListener(web,"kmayMedia",Collections.singleton("*"),(view,msg,origin,mainFrame,reply)->{
            if(closed)return;
            try{JSONObject data=new JSONObject(msg.getData());double t=data.optDouble("time",-1),d=data.optDouble("duration",-1);if(Double.isFinite(t)&&Double.isFinite(d)&&t>=0&&d>120&&t<=d){webPosition=(long)(t*1000);webDuration=(long)(d*1000);a.remember(webPosition,webDuration);if(webDuration>0&&webPosition>=webDuration-1000)offerNext();if(t>0)a.sourceWorked(stream);if(!webReady){webReady=true;}android.util.Log.i("KMayPlayback","WEB_PROGRESS seconds="+(long)t+" duration="+(long)d);}}catch(Exception ignored){}
        });
        long start=a.prefs.getLong("position:"+id,0)/1000;
        String js="(()=>{if(!window.MediaMetadata)window.MediaMetadata=class{constructor(v){Object.assign(this,v)}};if(!navigator.mediaSession)Object.defineProperty(navigator,'mediaSession',{value:{metadata:null,setActionHandler(){},setPositionState(){},playbackState:'none'}});const start="+start+";const restored=new WeakSet();function videos(){return Array.from(document.querySelectorAll('video')).filter(v=>v.duration>120)}function control(c){for(const v of videos()){if(c==='toggle'){v.paused?v.play().catch(()=>{}):v.pause()}if(c==='pause')v.pause();if(c==='play')v.play().catch(()=>{});if(c==='forward')v.currentTime=Math.min(v.duration,v.currentTime+10);if(c==='back')v.currentTime=Math.max(0,v.currentTime-10);if(c==='restart')v.currentTime=0;}for(const f of document.querySelectorAll('iframe'))try{f.contentWindow.postMessage({type:'KMAY_CONTROL',command:c},'*')}catch(e){}}window.addEventListener('message',e=>{if(e.source!==window.parent||!e.data||e.data.type!=='KMAY_CONTROL')return;control(e.data.command)});window.__kmayControl=control;setInterval(()=>{for(const v of videos()){if(!restored.has(v)&&v.readyState>=1){restored.add(v);if(start>0&&start<v.duration-30)v.currentTime=start;}if(v.readyState>=2&&window.kmayMedia)kmayMedia.postMessage(JSON.stringify({time:v.currentTime,duration:v.duration}));}},4000);})();";
        WebViewCompat.addDocumentStartJavaScript(web,js,Collections.singleton("*"));
    }
    void webCommand(String command){if(web==null)return;web.evaluateJavascript("window.__kmayControl&&window.__kmayControl("+JSONObject.quote(command)+")",null);}
    void clickPointer(){if(web==null)return;float x=web.getWidth()*pointerX,y=web.getHeight()*pointerY;long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0);MotionEvent up=MotionEvent.obtain(now,now+70,MotionEvent.ACTION_UP,x,y,0);web.dispatchTouchEvent(down);web.dispatchTouchEvent(up);down.recycle();up.recycle();}
    void movePointer(int key){float step=.025f;if(key==KeyEvent.KEYCODE_DPAD_LEFT)pointerX-=step;if(key==KeyEvent.KEYCODE_DPAD_RIGHT)pointerX+=step;if(key==KeyEvent.KEYCODE_DPAD_UP)pointerY-=step;if(key==KeyEvent.KEYCODE_DPAD_DOWN)pointerY+=step;pointerX=Math.max(.02f,Math.min(.98f,pointerX));pointerY=Math.max(.04f,Math.min(.96f,pointerY));cursor.setX(web.getWidth()*pointerX-a.dp(20));cursor.setY(web.getHeight()*pointerY-a.dp(20));}
    void options(){
        String[] options=player!=null?new String[]{"Choose another source","Play / pause","Restart from beginning","Audio and subtitles","Next episode"}:new String[]{"Choose another source",pointer?"Turn pointer off":"Turn pointer on","Click centre of player","Reload player","Next episode"};
        new AlertDialog.Builder(a).setTitle("Playback options").setItems(options,(d,which)->{
            if(which==0){a.closePlayer();return;}
            if(player!=null){if(which==1){if(player.isPlaying())player.pause();else player.play();}if(which==2)player.seekTo(0);if(which==3)tracks();}
            else {if(which==1){pointer=!pointer;cursor.setVisibility(pointer?View.VISIBLE:View.GONE);movePointer(0);toolbar.setVisibility(View.GONE);web.requestFocus();a.toast(pointer?"Use the arrows to move. Centre clicks. Menu turns the pointer off.":"Pointer off");}if(which==2){pointerX=.5f;pointerY=.5f;clickPointer();}if(which==3)web.reload();}
            if(which==4)nextEpisode();
        }).show();
    }
    void tracks(){
        List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();labels.add("Subtitles off");actions.add(()->player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build()));
        for(Tracks.Group group:player.getCurrentTracks().getGroups())if(group.getType()==C.TRACK_TYPE_AUDIO||group.getType()==C.TRACK_TYPE_TEXT)for(int i=0;i<group.length;i++){if(!group.isTrackSupported(i))continue;final int index=i;Format f=group.getTrackFormat(i);labels.add((group.getType()==C.TRACK_TYPE_AUDIO?"Audio · ":"Subtitles · ")+(f.label!=null?f.label:f.language!=null?f.language:"Track "+(i+1)));actions.add(()->player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(group.getType(),false).setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(),index)).build()));}
        new AlertDialog.Builder(a).setTitle("Audio and subtitles").setItems(labels.toArray(new String[0]),(d,i)->actions.get(i).run()).show();
    }
    void nextEpisode(){
        JSONObject next=following();if(next==null){a.toast("No next episode is listed.");return;}
        close();a.playback=null;a.videoId=LibraryStore.episodeId(a.selected,next);a.videoLabel=a.browser.episodeLabel(next);a.sources(true);
    }
    boolean key(KeyEvent e){
        int k=e.getKeyCode();if(k==KeyEvent.KEYCODE_BACK)return false;
        if(k==KeyEvent.KEYCODE_MENU){if(e.getAction()==KeyEvent.ACTION_DOWN)options();return true;}
        if(player!=null){
            boolean media=k==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||k==KeyEvent.KEYCODE_MEDIA_PLAY||k==KeyEvent.KEYCODE_MEDIA_PAUSE;
            boolean centre=k==KeyEvent.KEYCODE_DPAD_CENTER||k==KeyEvent.KEYCODE_ENTER;
            boolean seek=k==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD||k==KeyEvent.KEYCODE_MEDIA_REWIND||((k==KeyEvent.KEYCODE_DPAD_LEFT||k==KeyEvent.KEYCODE_DPAD_RIGHT)&&!playerView.isControllerFullyVisible());
            if(media||(centre&&!playerView.isControllerFullyVisible())){if(e.getAction()==0){if(k==KeyEvent.KEYCODE_MEDIA_PLAY)player.play();else if(k==KeyEvent.KEYCODE_MEDIA_PAUSE)player.pause();else if(player.isPlaying())player.pause();else player.play();playerView.showController();}return true;}
            if(seek){if(e.getAction()==0){boolean forward=k==KeyEvent.KEYCODE_DPAD_RIGHT||k==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;player.seekTo(Math.max(0,player.getCurrentPosition()+(forward?10000:-10000)));}return true;}
            return false;
        }
        if(web!=null&&pointer){if(k>=KeyEvent.KEYCODE_DPAD_UP&&k<=KeyEvent.KEYCODE_DPAD_RIGHT){if(e.getAction()==0)movePointer(k);return true;}if(k==KeyEvent.KEYCODE_DPAD_CENTER||k==KeyEvent.KEYCODE_ENTER){if(e.getAction()==0)clickPointer();return true;}}
        if(web!=null&&!pointer){String command=k==KeyEvent.KEYCODE_MEDIA_PLAY?"play":k==KeyEvent.KEYCODE_MEDIA_PAUSE?"pause":(k==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||k==KeyEvent.KEYCODE_DPAD_CENTER||k==KeyEvent.KEYCODE_ENTER)?"toggle":(k==KeyEvent.KEYCODE_DPAD_RIGHT||k==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)?"forward":(k==KeyEvent.KEYCODE_DPAD_LEFT||k==KeyEvent.KEYCODE_MEDIA_REWIND)?"back":null;if(command!=null){if(e.getAction()==0)webCommand(command);return true;}}
        return false;
    }
    void pause(){if(player!=null){wasPlaying=player.isPlaying();a.remember(player.getCurrentPosition(),player.getDuration());player.pause();}if(web!=null){webCommand("pause");web.onPause();}}
    void resume(){if(web!=null)web.onResume();}
    void close(){if(closed)return;closed=true;a.handler.removeCallbacks(progress);a.handler.removeCallbacks(clock);a.handler.removeCallbacks(nextTick);if(nextDialog!=null){nextDialog.dismiss();nextDialog=null;}if(player!=null){if(player.getCurrentPosition()>0||player.getDuration()>0)a.remember(player.getCurrentPosition(),player.getDuration());player.release();player=null;}if(web!=null){if(webPosition>0)a.remember(webPosition,webDuration);web.stopLoading();web.loadUrl("about:blank");frame.removeView(web);web.clearCache(true);web.destroy();web=null;}}
}
