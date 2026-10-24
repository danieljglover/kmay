package uk.kmay.media;

import android.view.View;
import android.widget.*;
import org.json.*;
import java.util.Locale;

/** Visible, cancellable comparison. Server owns the deadline and ranking. */
final class ComparisonScreen {
    final MainActivity a;
    final int generation;
    final String base, token;
    final TextView status, activity, rows, explanation;
    final ProgressBar progress, spinner;
    final Button finish, cancelButton;
    String id="";
    boolean stopped=false, finishing=false;

    ComparisonScreen(MainActivity a) {
        this.a=a;generation=a.generation;
        base=a.prefs.getString("proxyUrl","");token=a.prefs.getString("proxyToken","");
        status=a.sourceStatus;status.setText("Choosing the best source");
        explanation=a.text("Your server will test small video samples for up to 20 seconds, then play the best tested source automatically.",15,MainActivity.MUTED);a.body.addView(explanation);
        progress=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(200);
        LinearLayout.LayoutParams bar=new LinearLayout.LayoutParams(-1,a.dp(8));bar.setMargins(0,a.dp(20),0,a.dp(16));a.body.addView(progress,bar);
        LinearLayout line=a.row();spinner=new ProgressBar(a);line.addView(spinner,new LinearLayout.LayoutParams(a.dp(24),a.dp(24)));
        activity=a.text("Contacting your media server...",15,MainActivity.INK);activity.setPadding(a.dp(12),a.dp(8),0,a.dp(8));line.addView(activity);a.body.addView(line);
        rows=a.text("",14,MainActivity.MUTED);rows.setLineSpacing(a.dp(8),1);rows.setPadding(0,a.dp(16),0,a.dp(16));
        LinearLayout actions=a.row();finish=a.button("Play best so far",this::finish);finish.setEnabled(false);actions.addView(finish);
        cancelButton=a.button("Cancel",()->{cancel();a.details(a.selected);});actions.addView(cancelButton);a.body.addView(actions);
        a.body.addView(rows);
    }

    void start() {
        try { request("/v1/comparisons",ProxyClient.title(a)); }
        catch(Exception e){fail(Net.message(e));}
    }
    boolean current(){return !stopped&&generation==a.generation&&!a.isFinishing();}
    void request(String path,JSONObject payload){
        a.jobs.execute(()->{try{
            JSONObject result=ProxyClient.request(base,token,path,payload);
            a.runOnUiThread(()->{
                if(!current()){cancelRemote(result.optString("id",id));return;}
                id=result.optString("id",id);render(result);
            });
        }catch(Exception e){a.runOnUiThread(()->{if(current())fail(Net.message(e));});}});
    }
    void render(JSONObject result){
        String state=result.optString("state");boolean done=state.equals("complete");
        double elapsed=result.optDouble("elapsed_seconds",0);
        progress.setProgress(done?200:(int)(elapsed*10));
        StringBuilder summary=new StringBuilder();int completed=0,total=0;
        for(JSONObject row:Net.objects(result.optJSONArray("providers"))){
            String phase=row.optString("state"),label;
            switch(phase){
                case "resolving":label="Finding video links";break;
                case "testing":label="Testing video samples";break;
                case "waiting":label="Waiting for sample test";break;
                case "ready":label="Passed";completed++;break;
                case "unavailable":label=row.optString("reason","No working sample");completed++;break;
                case "timed_out":label="Timed out";completed++;break;
                default:label="Queued";
            }
            total++;summary.append(row.optString("name")).append("  -  ").append(label).append('\n');
        }
        rows.setText(summary.toString().trim());
        rows.setVisibility(total==0?View.GONE:View.VISIBLE);
        activity.setText(String.format(Locale.UK,"%d of %d providers checked  ·  %.0f / 20 seconds",completed,total,elapsed));
        finish.setEnabled(!finishing&&result.optInt("working")>0&&!done);
        if(done){
            stopped=true;spinner.setVisibility(View.GONE);activity.setPadding(0,a.dp(8),0,a.dp(8));a.comparison=null;finish.setVisibility(View.GONE);cancelButton.setText("Back to details");
            a.resolved.clear();a.streamKeys.clear();
            for(JSONObject stream:Net.objects(result.optJSONArray("streams")))a.addStream(stream,stream.optString("provider_id"));
            if(a.resolved.isEmpty()){
                status.setText("No source passed the comparison.");explanation.setText("The comparison has finished. Playback has not started.");activity.setText("Try again, or choose a source manually to allow more time.");
                a.body.addView(a.button("Retry comparison",()->a.sources(true)));a.body.addView(a.button("Choose source manually",()->a.sources(false)));return;
            }
            JSONObject best=a.resolved.get(0);
            status.setText("Comparison complete. Playing "+best.optString("provider_name",best.optString("name"))+"...");
            explanation.setText("The strongest working sample has been selected. Playback will start automatically.");
            activity.setText(String.format(Locale.UK,"Best tested sample: %.1f Mbps  ·  Response: %d ms",best.optDouble("sample_mbps"),best.optInt("response_ms")));
            a.autoStarted=true;
            a.handler.postDelayed(()->{if(generation==a.generation)a.play(best);},900);
        }else if(!finishing){a.handler.postDelayed(()->{if(current()&&!finishing)request("/v1/comparisons/"+id,null);},500);}
    }
    void finish(){if(id.isEmpty()||finishing)return;finishing=true;finish.setEnabled(false);status.setText("Finishing comparison...");request("/v1/comparisons/"+id+"/finish",new JSONObject());}
    void fail(String message){cancel();spinner.setVisibility(View.GONE);finish.setEnabled(false);status.setText("Comparison unavailable: "+message);activity.setText("Playback has not started.");a.body.addView(a.button("Retry comparison",()->a.sources(true)));a.body.addView(a.button("Choose source manually",()->a.sources(false)));}
    void cancelRemote(String key){if(key.isEmpty()||a.jobs.isShutdown())return;a.jobs.execute(()->{try{ProxyClient.request(base,token,"/v1/comparisons/"+key+"/cancel",new JSONObject());}catch(Exception ignored){}});}
    void cancel(){if(stopped)return;stopped=true;cancelRemote(id);if(a.comparison==this)a.comparison=null;}
}
