package uk.kmay.media;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import org.json.*;
import java.util.*;

/** TV presentation. Source resolution and the two playback engines stay in their existing owners. */
final class BrowserScreen {
    final MainActivity a;
    final LibraryStore store;
    final Map<String,List<JSONObject>> cache=new HashMap<>();
    final List<RecyclerView> rails=new ArrayList<>();
    Snapshot origin;
    String sort="Recommended",watchFilter="All",collection="";
    boolean browse=false;
    CatalogRepository.Catalog activeCatalog;
    FrameLayout hero;
    final Map<String,Integer> seasons=new HashMap<>();
    static final List<String> HOME_ROWS=Arrays.asList("Continue watching","My list","Films","Series","Open films","Add-on catalogues");

    BrowserScreen(MainActivity a){this.a=a;store=new LibraryStore(a.prefs);}
    static final class Snapshot {
        LinearLayout root,body,nav; View focus; String page; List<RecyclerView> rails;
    }
    void capture(){
        if(!Arrays.asList("home","library","catalog").contains(a.page))return;
        origin=new Snapshot();origin.root=a.root;origin.body=a.body;origin.nav=a.nav;
        origin.focus=a.getCurrentFocus();origin.page=a.page;origin.rails=new ArrayList<>(rails);
    }
    boolean restore(){
        if(origin==null)return false;
        Snapshot s=origin;origin=null;a.generation++;a.page=s.page;a.root=s.root;a.body=s.body;a.nav=s.nav;
        a.setContentView(s.root);rails.clear();rails.addAll(s.rails);
        for(RecyclerView rail:rails)if(rail.getAdapter() instanceof Posters){
            Posters adapter=(Posters)rail.getAdapter();if(!adapter.localKey.isEmpty()){adapter.items.clear();adapter.items.addAll(Net.objects(store.array(adapter.localKey)));}
            adapter.notifyDataSetChanged();
        }
        if(s.focus!=null)s.focus.post(s.focus::requestFocus);
        return true;
    }
    void navigate(String destination){
        origin=null;a.query="";a.genre="";a.filter="";a.searchOrigin=false;
        switch(destination){
            case "Films":a.filter="movie";a.home();break;
            case "Series":a.filter="series";a.home();break;
            case "My list":a.library();break;
            case "Search":a.search();break;
            case "Settings":a.settings();break;
            default:a.home();
        }
    }
    void shell(String page){
        if(a.comparison!=null)a.comparison.cancel();
        if(a.searchScreen!=null&&!page.equals("search"))a.searchScreen.hide();
        if(page.equals("home")||page.equals("library")||page.equals("settings"))a.searchOrigin=false;
        a.generation++;a.page=page;rails.clear();
        a.root=a.row();a.root.setGravity(Gravity.TOP);a.root.setBackgroundColor(MainActivity.BG);
        boolean compact=a.prefs.getBoolean("sidebarCompact",false);
        a.nav=a.column();a.nav.setPadding(a.dp(16),a.dp(24),a.dp(16),a.dp(20));a.nav.setBackgroundColor(MainActivity.BG);
        ScrollView side=new ScrollView(a);side.setVerticalScrollBarEnabled(false);side.addView(a.nav);
        a.root.addView(side,new LinearLayout.LayoutParams(a.dp(compact?76:168),-1));
        TextView brand=a.text(compact?"k.":"kmay.",31,MainActivity.INK);brand.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));brand.setLetterSpacing(-.065f);brand.setPadding(a.dp(12),0,0,a.dp(5));
        a.nav.addView(brand,new LinearLayout.LayoutParams(-1,a.dp(51)));
        TextView caption=a.text("PERSONAL CINEMA",8,MainActivity.MUTED);a.style.label(caption);caption.setTextSize(8);caption.setPadding(a.dp(12),0,0,a.dp(28));caption.setVisibility(compact?View.GONE:View.VISIBLE);a.nav.addView(caption);
        String selected=page.equals("search")?"Search":page.equals("settings")||page.equals("customize")||page.equals("sourceSettings")?"Settings":page.equals("library")?"My list":a.filter.equals("movie")?"Films":a.filter.equals("series")?"Series":"Home";
        String[] names={"Home","Films","Series","My list","Search","Settings"};
        List<Button> buttons=new ArrayList<>();
        for(String name:names){
            Button button=a.button(compact?"":name,()->navigate(name));button.setContentDescription(name);button.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);button.setTextSize(14);button.setCompoundDrawablePadding(a.dp(14));button.setPadding(a.dp(12),0,a.dp(8),0);
            boolean active=selected.equals(name);button.setTextColor(active?MainActivity.INK:MainActivity.MUTED);
            button.setCompoundDrawables(a.style.icon(name,active?MainActivity.ACCENT:MainActivity.MUTED),null,null,null);
            button.setBackground(a.style.background(active?0xff232027:0,false));a.nav.addView(button,navParams(44));buttons.add(button);
        }
        List<Button> pins=new ArrayList<>();List<String> pinNames=new ArrayList<>();
        for(String key:store.strings("pinnedCatalogs",Collections.emptyList()))for(CatalogRepository.Catalog cat:CatalogRepository.addons(store))if(cat.key.equals(key)){
            Button b=a.button(compact?"":cat.name,()->catalog(cat));b.setContentDescription(cat.name);b.setMaxLines(1);b.setCompoundDrawables(a.style.icon("Catalogue",MainActivity.MUTED),null,null,null);b.setCompoundDrawablePadding(a.dp(14));a.style.secondary(b);a.nav.addView(b,navParams(44));pins.add(b);pinNames.add(cat.name);
        }
        View gap=new View(a);a.nav.addView(gap,new LinearLayout.LayoutParams(1,a.dp(24)));
        Button toggle=a.button(compact?"":"Collapse",()->{});toggle.setTextSize(11);toggle.setTextColor(MainActivity.MUTED);toggle.setContentDescription("Toggle sidebar");toggle.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);toggle.setCompoundDrawablePadding(a.dp(14));toggle.setPadding(a.dp(12),0,0,0);toggle.setCompoundDrawables(a.style.icon(compact?"Expand":"Collapse",MainActivity.MUTED),null,null,null);a.style.secondary(toggle);a.nav.addView(toggle,navParams(40));
        toggle.setOnClickListener(v->{
            boolean value=!a.prefs.getBoolean("sidebarCompact",false);a.prefs.edit().putBoolean("sidebarCompact",value).apply();
            LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)side.getLayoutParams();lp.width=a.dp(value?76:168);side.setLayoutParams(lp);brand.setText(value?"k.":"kmay.");caption.setVisibility(value?View.GONE:View.VISIBLE);
            for(int i=0;i<buttons.size();i++)buttons.get(i).setText(value?"":names[i]);for(int i=0;i<pins.size();i++)pins.get(i).setText(value?"":pinNames.get(i));toggle.setText(value?"":"Collapse");toggle.setCompoundDrawables(a.style.icon(value?"Expand":"Collapse",MainActivity.MUTED),null,null,null);
        });
        ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        a.body=a.column();a.body.setPadding(a.dp(26),a.dp(26),a.dp(30),a.dp(36));scroll.addView(a.body);
        a.root.addView(scroll,new LinearLayout.LayoutParams(0,-1,1));a.setContentView(a.root);
    }
    LinearLayout.LayoutParams navParams(int height){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,a.dp(height));lp.setMargins(0,a.dp(3),0,a.dp(3));return lp;}
    TextView controlLabel(String text){TextView label=a.text(text,16,MainActivity.INK);label.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(a.dp(210),-2);lp.setMargins(0,0,a.dp(16),0);label.setLayoutParams(lp);return label;}
    LinearLayout controls(LinearLayout parent){
        HorizontalScrollView scroll=new HorizontalScrollView(a);scroll.setHorizontalScrollBarEnabled(false);scroll.setClipToPadding(false);
        LinearLayout row=a.row();row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,a.dp(4),0,a.dp(4));scroll.addView(row);parent.addView(scroll);return row;
    }
    void title(String title,String subtitle){a.heading(title,subtitle);}
    void home(){
        shell("home");
        if(!a.filter.isEmpty()){browse(a.filter.equals("movie")?CatalogRepository.films():CatalogRepository.series());return;}
        LinearLayout welcome=a.row();TextView heading=a.text("Home",21,MainActivity.INK);heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));welcome.addView(heading,new LinearLayout.LayoutParams(0,-2,1));TextView space=a.text("Katie's space",12,MainActivity.MUTED);welcome.addView(space);welcome.setPadding(0,0,0,a.dp(8));a.body.addView(welcome);
        hero=new FrameLayout(a);a.body.addView(hero,new LinearLayout.LayoutParams(-1,-2));
        List<JSONObject> saved=Net.objects(store.array("saved")),history=Net.objects(store.array("history"));
        JSONObject featured=!history.isEmpty()?history.get(0):!saved.isEmpty()?saved.get(0):a.openFilms().get(0);
        hero(hero,featured,false);
        List<String> hidden=store.strings("hiddenRows",Collections.emptyList());
        for(String row:store.strings("homeRows",HOME_ROWS)){
            if(hidden.contains(row))continue;
            switch(row){
                case "Continue watching":if(!history.isEmpty())rail(row,history,true);break;
                case "My list":if(!saved.isEmpty())rail(row,saved,false);break;
                case "Films":catalogRow(CatalogRepository.films(),"Films for tonight",featured.has("_direct"));break;
                case "Series":catalogRow(CatalogRepository.series(),"Your next series",false);break;
                case "Open films":rail("Open films",a.openFilms(),false);break;
                case "Add-on catalogues":for(CatalogRepository.Catalog c:CatalogRepository.addons(store))catalogRow(c,c.name,false);break;
            }
        }
        a.body.addView(a.button("Customize Home",this::customize));
    }
    void hero(FrameLayout frame,JSONObject item,boolean detail){
        frame.removeAllViews();frame.getLayoutParams().height=a.dp(detail?326:270);frame.setMinimumHeight(0);frame.setBackgroundColor(MainActivity.BG);
        ImageView art=new ImageView(a);art.setScaleType(ImageView.ScaleType.CENTER_CROP);frame.addView(art,new FrameLayout.LayoutParams(-1,-1));
        String artwork=item.optString("background","");if(artwork.isEmpty())artwork=item.optString("poster");
        Glide.with(a).load(artwork).placeholder(a.shape(MainActivity.PANEL,0)).error(a.shape(MainActivity.PANEL,0)).into(art);
        View shade=new View(a);shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{MainActivity.BG,0xe6101012,0x10101012}));frame.addView(shade,new FrameLayout.LayoutParams(-1,-1));
        View fade=new View(a);fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00101012,MainActivity.BG}));FrameLayout.LayoutParams bottom=new FrameLayout.LayoutParams(-1,a.dp(90),Gravity.BOTTOM);frame.addView(fade,bottom);
        LinearLayout copy=a.column();copy.setPadding(a.dp(6),a.dp(18),a.dp(10),a.dp(18));
        int available=(int)(a.getResources().getDisplayMetrics().widthPixels/a.getResources().getDisplayMetrics().density)-(a.prefs.getBoolean("sidebarCompact",false)?76:168)-56;
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(a.dp(Math.min(460,Math.max(290,(int)(available*.62f)))),-2,Gravity.CENTER_VERTICAL);frame.addView(copy,cp);
        TextView eyebrow=a.text(detail?(item.optString("type").equals("series")?"SERIES":"FILM"):"TONIGHT, ON KMAY",10,MainActivity.ACCENT);a.style.label(eyebrow);eyebrow.setTextColor(MainActivity.ACCENT);eyebrow.setPadding(0,0,0,a.dp(12));copy.addView(eyebrow);
        TextView name=a.text(item.optString("name","Untitled"),detail?37:item.optString("name").length()>28?30:35,MainActivity.INK);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));name.setLetterSpacing(-.035f);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);copy.addView(name);
        TextView meta=a.text(metadata(item),12,0xffd0cbd4);meta.setPadding(0,a.dp(8),0,a.dp(10));copy.addView(meta);
        String description=item.optString("description",detail?"Details are loading...":"Your next story is one play away.");
        TextView summary=a.text(description,13,MainActivity.MUTED);summary.setMaxLines(2);summary.setEllipsize(android.text.TextUtils.TruncateAt.END);summary.setLineSpacing(a.dp(3),1);copy.addView(summary,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout actions=controls(copy);actions.setPadding(0,a.dp(13),0,0);
        JSONObject resume=store.resume(item);Button play=a.button(resume!=null?"Resume":"Play",()->{if(detail)start(item,true,true);else playFeatured(item);});a.style.button(play,true);play.setCompoundDrawables(a.style.icon("Play",MainActivity.BG),null,null,null);play.setCompoundDrawablePadding(a.dp(10));actions.addView(play);
        if(!detail)actions.addView(a.button("View details",()->open(item)));
        Button save=a.button(a.isSaved(item)?"Saved":"+ My list",()->{});save.setOnClickListener(v->{a.toggleSaved(item);save.setText(a.isSaved(item)?"Saved":"+ My list");});a.style.secondary(save);actions.addView(save);
        if(detail){Button more=a.button("More options",()->detailActions(item));a.style.secondary(more);actions.addView(more);}
    }
    String metadata(JSONObject item){
        List<String> parts=new ArrayList<>();String year=item.optString("year",item.optString("releaseInfo"));if(!year.isEmpty())parts.add(year);
        if(!item.optString("runtime").isEmpty())parts.add(item.optString("runtime"));
        if(!item.optString("imdbRating").isEmpty())parts.add("★ "+item.optString("imdbRating"));
        return String.join("   ·   ",parts);
    }
    void open(JSONObject item){capture();a.searchOrigin=false;a.details(item);}
    void playFeatured(JSONObject item){
        capture();a.searchOrigin=false;
        if(item.has("_direct")){start(item,true,true);return;}
        int generation=a.generation;a.toast("Loading playback details…");
        a.jobs.execute(()->{try{JSONObject full=a.searchMetadata(item);a.runOnUiThread(()->{if(generation==a.generation)start(full,true,true);});}
            catch(Exception e){a.runOnUiThread(()->{if(generation==a.generation){a.toast(Net.message(e));open(item);}});}});
    }
    void catalogRow(CatalogRepository.Catalog cat,String heading,boolean feature){
        LinearLayout section=a.column();a.body.addView(section);section.addView(a.sectionTitle(heading));
        TextView status=a.text("Loading titles…",14,MainActivity.MUTED);section.addView(status);int generation=a.generation;
        load(cat,"",0,(items,error)->{
            if(generation!=a.generation)return;section.removeView(status);
            if(error!=null){section.addView(a.text(error,14,MainActivity.MUTED));section.addView(a.button("Retry "+cat.name,()->a.home()));return;}
            if(items.isEmpty()){section.addView(a.text("No titles available.",14,MainActivity.MUTED));return;}
            cards(section,items,false,false);
            // Only replace the initial fallback before the user enters the content area.
            if(feature&&hero!=null&&!hero.hasFocus()&&(a.getCurrentFocus()==null||a.nav.hasFocus()))hero(hero,items.get(0),false);
        });
    }
    interface Loaded {void done(List<JSONObject> items,String error);}
    void load(CatalogRepository.Catalog cat,String genre,int offset,Loaded result){
        String url=cat.url("",genre,offset);List<JSONObject> cached=cache.get(url);
        if(cached!=null){result.done(new ArrayList<>(cached),null);return;}
        a.jobs.execute(()->{try{List<JSONObject> items=CatalogRepository.load(cat,"",genre,offset);a.runOnUiThread(()->{cache.put(url,items);result.done(items,null);});}
            catch(Exception e){a.runOnUiThread(()->result.done(Collections.emptyList(),Net.message(e)));}});
    }
    void catalog(CatalogRepository.Catalog cat){origin=null;a.filter=cat.type;activeCatalog=cat;shell("catalog");browse(cat);}
    void browse(CatalogRepository.Catalog cat){
        activeCatalog=cat;title(cat.name,browse?"Browse your catalogue. Filters and sorting apply to loaded titles.":"Recommended by your catalogue.");
        LinearLayout tabs=controls(a.body);
        tabs.addView(a.button((browse?"":"✓ ")+"Recommended",()->{browse=false;catalog(cat);}));
        tabs.addView(a.button((browse?"✓ ":"")+"Browse",()->{browse=true;catalog(cat);}));
        if(!browse){catalogRow(cat,"Popular now",false);return;}
        LinearLayout filters=controls(a.body);
        filters.addView(a.button(a.genre.isEmpty()?"All genres":a.genre,()->choose("Genre",new String[]{"All genres","Action","Adventure","Animation","Comedy","Crime","Documentary","Drama","Family","Fantasy","Horror","Romance","Science Fiction","Thriller"},i->{a.genre=i.equals("All genres")?"":i;catalog(cat);})));
        filters.addView(a.button("Sort: "+sort,()->choose("Sort loaded titles",new String[]{"Recommended","Title","Year","Rating"},s->{sort=s;catalog(cat);})));
        filters.addView(a.button("Watched: "+watchFilter,()->choose("Watch status",new String[]{"All","Watched","Unwatched"},s->{watchFilter=s;catalog(cat);})));
        LinearLayout results=a.column();a.body.addView(results);
        TextView status=a.text("Loading titles…",14,MainActivity.MUTED);a.body.addView(status);
        Button more=a.button("Load more",()->{});a.body.addView(more);more.setVisibility(View.GONE);
        List<JSONObject> loaded=new ArrayList<>();int[] offset={0};int generation=a.generation;
        Runnable fetch=new Runnable(){public void run(){more.setEnabled(false);status.setText("Loading titles…");
            load(cat,a.genre,offset[0],(items,error)->{if(generation!=a.generation)return;
                if(error!=null){status.setText(error);more.setText("Retry");more.setVisibility(View.VISIBLE);more.setEnabled(true);return;}
                Set<String> ids=new HashSet<>();for(JSONObject m:loaded)ids.add(LibraryStore.key(m));int before=loaded.size();
                for(JSONObject m:items)if(ids.add(LibraryStore.key(m)))loaded.add(m);offset[0]+=items.size();
                List<JSONObject> shown=filtered(loaded);results.removeAllViews();cards(results,shown,false,true);status.setText(shown.size()+" shown · "+loaded.size()+" loaded");
                more.setText("Load more");more.setEnabled(true);more.setVisibility(cat.skip&&items.size()>=100&&loaded.size()>before?View.VISIBLE:View.GONE);
            });}};
        more.setOnClickListener(v->fetch.run());fetch.run();
    }
    List<JSONObject> filtered(List<JSONObject> input){
        List<JSONObject> result=new ArrayList<>();for(JSONObject m:input){
            boolean watched=store.watched(m.optString("id"));
            if(watchFilter.equals("Watched")&&!watched||watchFilter.equals("Unwatched")&&watched)continue;
            if(activeCatalog!=null&&!activeCatalog.base.equals(Net.CATALOG)&&!a.genre.isEmpty()&&!m.optString("genres").toLowerCase(Locale.ROOT).contains(a.genre.toLowerCase(Locale.ROOT)))continue;
            result.add(m);
        }
        if(sort.equals("Title"))result.sort(Comparator.comparing(m->m.optString("name").toLowerCase(Locale.ROOT)));
        if(sort.equals("Year"))result.sort(Comparator.comparing((JSONObject m)->m.optString("year",m.optString("releaseInfo"))).reversed());
        if(sort.equals("Rating"))result.sort(Comparator.comparingDouble((JSONObject m)->m.optDouble("imdbRating",0)).reversed());
        return result;
    }
    void rail(String name,List<JSONObject> items,boolean resume){LinearLayout section=a.column();section.setPadding(0,0,0,0);a.body.addView(section);section.addView(a.sectionTitle(name));cards(section,items,resume,false);if(name.equals("My list")&&!items.isEmpty())((Posters)rails.get(rails.size()-1).getAdapter()).localKey="saved";}
    void cards(LinearLayout parent,List<JSONObject> items,boolean resume,boolean grid){
        if(items.isEmpty()){parent.addView(a.text("Nothing here yet.",15,MainActivity.MUTED));return;}
        RecyclerView list=new RecyclerView(a);list.setItemAnimator(null);list.setNestedScrollingEnabled(false);list.setClipToPadding(false);list.setPadding(a.dp(3),a.dp(8),a.dp(3),a.dp(8));
        int columns=Math.max(3,(int)((a.getResources().getDisplayMetrics().widthPixels/a.getResources().getDisplayMetrics().density-230)/138));
        list.setLayoutManager(grid?new GridLayoutManager(a,columns):new LinearLayoutManager(a,RecyclerView.HORIZONTAL,false));
        list.setAdapter(new Posters(items,resume,grid));rails.add(list);
        parent.addView(list,new LinearLayout.LayoutParams(-1,grid?-2:a.dp(resume?232:256)));
    }
    final class Posters extends RecyclerView.Adapter<Poster> {
        final List<JSONObject> items;final boolean resume,grid;String localKey;
        Posters(List<JSONObject> items,boolean resume,boolean grid){this.items=new ArrayList<>(items);this.resume=resume;this.grid=grid;localKey=resume?"history":a.page.equals("library")?(collection.isEmpty()?"saved":"collection:"+collection):"";}
        public Poster onCreateViewHolder(ViewGroup parent,int type){
            LinearLayout card=a.column();card.setPadding(a.dp(4),a.dp(4),a.dp(4),a.dp(4));card.setFocusable(true);card.setClickable(true);card.setBackground(a.focusBg());
            RecyclerView.LayoutParams lp=new RecyclerView.LayoutParams(grid?-1:a.dp(resume?248:138),a.dp(resume?211:236));lp.setMargins(a.dp(3),0,a.dp(9),a.dp(5));card.setLayoutParams(lp);
            ImageView image=new ImageView(a);image.setScaleType(ImageView.ScaleType.CENTER_CROP);card.addView(image,new LinearLayout.LayoutParams(-1,a.dp(resume?132:164)));image.setBackground(a.style.surface(MainActivity.PANEL,0,4));image.setClipToOutline(true);
            TextView title=a.text("",13,MainActivity.INK);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));title.setMinLines(2);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(title);
            TextView info=a.text("",10,MainActivity.MUTED);info.setMaxLines(1);info.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(info);
            ProgressBar progress=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);progress.setProgressTintList(ColorStateList.valueOf(MainActivity.ACCENT));card.addView(progress,new LinearLayout.LayoutParams(-1,a.dp(3)));
            return new Poster(card,image,title,info,progress);
        }
        public void onBindViewHolder(Poster h,int position){
            JSONObject m=items.get(position);String id=resume?m.optString("_videoId",m.optString("id")):m.optString("id");
            h.title.setText(m.optString("name","Untitled"));h.info.setText((store.watched(id)?"✓ Watched · ":"")+(resume?m.optString("_videoLabel","Resume"):m.optString("year",m.optString("releaseInfo"))+(m.optString("imdbRating").isEmpty()?"":" · ★ "+m.optString("imdbRating"))));
            Glide.with(a).load((resume?m.optString("background",m.optString("poster")):m.optString("poster"))).placeholder(a.shape(0xff242228,0)).error(a.style.surface(0xff242228,0,4)).into(h.image);
            long duration=a.prefs.getLong("duration:"+id,0),positionMs=a.prefs.getLong("position:"+id,0);h.progress.setVisibility(duration>0&&positionMs>0?View.VISIBLE:View.INVISIBLE);h.progress.setProgress(duration>0?(int)(100*positionMs/duration):0);
            h.itemView.setContentDescription(m.optString("name"));h.itemView.setOnClickListener(v->{if(resume){capture();start(m,true,true);}else open(m);});
            h.itemView.setOnLongClickListener(v->{itemMenu(m,resume);return true;});
            h.itemView.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_MENU&&event.getAction()==KeyEvent.ACTION_DOWN){itemMenu(m,resume);return true;}return false;});
        }
        public void onViewRecycled(Poster h){Glide.with(a).clear(h.image);}
        public int getItemCount(){return items.size();}
    }
    static final class Poster extends RecyclerView.ViewHolder {
        final ImageView image;final TextView title,info;final ProgressBar progress;
        Poster(View v,ImageView image,TextView title,TextView info,ProgressBar progress){super(v);this.image=image;this.title=title;this.info=info;this.progress=progress;}
    }
    void start(JSONObject m,boolean resume,boolean auto){
        a.selected=m;JSONObject history=resume?store.resume(m):null;
        if(history!=null){a.videoId=history.optString("_videoId",m.optString("id"));a.videoLabel=history.optString("_videoLabel",m.optString("name"));}
        else if(m.optString("type").equals("series")){
            List<JSONObject> episodes=Net.objects(m.optJSONArray("videos"));episodes.sort(Comparator.comparingInt((JSONObject e)->e.optInt("season",1)).thenComparingInt(e->e.optInt("episode",1)));
            JSONObject first=null;for(JSONObject ep:episodes)if(ep.optInt("season",1)>0&&!store.watched(LibraryStore.episodeId(m,ep))){first=ep;break;}
            if(first==null&&!episodes.isEmpty())first=episodes.get(0);
            if(first==null){a.manualEpisode(m);return;}a.videoId=LibraryStore.episodeId(m,first);a.videoLabel=episodeLabel(first);
        }else{a.videoId=m.optString("id");a.videoLabel=m.optString("name");}
        if(!resume)a.prefs.edit().putLong("position:"+a.videoId,0).apply();a.sources(auto);
    }
    String episodeLabel(JSONObject ep){return "S"+ep.optInt("season",1)+" · E"+ep.optInt("episode",1)+"   "+ep.optString("title",ep.optString("name","Episode"));}
    void details(JSONObject m,boolean loading){
        String focus=a.page.equals("details")&&a.getCurrentFocus() instanceof TextView?((TextView)a.getCurrentFocus()).getText().toString():"";
        shell("details");
        FrameLayout banner=new FrameLayout(a);a.body.addView(banner,new LinearLayout.LayoutParams(-1,-2));hero(banner,m,true);
        LinearLayout actions=controls(a.body);
        Button synopsis=a.button("Read synopsis",()->new AlertDialog.Builder(a).setTitle(m.optString("name")).setMessage(m.optString("description","No synopsis available.")).setPositiveButton("Close",null).show());a.style.secondary(synopsis);actions.addView(synopsis);
        JSONArray genres=m.optJSONArray("genres");if(genres!=null)a.body.addView(a.text(join(genres),13,MainActivity.ACCENT));
        JSONArray cast=m.optJSONArray("cast");if(cast!=null&&cast.length()>0){a.body.addView(a.sectionTitle("Cast"));TextView names=a.text(join(cast),14,MainActivity.MUTED);names.setMaxLines(3);a.body.addView(names);}
        if(m.optString("type").equals("series")){
            List<JSONObject> episodes=Net.objects(m.optJSONArray("videos"));
            if(episodes.isEmpty()){
                a.body.addView(a.text(loading?"Loading episodes…":"No episode listing available.",15,MainActivity.MUTED));
                if(!loading)a.body.addView(a.button("Enter season / episode",()->a.manualEpisode(m)));
            }else{
                TreeMap<Integer,List<JSONObject>> groups=new TreeMap<>();for(JSONObject ep:episodes)groups.computeIfAbsent(ep.optInt("season",1),k->new ArrayList<>()).add(ep);
                int initial=groups.containsKey(1)?1:groups.firstKey();JSONObject resume=store.resume(m);
                if(resume!=null)for(JSONObject ep:episodes)if(LibraryStore.episodeId(m,ep).equals(resume.optString("_videoId")))initial=ep.optInt("season",1);
                int season=seasons.getOrDefault(LibraryStore.key(m),initial);if(!groups.containsKey(season))season=initial;
                LinearLayout tabs=controls(a.body),list=a.column();a.body.addView(list);
                for(Integer s:groups.keySet())tabs.addView(a.button((s==season?"✓ ":"")+(s==0?"Specials":"Season "+s),()->{
                    seasons.put(LibraryStore.key(m),s);episodes(list,groups.get(s),m);
                    String selectedLabel=s==0?"Specials":"Season "+s;
                    for(int i=0;i<tabs.getChildCount();i++){
                        Button b=(Button)tabs.getChildAt(i);
                        String label=b.getText().toString().replace("✓ ","");
                        b.setText((label.equals(selectedLabel)?"✓ ":"")+label);
                    }
                }));
                episodes(list,groups.get(season),m);
            }
        }
        if(loading&&!m.has("_direct")){
            // Keep metadata refresh from accidentally starting an incomplete series or provider ID.
            for(int i=0;i<actions.getChildCount();i++)actions.getChildAt(i).setEnabled(false);
            enableButtons(banner,false);
        }
        if(!focus.isEmpty())restoreTextFocus(a.root,focus);
    }
    void detailActions(JSONObject m){
        List<String> actions=new ArrayList<>(Arrays.asList("Play from beginning","Choose source","Add to collection",store.watched(m.optString("id"))?"Mark unwatched":"Mark watched"));
        if(store.resume(m)!=null)actions.add("Remove from Continue watching");
        choose(m.optString("name"),actions.toArray(new String[0]),choice->{
            switch(choice){
                case "Play from beginning":start(m,false,true);break;
                case "Choose source":start(m,true,false);break;
                case "Add to collection":collectionsFor(m);break;
                case "Mark watched":mark(m,true);details(m,false);break;
                case "Mark unwatched":mark(m,false);details(m,false);break;
                case "Remove from Continue watching":store.removeHistory(m);details(m,false);break;
            }
        });
    }
    boolean restoreTextFocus(View view,String label){
        if(view instanceof TextView&&view.isFocusable()&&((TextView)view).getText().toString().equals(label)){view.requestFocus();return true;}
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(restoreTextFocus(((ViewGroup)view).getChildAt(i),label))return true;
        return false;
    }
    void enableButtons(View view,boolean enabled){if(view instanceof Button)view.setEnabled(enabled);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)enableButtons(((ViewGroup)view).getChildAt(i),enabled);}
    String join(JSONArray array){List<String> values=new ArrayList<>();for(int i=0;i<array.length();i++)values.add(array.optString(i));return String.join(" · ",values);}
    void episodes(LinearLayout list,List<JSONObject> episodes,JSONObject m){
        list.removeAllViews();episodes.sort(Comparator.comparingInt(e->e.optInt("episode",1)));
        for(JSONObject ep:episodes){
            String id=LibraryStore.episodeId(m,ep),label=episodeLabel(ep);LinearLayout card=a.row();card.setFocusable(true);card.setClickable(true);card.setBackground(a.focusBg());card.setPadding(a.dp(8),a.dp(8),a.dp(12),a.dp(8));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,a.dp(6),0,0);card.setMinimumHeight(a.dp(120));list.addView(card,lp);
            ImageView image=new ImageView(a);image.setScaleType(ImageView.ScaleType.CENTER_CROP);card.addView(image,new LinearLayout.LayoutParams(a.dp(144),a.dp(88)));Glide.with(a).load(ep.optString("thumbnail",m.optString("background"))).error(a.style.surface(0xff242228,0,4)).into(image);
            LinearLayout copy=a.column();copy.setPadding(a.dp(14),0,0,0);card.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
            TextView title=a.text((store.watched(id)?"✓ ":"")+label,15,MainActivity.INK);title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);copy.addView(title);
            TextView description=a.text(ep.optString("overview",ep.optString("description","")),12,MainActivity.MUTED);description.setMaxLines(2);copy.addView(description);
            long position=a.prefs.getLong("position:"+id,0),duration=a.prefs.getLong("duration:"+id,0);
            if(position>0)copy.addView(a.text("Resume at "+position/60000+" min"+(duration>0?" / "+duration/60000+" min":""),11,MainActivity.ACCENT));
            card.setContentDescription(label);card.setOnClickListener(v->{a.selected=m;a.videoId=id;a.videoLabel=label;a.sources(true);});
            Runnable menu=()->choose(label,new String[]{"Play","Play from beginning",store.watched(id)?"Mark unwatched":"Mark watched","Choose source"},choice->{
                if(choice.startsWith("Mark")){store.watched(id,!store.watched(id));episodes(list,episodes,m);return;}
                a.selected=m;a.videoId=id;a.videoLabel=label;if(choice.equals("Play from beginning"))a.prefs.edit().putLong("position:"+id,0).apply();a.sources(!choice.equals("Choose source"));
            });
            card.setOnLongClickListener(v->{menu.run();return true;});card.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_MENU&&event.getAction()==0){menu.run();return true;}return false;});
        }
    }
    void mark(JSONObject m,boolean watched){
        store.watched(m.optString("id"),watched);for(JSONObject ep:Net.objects(m.optJSONArray("videos")))store.watched(LibraryStore.episodeId(m,ep),watched);store.removeHistory(m);
    }
    void itemMenu(JSONObject m,boolean resume){
        List<String> actions=new ArrayList<>(Arrays.asList("View details",a.isSaved(m)?"Remove from My list":"Add to My list","Add to collection",store.watched(m.optString("id"))?"Mark unwatched":"Mark watched"));
        if(resume)actions.add("Remove from Continue watching");if(!collection.isEmpty()&&a.page.equals("library"))actions.add("Remove from collection");
        choose(m.optString("name"),actions.toArray(new String[0]),action->{
            switch(action){
                case "View details":open(m);return;
                case "Add to collection":collectionsFor(m);return;
                case "Remove from Continue watching":store.removeHistory(m);break;
                case "Remove from collection":store.toggle("collection:"+collection,m);break;
                case "Mark watched":mark(m,true);break;
                case "Mark unwatched":mark(m,false);break;
                default:a.toggleSaved(m);
            }
            if(a.page.equals("library"))library();else a.home();
        });
    }
    void library(){
        shell("library");title("My list","Good stories, saved for later.");
        LinearLayout tabs=controls(a.body);tabs.addView(a.button(collection.isEmpty()?"✓ Watchlist":"Watchlist",()->{collection="";library();}));
        List<String> names=store.strings("collections",Collections.emptyList());if(!names.contains(collection))collection="";
        for(String name:names)tabs.addView(a.button((collection.equals(name)?"✓ ":"")+name,()->{collection=name;library();}));
        tabs.addView(a.button("+ New collection",()->nameDialog("New collection","",name->{if(names.contains(name)){a.toast("That collection already exists.");return;}names.add(name);store.putStrings("collections",names);collection=name;library();})));
        if(!collection.isEmpty()){
            LinearLayout edit=controls(a.body);edit.addView(a.button("Rename collection",()->{String previous=collection;nameDialog("Rename collection",previous,name->{
                if(names.contains(name)&&!name.equals(previous)){a.toast("That collection already exists.");return;}
                if(!name.equals(previous)){names.set(names.indexOf(previous),name);store.put("collection:"+name,store.array("collection:"+previous));a.prefs.edit().remove("collection:"+previous).apply();store.putStrings("collections",names);collection=name;}library();
            });}));
            edit.addView(a.button("Delete collection",()->confirm("Delete "+collection+"?","The titles remain in your catalogue and watchlist.",()->{names.remove(collection);a.prefs.edit().remove("collection:"+collection).apply();store.putStrings("collections",names);collection="";library();})));
        }
        List<JSONObject> items=Net.objects(store.array(collection.isEmpty()?"saved":"collection:"+collection));
        if(items.isEmpty())a.body.addView(a.text("Open a title to save it or add it to a collection.",16,MainActivity.MUTED));else cards(a.body,items,false,true);
        List<JSONObject> history=Net.objects(store.array("history"));if(!history.isEmpty())rail("Continue watching",history,true);
    }
    void collectionsFor(JSONObject item){
        List<String> names=store.strings("collections",Collections.emptyList());List<String> choices=new ArrayList<>();
        for(String name:names)choices.add((store.contains("collection:"+name,item)?"✓ ":"+ ")+name);choices.add("New collection");
        new AlertDialog.Builder(a).setTitle("Collections").setItems(choices.toArray(new String[0]),(d,index)->{
            if(index<names.size()){String name=names.get(index);store.toggle("collection:"+name,item);a.toast(store.contains("collection:"+name,item)?"Added to "+name:"Removed from "+name);}
            else nameDialog("New collection","",name->{if(!names.contains(name)){names.add(name);store.putStrings("collections",names);}if(!store.contains("collection:"+name,item))store.toggle("collection:"+name,item);a.toast("Added to "+name);});
        }).setNegativeButton("Close",null).show();
    }
    interface Choice {void accept(String value);}
    void choose(String title,String[] options,Choice action){new AlertDialog.Builder(a).setTitle(title).setItems(options,(d,i)->action.accept(options[i])).setNegativeButton("Cancel",null).show();}
    void nameDialog(String title,String initial,Choice action){
        EditText input=new EditText(a);input.setSingleLine();input.setText(initial);input.setSelectAllOnFocus(true);
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle(title).setView(a.dialogForm(input)).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();dialog.show();
        dialog.getButton(-1).setOnClickListener(v->{String value=input.getText().toString().trim();if(value.isEmpty()||value.length()>48){input.setError("Use 1 to 48 characters.");return;}dialog.dismiss();action.accept(value);});
    }
    void confirm(String title,String message,Runnable action){new AlertDialog.Builder(a).setTitle(title).setMessage(message).setPositiveButton("Confirm",(d,w)->action.run()).setNegativeButton("Cancel",null).show();}
    void customize(){
        shell("customize");title("Your Home","Choose what appears, and in what order.");
        List<String> order=store.strings("homeRows",HOME_ROWS),hidden=store.strings("hiddenRows",Collections.emptyList());
        for(String name:order){LinearLayout line=controls(a.body);line.addView(controlLabel(name));
            line.addView(a.button(hidden.contains(name)?"Show":"Hide",()->{if(!hidden.remove(name))hidden.add(name);store.putStrings("hiddenRows",hidden);customize();}));
            line.addView(a.button("Move up",()->{int i=order.indexOf(name);if(i>0){Collections.swap(order,i,i-1);store.putStrings("homeRows",order);customize();}}));
            line.addView(a.button("Move down",()->{int i=order.indexOf(name);if(i<order.size()-1){Collections.swap(order,i,i+1);store.putStrings("homeRows",order);customize();}}));
        }
        a.body.addView(a.sectionTitle("Sidebar catalogues"));List<String> pins=store.strings("pinnedCatalogs",Collections.emptyList());
        List<CatalogRepository.Catalog> cats=CatalogRepository.addons(store);cats.sort(Comparator.comparingInt(c->pins.contains(c.key)?pins.indexOf(c.key):Integer.MAX_VALUE));
        if(cats.isEmpty())a.body.addView(a.text("Add a catalogue in Sources to pin it here.",14,MainActivity.MUTED));
        for(CatalogRepository.Catalog cat:cats){LinearLayout line=controls(a.body);line.addView(controlLabel(cat.name));
            line.addView(a.button(pins.contains(cat.key)?"Unpin":"Pin",()->{if(!pins.remove(cat.key))pins.add(cat.key);store.putStrings("pinnedCatalogs",pins);customize();}));
            if(pins.contains(cat.key))line.addView(a.button("Move up",()->{int i=pins.indexOf(cat.key);if(i>0){Collections.swap(pins,i,i-1);store.putStrings("pinnedCatalogs",pins);customize();}}));
        }
    }
    void settings(){
        shell("settings");title("Settings","Your space. Your preferences.");
        LinearLayout columns=a.row();columns.setGravity(Gravity.TOP);columns.setPadding(0,a.dp(18),0,0);a.body.addView(columns);
        LinearLayout left=a.column(),right=a.column();LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(0,0,a.dp(28),0);columns.addView(left,lp);columns.addView(right,new LinearLayout.LayoutParams(0,-2,1));
        settingHeading(left,"EXPERIENCE");
        setting(left,"Customize Home and sidebar",this::customize);
        setting(left,"Autoplay next episode: "+(a.prefs.getBoolean("autoplayNext",true)?"on":"off"),()->{a.prefs.edit().putBoolean("autoplayNext",!a.prefs.getBoolean("autoplayNext",true)).apply();settings();});
        setting(left,"Hosted players: "+(a.prefs.getBoolean("webSources",true)?"on":"off"),()->{a.prefs.edit().putBoolean("webSources",!a.prefs.getBoolean("webSources",true)).apply();settings();});
        TextView hint=a.text("Select a title to play. Hold Select or press Menu for more actions.",12,MainActivity.MUTED);hint.setPadding(0,a.dp(16),a.dp(12),0);left.addView(hint);
        settingHeading(right,"CONNECTIONS");setting(right,"Manage sources",this::sourceSettings);
        settingHeading(right,"ON THIS DEVICE");
        setting(right,"Clear viewing progress",()->confirm("Clear viewing progress?","This resets resume positions and watched status. Saved titles and collections remain.",()->{store.clearProgress();settings();}));
        setting(right,"Clear search history",()->confirm("Clear search history?","Remove recent searches from this device.",()->{a.prefs.edit().remove("searchHistory").apply();a.toast("Search history cleared");}));
        TextView about=a.text("KMay 2.1  /  Made for Katie May",11,MainActivity.MUTED);about.setPadding(0,a.dp(36),0,0);a.body.addView(about);
    }
    void settingHeading(LinearLayout parent,String title){TextView label=a.text(title,10,MainActivity.MUTED);a.style.label(label);label.setPadding(0,a.dp(14),0,a.dp(13));parent.addView(label);}
    void setting(LinearLayout parent,String label,Runnable action){Button button=a.button(label,action);button.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);button.setMaxLines(2);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,a.dp(54));lp.setMargins(0,0,0,a.dp(8));parent.addView(button,lp);}
    void sourceSettings(){
        shell("sourceSettings");title("Sources","Connect your catalogues and playback services.");
        ProxySettings.add(a);
        LinearLayout sources=controls(a.body);sources.addView(a.button("Add a source",a::addAddon));sources.addView(a.button("Play a direct URL",a::directUrl));
        for(JSONObject addon:Net.objects(store.array("addons"))){JSONObject manifest=addon.optJSONObject("manifest");if(manifest==null)continue;
            LinearLayout line=controls(a.body);line.addView(controlLabel(manifest.optString("name")));line.addView(a.button("Remove",()->confirm("Remove source?",manifest.optString("name"),()->{
                JSONArray out=new JSONArray();for(JSONObject old:Net.objects(store.array("addons")))if(!old.optString("base").equals(addon.optString("base")))out.put(old);store.put("addons",out);sourceSettings();
            })));
        }
    }
}
