package uk.kmay.media;

import android.app.Activity;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.speech.RecognizerIntent;
import android.text.*;
import android.util.Log;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import org.json.*;
import java.util.*;

/** Retains its view and focus while a title or player is open. */
final class SearchScreen implements AutoCloseable {
    static final int VOICE_REQUEST=701;
    final MainActivity app;
    final Handler handler=new Handler(Looper.getMainLooper());
    final SearchRepository repository=new SearchRepository(command->handler.post(command));
    final LinkedHashMap<String,SearchModel.Result> results=new LinkedHashMap<>();
    final Map<String,Integer> nextPages=new LinkedHashMap<>(), failedPages=new LinkedHashMap<>(), inFlight=new LinkedHashMap<>();
    final List<SearchModel.Provider> providers=new ArrayList<>();
    final List<SearchModel.Result> displayed=new ArrayList<>();
    LinearLayout root,recent;
    EditText input;
    TextView status;
    Button retry,more,historyToggle;
    final List<Button> filters=new ArrayList<>();
    RecyclerView grid;
    Cards adapter;
    SearchModel.Request request=new SearchModel.Request("","");
    String type="",focusedKey="";
    int unsupported,pending;
    boolean visible,changing;
    Runnable debounce;
    View lastFocus;

    SearchScreen(MainActivity app){this.app=app;}
    void show(){
        if(root==null)build();
        else {app.generation++;app.page="search";app.root=root;app.setContentView(root);}
        visible=true;
        String config=app.arrayPref("addons").toString();
        if(!repository.configuration.equals(config)||!input.getText().toString().trim().equals(request.query)||!type.equals(request.type)){
            repository.configure(config);
            run(false);
        }else if(!failedPages.isEmpty())retry();
        renderHistory();
        if(lastFocus!=null&&lastFocus.isAttachedToWindow())lastFocus.requestFocus();
        else if(!focusedKey.isEmpty())restoreCard();
        else input.requestFocus();
    }
    void build(){
        app.shell("search");root=app.root;root.removeViewAt(root.getChildCount()-1);
        LinearLayout panel=app.column();panel.setPadding(app.dp(26),app.dp(20),app.dp(26),app.dp(20));root.addView(panel,new LinearLayout.LayoutParams(0,-1,1));
        TextView title=app.text("Search",28,MainActivity.INK);panel.addView(title);
        LinearLayout entry=app.row();entry.setGravity(Gravity.CENTER_VERTICAL);entry.setPadding(0,app.dp(8),0,app.dp(4));panel.addView(entry);
        input=new EditText(app);input.setId(View.generateViewId());input.setSingleLine(true);input.setTextColor(MainActivity.INK);input.setHintTextColor(MainActivity.MUTED);input.setHint("Search films and series");input.setTextSize(18);input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);input.setBackground(app.style.background(TvStyle.SURFACE,false));input.setPadding(app.dp(14),0,app.dp(14),0);
        LinearLayout.LayoutParams field=new LinearLayout.LayoutParams(0,app.dp(48),1);field.setMargins(0,0,app.dp(10),0);entry.addView(input,field);
        entry.addView(app.button("Search",()->submit()));
        entry.addView(app.button("Clear",()->{input.setText("");input.requestFocus();}));
        Intent speech=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        if(speech.resolveActivity(app.getPackageManager())!=null)entry.addView(app.button("Voice",this::voice));
        LinearLayout tabs=app.row();panel.addView(tabs);
        String[] names={"All","Films","Series"},types={"","movie","series"};
        for(int i=0;i<names.length;i++){
            final String choice=types[i];Button button=app.button(names[i],()->{type=choice;updateFilters();run(false);});
            app.style.secondary(button);button.setTag(names[i]);filters.add(button);tabs.addView(button);
        }
        updateFilters();
        recent=app.column();panel.addView(recent);
        status=app.text("Search your catalogues, saved titles and viewing history.",14,MainActivity.MUTED);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);panel.addView(status);
        grid=new RecyclerView(app);grid.setId(View.generateViewId());grid.setLayoutManager(new GridLayoutManager(app,Math.max(3,(int)((app.getResources().getDisplayMetrics().widthPixels/app.getResources().getDisplayMetrics().density-230)/138))));grid.setItemAnimator(null);grid.setClipToPadding(false);grid.setPadding(app.dp(3),app.dp(5),app.dp(3),app.dp(5));adapter=new Cards();grid.setAdapter(adapter);panel.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout controls=app.row();panel.addView(controls);
        retry=app.button("Retry failed",this::retry);more=app.button("Load more",this::more);controls.addView(retry);controls.addView(more);retry.setVisibility(View.GONE);more.setVisibility(View.GONE);
        input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){
            if(changing)return;
            if(debounce!=null)handler.removeCallbacks(debounce);
            repository.cancel();inFlight.clear();pending=0;
            debounce=()->run(false);handler.postDelayed(debounce,350);
        }public void afterTextChanged(Editable e){}});
        input.setOnEditorActionListener((v,action,event)->{
            if(action==EditorInfo.IME_ACTION_SEARCH||(event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_UP)){submit();return true;}return false;
        });
    }
    void updateFilters(){for(int i=0;i<filters.size();i++){Button b=filters.get(i);boolean selected=type.equals(new String[]{"","movie","series"}[i]);b.setText((selected?"✓ ":"")+b.getTag());b.setContentDescription(b.getTag()+(selected?", selected":""));}}
    void submit(){
        hideKeyboard();run(true);remember();
        if(!displayed.isEmpty())grid.post(()->{RecyclerView.ViewHolder holder=grid.findViewHolderForAdapterPosition(0);if(holder!=null)holder.itemView.requestFocus();});
    }
    void hideKeyboard(){((InputMethodManager)app.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);}
    void run(boolean explicit){
        if(debounce!=null)handler.removeCallbacks(debounce);
        repository.cancel();repository.configure(app.arrayPref("addons").toString());
        results.clear();displayed.clear();nextPages.clear();failedPages.clear();inFlight.clear();pending=0;focusedKey="";
        request=new SearchModel.Request(input.getText().toString(),type);
        long start=System.nanoTime();
        SearchModel.Providers selected=SearchModel.providers(app.arrayPref("addons"),type);providers.clear();providers.addAll(selected.available);unsupported=selected.unsupported;
        if(request.query.isEmpty()){
            adapter.notifyDataSetChanged();status.setText("Search your catalogues, saved titles and viewing history.");retry.setVisibility(View.GONE);more.setVisibility(View.GONE);renderHistory();return;
        }
        for(String key:new String[]{"saved","history"})for(JSONObject item:Net.objects(app.arrayPref(key)))
            if(request.accepts(item)&&SearchModel.rank(item.optString("name"),request.query,true)<4)add(item,true);
        renderHistory();render();
        Log.i("KMaySearch","local_ms="+((System.nanoTime()-start)/1_000_000)+" count="+results.size());
        if(!explicit&&request.query.codePointCount(0,request.query.length())<2){status.setText("Type another character, or select Search.");return;}
        for(SearchModel.Provider provider:providers)fetch(provider,0);
        renderStatus();
    }
    void add(JSONObject item,boolean local){add(item,local,Integer.MAX_VALUE,Integer.MAX_VALUE);}
    void add(JSONObject item,boolean local,int rank,int order){SearchModel.Result result=new SearchModel.Result(item,local);result.providerRank=rank;result.providerOrder=order;SearchModel.Result existing=results.get(result.key);if(existing==null)results.put(result.key,result);else existing.merge(result);}
    void fetch(SearchModel.Provider provider,int offset){
        pending++;inFlight.put(provider.key(),offset);
        repository.fetch(provider,request.query,offset,(p,page,items,failed,millis)->{
            if(!visible)return;
            pending--;inFlight.remove(p.key());
            if(failed)failedPages.put(p.key(),page);
            else{
                failedPages.remove(p.key());int rank=page;for(JSONObject item:items){if(request.accepts(item))add(item,false,rank,providers.indexOf(p));rank++;}
                int next=p.next(page);if(items.size()>=100&&next>=0)nextPages.put(p.key(),next);else nextPages.remove(p.key());
            }
            Log.i("KMaySearch","provider_ms="+millis+" failed="+failed+" count="+items.size());render();
        });
    }
    void retry(){Map<String,Integer> failures=new LinkedHashMap<>(failedPages);failedPages.clear();for(SearchModel.Provider p:providers)if(failures.containsKey(p.key()))fetch(p,failures.get(p.key()));renderStatus();}
    void more(){Map<String,Integer> pages=new LinkedHashMap<>(nextPages);nextPages.clear();for(SearchModel.Provider p:providers)if(pages.containsKey(p.key()))fetch(p,pages.get(p.key()));renderStatus();}
    void render(){
        boolean preserve=grid.hasFocus();
        if(preserve){Set<String> shown=new HashSet<>();for(SearchModel.Result r:displayed)shown.add(r.key);int old=displayed.size();for(SearchModel.Result r:SearchModel.ranked(results.values(),request))if(shown.add(r.key))displayed.add(r);if(displayed.size()>old)adapter.notifyItemRangeInserted(old,displayed.size()-old);}
        else{displayed.clear();displayed.addAll(SearchModel.ranked(results.values(),request));adapter.notifyDataSetChanged();}
        renderStatus();
    }
    void renderStatus(){
        String message=results.size()+" titles";
        if(pending>0)message+=" · Searching "+pending+" catalogues…";
        else if(results.isEmpty())message=failedPages.isEmpty()?"No matches. Try a different title or filter.":"Catalogues unavailable. Check your connection or retry.";
        if(!failedPages.isEmpty()&&!results.isEmpty())message+=" · Some catalogues unavailable";
        if(unsupported>0)message+=" · "+unsupported+" catalogues do not support this search";
        status.setText(message);
        boolean retryFocused=retry.hasFocus(),moreFocused=more.hasFocus();
        retry.setVisibility(failedPages.isEmpty()?View.GONE:View.VISIBLE);retry.setEnabled(pending==0);
        more.setVisibility(nextPages.isEmpty()?View.GONE:View.VISIBLE);more.setEnabled(pending==0);
        if((retryFocused&&retry.getVisibility()!=View.VISIBLE)||(moreFocused&&more.getVisibility()!=View.VISIBLE))input.requestFocus();
    }
    void remember(){
        String query=input.getText().toString().trim();if(query.isEmpty()||!app.prefs.getBoolean("searchHistoryEnabled",true))return;
        JSONArray updated=new JSONArray();updated.put(query);JSONArray old=app.arrayPref("searchHistory");
        for(int i=0;i<old.length()&&updated.length()<20;i++)if(!SearchModel.normalize(old.optString(i)).equals(SearchModel.normalize(query)))updated.put(old.optString(i));
        app.prefs.edit().putString("searchHistory",updated.toString()).apply();renderHistory();
    }
    void renderHistory(){
        recent.removeAllViews();recent.setVisibility(input.getText().toString().trim().isEmpty()?View.VISIBLE:View.GONE);
        if(recent.getVisibility()!=View.VISIBLE)return;
        boolean enabled=app.prefs.getBoolean("searchHistoryEnabled",true);
        LinearLayout controls=app.row();recent.addView(controls);
        historyToggle=app.button(enabled?"Search history: on":"Search history: off",()->{app.prefs.edit().putBoolean("searchHistoryEnabled",!app.prefs.getBoolean("searchHistoryEnabled",true)).remove("searchHistory").apply();renderHistory();historyToggle.requestFocus();});controls.addView(historyToggle);
        controls.addView(app.button("Clear history",()->{app.prefs.edit().remove("searchHistory").apply();renderHistory();historyToggle.requestFocus();}));
        JSONArray history=app.arrayPref("searchHistory");
        if(enabled&&history.length()>0){HorizontalScrollView scroll=new HorizontalScrollView(app);LinearLayout items=app.row();scroll.addView(items);recent.addView(scroll);
            for(int i=0;i<history.length();i++){String query=history.optString(i);items.addView(app.button(query,()->{input.setText(query);input.setSelection(input.length());submit();}));}}
    }
    void voice(){
        Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PROMPT,"Search films and series");
        try{app.startActivityForResult(intent,VOICE_REQUEST);}catch(ActivityNotFoundException|SecurityException e){app.toast("Voice input is unavailable. Use the search keyboard.");input.requestFocus();}
    }
    void voiceResult(int result,Intent data){if(result!=Activity.RESULT_OK||data==null)return;ArrayList<String> words=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);if(words!=null&&!words.isEmpty()){input.setText(words.get(0));input.setSelection(input.length());submit();}}
    void hide(){
        if(!visible)return;visible=false;lastFocus=app.getCurrentFocus();hideKeyboard();
        if(debounce!=null)handler.removeCallbacks(debounce);
        failedPages.putAll(inFlight);inFlight.clear();pending=0;repository.cancel();
    }
    void restoreCard(){for(int i=0;i<displayed.size();i++)if(displayed.get(i).key.equals(focusedKey)){int position=i;grid.scrollToPosition(position);grid.post(()->{RecyclerView.ViewHolder h=grid.findViewHolderForAdapterPosition(position);if(h!=null)h.itemView.requestFocus();});break;}}
    @Override public void close(){hide();repository.close();handler.removeCallbacksAndMessages(null);}

    final class Cards extends RecyclerView.Adapter<Card> {
        @Override public Card onCreateViewHolder(ViewGroup parent,int type){
            LinearLayout card=app.column();card.setFocusable(true);card.setClickable(true);card.setBackground(app.focusBg());card.setPadding(app.dp(5),app.dp(5),app.dp(5),app.dp(5));
            RecyclerView.LayoutParams lp=new RecyclerView.LayoutParams(-1,app.dp(240));lp.setMargins(app.dp(4),app.dp(4),app.dp(4),app.dp(8));card.setLayoutParams(lp);
            ImageView image=new ImageView(app);image.setScaleType(ImageView.ScaleType.CENTER_CROP);card.addView(image,new LinearLayout.LayoutParams(-1,app.dp(163)));image.setBackground(app.style.surface(MainActivity.PANEL,0,4));image.setClipToOutline(true);
            TextView title=app.text("",14,MainActivity.INK);title.setMinLines(2);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(title);
            TextView info=app.text("",11,MainActivity.MUTED);info.setMaxLines(1);info.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(info);return new Card(card,image,title,info);
        }
        @Override public void onBindViewHolder(Card holder,int position){
            SearchModel.Result result=displayed.get(position);JSONObject item=result.item();
            holder.title.setText(item.optString("name"));holder.info.setText(item.optString("year",item.optString("releaseInfo"))+" · "+(item.optString("type").equals("series")?"Series":"Film"));
            Glide.with(app).load(item.optString("poster")).placeholder(app.shape(MainActivity.PANEL,0)).error(app.shape(MainActivity.PANEL,0)).into(holder.image);
            holder.itemView.setContentDescription(item.optString("name")+", "+holder.info.getText());
            holder.itemView.setOnFocusChangeListener((v,focused)->{if(focused){focusedKey=result.key;v.post(()->{if(v.hasFocus())v.requestRectangleOnScreen(new Rect(0,0,v.getWidth(),v.getHeight()),true);});}});
            holder.itemView.setOnClickListener(v->{
                remember();focusedKey=result.key;app.searchOrigin=true;
                try{JSONObject chosen=new JSONObject(item.toString());JSONArray alternatives=new JSONArray();for(SearchModel.ProviderRef ref:result.references)alternatives.put(ref.item);chosen.put("_searchAlternatives",alternatives);app.details(chosen);}catch(JSONException e){app.toast("Cannot open this title.");}
            });
        }
        @Override public void onViewRecycled(Card holder){Glide.with(app).clear(holder.image);}
        @Override public int getItemCount(){return displayed.size();}
    }
    static final class Card extends RecyclerView.ViewHolder {
        final ImageView image;final TextView title,info;
        Card(View view,ImageView image,TextView title,TextView info){super(view);this.image=image;this.title=title;this.info=info;}
    }
}
