package uk.kmay.media;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.bumptech.glide.Glide;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static final int BG=TvStyle.BACKGROUND, PANEL=TvStyle.SURFACE, INK=TvStyle.TEXT, MUTED=TvStyle.SECONDARY, ACCENT=TvStyle.ACCENT;
    final ExecutorService jobs=Executors.newFixedThreadPool(6);
    final Handler handler=new Handler(Looper.getMainLooper());
    SharedPreferences prefs;
    BrowserScreen browser;
    final TvStyle style=new TvStyle(this);
    LinearLayout root,body,nav;
    int generation=0;
    String page="home",filter="",query="",genre="";
    JSONObject selected;
    String videoId="",videoLabel="";
    PlayerScreen playback;
    SearchScreen searchScreen;
    ComparisonScreen comparison;
    boolean searchOrigin=false;
    List<JSONObject> resolved=new ArrayList<>();
    int pending=0;
    boolean automatic=false,autoStarted=false;
    LinearLayout sourceList;
    TextView sourceStatus;
    Set<String> streamKeys=new HashSet<>();
    Set<String> attemptedProviders=new HashSet<>();
    Set<String> attemptedStreams=new HashSet<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);prefs=getSharedPreferences("kmay",MODE_PRIVATE);browser=new BrowserScreen(this);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        home();
    }
    int dp(float x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));t.setPadding(0,dp(4),0,dp(4));return t;}
    LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    GradientDrawable shape(int color,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(8));if(stroke!=0)d.setStroke(dp(2),stroke);return d;}
    StateListDrawable focusBg(){return style.background(0,false);}
    Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);style.button(b,false);b.setOnClickListener(v->action.run());LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(44));lp.setMargins(0,dp(4),dp(10),dp(4));b.setLayoutParams(lp);return b;}
    LinearLayout dialogForm(View... fields){
        LinearLayout form=column();form.setPadding(dp(24),dp(12),dp(24),dp(8));
        for(View field:fields){if(field instanceof EditText){field.setMinimumHeight(dp(48));field.setPadding(dp(12),dp(8),dp(12),dp(8));}
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(8));form.addView(field,lp);}
        return form;
    }
    TextView sectionTitle(String label){TextView t=text(label,19,INK);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));t.setPadding(0,dp(24),0,dp(10));return t;}
    void shell(String name){browser.shell(name);}
    void heading(String title,String sub){TextView h=text(title,32,INK);h.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));h.setLetterSpacing(-.025f);body.addView(h);if(!sub.isEmpty())body.addView(text(sub,13,MUTED));}
    void home(){browser.home();}
    void cards(LinearLayout section,List<JSONObject> items,boolean resume){browser.cards(section,items,resume,false);}
    void posterRow(String title,List<JSONObject> items,boolean resume){browser.rail(title,items,resume);}
    List<JSONObject> openFilms(){
        List<JSONObject> list=new ArrayList<>();
        String[][] films={{"bbb","Big Buck Bunny","2008","https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8","https://storage.googleapis.com/gtv-videos-bucket/sample/images/BigBuckBunny.jpg"}};
        for(String[] f:films)try{list.add(new JSONObject().put("id","open:"+f[0]).put("name",f[1]).put("year",f[2]).put("type","movie").put("poster",f[4]).put("background",f[4]).put("_direct",f[3]).put("description","An independently created Blender open film. Stream directly, with no account needed."));}catch(Exception ignored){}
        return list;
    }
    void details(JSONObject item){
        if(!page.equals("details"))browser.capture();selected=item;showDetails(item,!item.has("_direct"));if(item.has("_direct"))return;
        int g=generation;String base=item.optString("_base",Net.CATALOG);
        jobs.execute(()->{try{JSONObject full=searchMetadata(item);runOnUiThread(()->{if(g!=generation)return;selected=full;showDetails(full,false);});}catch(Exception e){runOnUiThread(()->{if(g==generation){showDetails(item,false);body.addView(text("Details unavailable: "+Net.message(e),14,MUTED));body.addView(button("Retry details",()->details(item)));}});}});
    }
    JSONObject searchMetadata(JSONObject item)throws Exception{
        List<JSONObject> alternatives=new ArrayList<>();alternatives.add(item);
        alternatives.addAll(Net.objects(item.optJSONArray("_searchAlternatives")));
        Set<String> tried=new HashSet<>();Exception failure=null;
        for(JSONObject candidate:alternatives){
            String base=candidate.optString("_base",Net.CATALOG);
            String id=candidate.optString("id"),type=candidate.optString("type","movie");
            if(!tried.add(base+"|"+type+"|"+id))continue;
            try{
                JSONObject full=Net.json(base+"/meta/"+Net.segment(type)+"/"+Net.segment(id)+".json").getJSONObject("meta");
                full.put("_base",base);if(!full.has("id"))full.put("id",id);if(!full.has("type"))full.put("type",type);
                return full;
            }catch(Exception e){failure=e;}
        }
        throw failure==null?new Exception("No title details available"):failure;
    }
    void showDetails(JSONObject m,boolean loading){browser.details(m,loading);}
    void manualEpisode(JSONObject m){EditText season=new EditText(this);season.setHint("Season");season.setInputType(2);season.setText("1");EditText episode=new EditText(this);episode.setHint("Episode");episode.setInputType(2);episode.setText("1");new AlertDialog.Builder(this).setTitle("Choose episode").setView(dialogForm(season,episode)).setPositiveButton("Find sources",(d,w)->{try{int s=Integer.parseInt(season.getText().toString()),e=Integer.parseInt(episode.getText().toString());if(s<0||e<1)throw new Exception();videoId=m.optString("id")+":"+s+":"+e;videoLabel="S"+s+" · E"+e;sources(true);}catch(Exception ex){toast("Enter valid season and episode numbers.");}}).setNegativeButton("Cancel",null).show();}
    void search(){
        if(searchScreen==null)searchScreen=new SearchScreen(this);
        searchScreen.show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==SearchScreen.VOICE_REQUEST&&searchScreen!=null)searchScreen.voiceResult(result,data);
    }
    JSONArray arrayPref(String key){try{return new JSONArray(prefs.getString(key,"[]"));}catch(Exception e){return new JSONArray();}}
    boolean isSaved(JSONObject m){return browser.store.contains("saved",m);}
    void toggleSaved(JSONObject m){browser.store.toggle("saved",m);}
    void library(){browser.library();}
    void remember(long position,long duration){browser.store.remember(selected,videoId,videoLabel,position,duration);}
    void sources(boolean auto){
        shell("sources");automatic=auto;autoStarted=false;resolved=new ArrayList<>();streamKeys.clear();attemptedProviders.clear();attemptedStreams.clear();pending=0;
        heading(selected.optString("name"),videoLabel);sourceStatus=text("Finding playback options…",15,MUTED);body.addView(sourceStatus);sourceList=column();body.addView(sourceList);
        if(auto&&prefs.getBoolean("proxyEnabled",false)){comparison=new ComparisonScreen(this);comparison.start();return;}
        body.addView(button("Refresh sources",()->sources(false)));body.addView(button("Source settings",this::settings));int g=generation;
        if(prefs.getBoolean("proxyEnabled",false)){proxySources(g);return;}
        if(selected.has("_direct")){try{addStream(new JSONObject().put("name","Open film · direct HD").put("url",selected.getString("_direct")),"Blender open films");}catch(Exception ignored){}}
        for(JSONObject addon:Net.objects(arrayPref("addons"))){JSONObject manifest=addon.optJSONObject("manifest");if(manifest==null||!Net.supports(manifest,"stream",selected.optString("type","movie"),videoId))continue;pending++;String url=addon.optString("base")+"/stream/"+Net.segment(selected.optString("type","movie"))+"/"+Net.segment(videoId)+".json";jobs.execute(()->{try{List<JSONObject> streams=Net.objects(Net.json(url).optJSONArray("streams"));runOnUiThread(()->{if(g!=generation)return;pending--;for(JSONObject s:streams)addStream(s,manifest.optString("name"));updateSources();});}catch(Exception e){runOnUiThread(()->{if(g!=generation)return;pending--;sourceList.addView(text(manifest.optString("name")+": "+Net.message(e),13,MUTED));updateSources();});}});}
        if(!selected.has("_direct")&&prefs.getBoolean("webSources",true)){
            String tmdb=selected.optString("moviedb_id","");String type=selected.optString("type","movie");String[] parts=videoId.split(":");String suffix=type.equals("series")&&parts.length>=3?"/"+parts[parts.length-2]+"/"+parts[parts.length-1]:"";
            if(!tmdb.isEmpty()&&(!type.equals("series")||!suffix.isEmpty())){
                String kind=type.equals("series")?"tv":"movie";
                String rive="https://www.rivestream.app/embed?type="+kind+"&id="+tmdb+(suffix.isEmpty()?"":"&season="+parts[parts.length-2]+"&episode="+parts[parts.length-1]);
                try{addStream(new JSONObject().put("name","Rive").put("_web",true).put("url",rive).put("description","Champagne-listed · multi-source hosted player"),"Rive");}catch(Exception ignored){}
                for(String host:new String[]{"vidlink.pro","vidfast.pro"})try{addStream(new JSONObject().put("name",host.equals("vidlink.pro")?"VidLink":"VidFast").put("_web",true).put("url","https://"+host+"/"+kind+"/"+tmdb+suffix+"?autoplay=true").put("description","Hosted player · availability depends on the service"),host);}catch(Exception ignored){}
            }
        }
        updateSources();
    }
    void addStream(JSONObject s,String provider){
        String url=s.optString("url");if(!Net.http(url))return;
        if(!streamKeys.add(url))return;
        try{s.put("_provider",provider);}catch(Exception ignored){}resolved.add(s);
        Button b=button(s.optString("name",provider)+"   "+s.optString("description",s.optString("title","")),()->{automatic=false;play(s);});b.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);b.setMaxLines(2);sourceList.addView(b,sourceRowParams());
    }
    LinearLayout.LayoutParams sourceRowParams(){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(64));lp.setMargins(0,dp(4),0,dp(4));return lp;}
    void updateSources(){
        sourceStatus.setText(pending>0?resolved.size()+" playback options · checking "+pending+" more sources…":resolved.isEmpty()?"No playable HTTP streams found. Add an HTTP/debrid add-on in Settings, or retry later. Torrent-only results need a debrid service.":"Choose a source, or press Play next time to use your preferred one.");
        if(automatic&&!autoStarted&&!resolved.isEmpty()&&pending==0){autoStarted=true;String type=selected.optString("type");String preferred=prefs.getString("preferred:"+type,type.equals("series")?"vidlink.pro":"vidfast.pro");JSONObject choice=resolved.get(0);for(JSONObject s:resolved)if(s.optString("_provider").equals(preferred)){choice=s;break;}JSONObject stream=choice;int g=generation;handler.postDelayed(()->{if(g==generation)play(stream);},250);}
    }
    void play(JSONObject stream){
        if(stream.optBoolean("_resolve")){resolveProxy(stream);return;}
        if(searchScreen!=null)searchScreen.hide();generation++;page="player";attemptedProviders.add(stream.optString("_provider"));attemptedStreams.add(stream.optString("url"));playback=new PlayerScreen(this,stream,videoId,videoLabel);playback.show();
    }
    void proxySources(int g){
        pending=1;
        final JSONObject title;
        try{title=ProxyClient.title(this);}catch(Exception e){pending=0;sourceStatus.setText("This title has incomplete episode information.");return;}
        jobs.execute(()->{try{
            JSONObject status=ProxyClient.request(this,"/v1/status",null);if(status.optInt("api_version")!=1)throw new Exception("Incompatible server API version.");
            JSONObject response=ProxyClient.request(this,"/v1/resolve",title);
            runOnUiThread(()->{if(g!=generation)return;pending=0;
                for(JSONObject s:Net.objects(response.optJSONArray("streams")))addStream(s,s.optString("provider_id"));
                for(JSONObject p:Net.objects(response.optJSONArray("providers")))try{addStream(new JSONObject().put("url",prefs.getString("proxyUrl","")+"/#"+p.getString("id")).put("name",p.optString("name")).put("description","Resolve through your media server").put("_resolve",true),p.getString("id"));}catch(Exception ignored){}
                updateSources();if(resolved.isEmpty())sourceStatus.setText("No verified sources are available. Test the server connection or refresh server sources in Settings.");
            });
        }catch(Exception e){runOnUiThread(()->{if(g!=generation)return;pending=0;sourceStatus.setText("Server unavailable: "+Net.message(e)+" Use Refresh sources to retry.");});}});
    }
    void resolveProxy(JSONObject candidate){
        if(page.equals("player")){shell("sources");heading(selected.optString("name"),videoLabel);sourceStatus=text("Finding another source…",15,MUTED);body.addView(sourceStatus);body.addView(button("Back to sources",()->sources(false)));}
        int g=++generation;attemptedProviders.add(candidate.optString("_provider"));sourceStatus.setText("Resolving "+candidate.optString("name")+" on your server…");
        final JSONObject title;
        try{title=ProxyClient.title(this).put("provider_id",candidate.optString("_provider"));}catch(Exception e){toast("Incomplete title information");return;}
        jobs.execute(()->{try{
            JSONObject response=ProxyClient.request(this,"/v1/resolve",title);JSONArray streams=response.optJSONArray("streams");
            if(streams==null||streams.length()==0)throw new Exception("This provider has no playable stream for this title.");
            JSONObject stream=streams.getJSONObject(0);stream.put("_provider",candidate.optString("_provider"));
            runOnUiThread(()->{if(g==generation)play(stream);});
        }catch(Exception e){runOnUiThread(()->{if(g!=generation)return;if(automatic){for(JSONObject next:resolved)if(!attemptedProviders.contains(next.optString("_provider"))){resolveProxy(next);return;}}sourceStatus.setText(Net.message(e)+" Choose another source or refresh.");});}});
    }
    void sourceWorked(JSONObject stream){prefs.edit().putString("preferred:"+selected.optString("type"),stream.optString("_provider")).apply();}
    JSONObject nextUntestedSource(){for(JSONObject s:resolved)if(s.optBoolean("_resolve")?!attemptedProviders.contains(s.optString("_provider")):!attemptedStreams.contains(s.optString("url")))return s;return null;}
    boolean tryNextSource(PlayerScreen failed){if(!automatic||playback!=failed)return false;JSONObject next=nextUntestedSource();if(next!=null){failed.close();playback=null;toast("Trying another tested stream…");play(next);return true;}closePlayer();toast("These sources are unavailable right now. Try again later or choose another title.");return true;}
    void closePlayer(){if(playback!=null){playback.close();playback=null;}getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);sources(false);}
    void settings(){browser.settings();}
    void addAddon(){
        if(prefs.getBoolean("proxyEnabled",false)){ProxySettings.addSource(this);return;}
        EditText input=new EditText(this);input.setSingleLine();input.setHint("https://your-addon/manifest.json");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Add an HTTP source or catalogue").setMessage("Paste the configured Stremio manifest URL. Account tokens in that URL are stored privately on this device.").setView(dialogForm(input)).setPositiveButton("Add",null).setNegativeButton("Cancel",null).create();dialog.show();
        dialog.getButton(-1).setOnClickListener(v->{String url=input.getText().toString().trim().replaceFirst("^stremio://","https://");if(!Net.http(url)||!url.endsWith("/manifest.json")){input.setError("Use a full /manifest.json URL");return;}String base=url.substring(0,url.length()-14);dialog.getButton(-1).setEnabled(false);dialog.getButton(-1).setText("Checking…");jobs.execute(()->{try{JSONObject manifest=Net.json(url);if(!manifest.has("id")||!manifest.has("resources")||!manifest.has("name"))throw new Exception("This is not a Stremio manifest.");JSONObject addon=new JSONObject().put("base",base).put("manifest",manifest);runOnUiThread(()->{JSONArray out=new JSONArray();for(JSONObject old:Net.objects(arrayPref("addons")))if(!old.optString("base").equals(base))out.put(old);out.put(addon);prefs.edit().putString("addons",out.toString()).apply();dialog.dismiss();settings();toast("Source added");});}catch(Exception e){runOnUiThread(()->{dialog.getButton(-1).setEnabled(true);dialog.getButton(-1).setText("Add");input.setError(Net.message(e));});}});});
    }
    void directUrl(){EditText input=new EditText(this);input.setSingleLine();input.setHint("https://…/video.m3u8 or .mp4");new AlertDialog.Builder(this).setTitle("Play a direct stream").setView(dialogForm(input)).setPositiveButton("Play",(d,w)->{String url=input.getText().toString().trim();if(!Net.http(url)){toast("Enter an HTTP or HTTPS media URL.");return;}try{selected=new JSONObject().put("id","direct:"+url.hashCode()).put("name","Direct stream").put("type","movie").put("_direct",url);videoId=selected.getString("id");videoLabel="Direct stream";sources(true);}catch(Exception ignored){}}).setNegativeButton("Cancel",null).show();}
    void external(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception e){toast("No browser installed.");}}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override public void onBackPressed(){
        android.util.Log.i("KMayNavigation","back page="+page+" search="+searchOrigin+" generation="+generation);
        if(playback!=null){closePlayer();return;}
        if(page.equals("sources")&&selected!=null){details(selected);return;}
        if(page.equals("details")){if(searchOrigin)search();else if(!browser.restore())home();return;}
        if(page.equals("customize")||page.equals("sourceSettings")){settings();return;}
        if(!page.equals("home")||!filter.isEmpty()){filter="";query="";genre="";home();return;}
        new AlertDialog.Builder(this).setTitle("All done for tonight?").setPositiveButton("Exit",(d,w)->finish()).setNegativeButton("Keep browsing",null).show();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent e){if(playback!=null&&playback.key(e))return true;return super.dispatchKeyEvent(e);}
    @Override protected void onPause(){super.onPause();if(playback!=null)playback.pause();}
    @Override protected void onResume(){super.onResume();if(playback!=null)playback.resume();}
    @Override protected void onDestroy(){if(comparison!=null)comparison.cancel();if(searchScreen!=null)searchScreen.close();if(playback!=null)playback.close();jobs.shutdownNow();handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
