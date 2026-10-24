package uk.kmay.media;

import android.app.AlertDialog;
import android.text.InputType;
import android.widget.*;
import org.json.*;

final class ProxySettings {
    static void addSource(MainActivity a){
        EditText input=new EditText(a);input.setSingleLine();input.setHint("https://your-addon/manifest.json");
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Add a server source").setMessage("The configured add-on URL will be stored on your local server.").setView(a.dialogForm(input)).setPositiveButton("Add",null).setNegativeButton("Cancel",null).create();dialog.show();
        dialog.getButton(-1).setOnClickListener(v->{String manifest=input.getText().toString().trim().replaceFirst("^stremio://","https://");int g=a.generation;dialog.getButton(-1).setEnabled(false);
            a.jobs.execute(()->{try{ProxyClient.request(a,"/v1/addons",new JSONObject().put("manifest",manifest));a.runOnUiThread(()->{if(g!=a.generation)return;dialog.dismiss();a.toast("Server source added. Verification is queued.");});}
            catch(Exception e){a.runOnUiThread(()->{if(g!=a.generation)return;dialog.getButton(-1).setEnabled(true);input.setError(Net.message(e));});}});
        });
    }
    static void add(MainActivity a){
        a.body.addView(a.text("Local media server",20,MainActivity.INK));
        a.body.addView(a.button("Proxy mode: "+(a.prefs.getBoolean("proxyEnabled",false)?"on":"off"),()->{
            if(!a.prefs.getBoolean("proxyEnabled",false)&&a.prefs.getString("proxyUrl","").isEmpty()){configure(a);return;}
            a.prefs.edit().putBoolean("proxyEnabled",!a.prefs.getBoolean("proxyEnabled",false)).apply();a.browser.sourceSettings();
        }));
        a.body.addView(a.text(a.prefs.getString("proxyUrl","No server configured"),14,MainActivity.MUTED));
        a.body.addView(a.button("Configure server",()->configure(a)));
        a.body.addView(a.button("Test server connection",()->status(a,false)));
        a.body.addView(a.button("Refresh server sources",()->status(a,true)));
        a.body.addView(a.button("Source availability",()->availability(a)));
        a.body.addView(a.text("Proxy mode routes playback through your server. If it is offline, retry or turn proxy mode off. Daily updates refresh sources and availability.",14,MainActivity.MUTED));
    }
    static void configure(MainActivity a){
        LinearLayout form;
        EditText url=new EditText(a);url.setSingleLine();url.setHint("Server URL, e.g. http://192.168.1.100:8788");url.setText(a.prefs.getString("proxyUrl",""));
        EditText token=new EditText(a);token.setSingleLine();token.setHint("Server access token");token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);token.setText(a.prefs.getString("proxyToken",""));form=a.dialogForm(url,token);
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Local media server").setView(form).setPositiveButton("Test and save",null).setNegativeButton("Cancel",null).create();dialog.show();
        dialog.getButton(-1).setOnClickListener(v->{
            final String base,key=token.getText().toString().trim();
            try{base=ProxyClient.normalize(url.getText().toString());if(key.isEmpty())throw new Exception("Enter the server access token.");}catch(Exception e){url.setError(e.getMessage());return;}
            dialog.getButton(-1).setEnabled(false);int g=a.generation;
            a.jobs.execute(()->{try{
                JSONObject response=ProxyClient.request(base,key,"/v1/status",null);
                if(response.optInt("api_version")!=1)throw new Exception("This server version is incompatible with this app.");
                a.runOnUiThread(()->{if(g!=a.generation||!dialog.isShowing())return;a.prefs.edit().putString("proxyUrl",base).putString("proxyToken",key).putBoolean("proxyEnabled",true).apply();dialog.dismiss();a.browser.sourceSettings();a.toast("Server connected. Proxy mode is on.");});
            }catch(Exception e){a.runOnUiThread(()->{if(g!=a.generation||!dialog.isShowing())return;dialog.getButton(-1).setEnabled(true);url.setError(Net.message(e));});}});
        });
    }
    static void availability(MainActivity a){
        int g=a.generation;a.toast("Checking source availability...");
        a.jobs.execute(()->{try{
            JSONObject response=ProxyClient.request(a,"/v1/providers",null);
            java.util.Map<String,Integer> counts=new java.util.TreeMap<>();
            for(JSONObject c:Net.objects(response.optJSONArray("candidates"))){String state=c.optString("status","unknown");counts.put(state,counts.getOrDefault(state,0)+1);}
            java.util.List<JSONObject> integrations=Net.objects(response.optJSONArray("providers"));
            integrations.sort((x,y)->{int order=Boolean.compare(!x.optBoolean("enabled"),!y.optBoolean("enabled"));return order!=0?order:x.optString("name").compareToIgnoreCase(y.optString("name"));});
            int enabledCount=0;for(JSONObject p:integrations)if(p.optBoolean("enabled")&&!p.optString("id").equals("open-film"))enabledCount++;
            StringBuilder text=new StringBuilder(enabledCount+" playback providers enabled. Availability varies by title.\n\nDiscovery entries are not all playback sources. Websites need an integration; add-ons must return working video and audio.\n\nPlayback integrations\n");
            for(JSONObject provider:integrations){
                if(provider.optString("id").equals("open-film"))continue;
                text.append("\n").append(provider.optString("name")).append(": ");
                JSONObject types=provider.optJSONObject("types");boolean enabled=provider.optBoolean("enabled");
                if(enabled){text.append("verified for ");if(types!=null){JSONObject m=types.optJSONObject("movie"),s=types.optJSONObject("series");boolean movie=m!=null&&m.optBoolean("enabled"),series=s!=null&&s.optBoolean("enabled");text.append(movie?"films":"").append(movie&&series?" and ":"").append(series?"series":"");}}
                else{String reason="No verified media";if(types!=null){JSONObject m=types.optJSONObject("movie");if(m!=null)reason=m.optString("error",reason);}text.append(reason);}
                text.append("\n");
            }
            text.append("\nDiscovered entries\n");
            String[][] labels={{"enabled","Verified add-ons"},{"queued","Awaiting checks"},{"unsupported","Websites needing integration"},{"configuration_required","Need account/configuration"},{"incompatible","Incompatible with HTTP playback"},{"language_pending","Other declared languages"},{"unavailable","Failed verification"},{"out_of_scope","Outside selected content scope"}};
            for(String[] label:labels)text.append("\n").append(label[1]).append(": ").append(counts.getOrDefault(label[0],0));
            String message=text.toString();a.runOnUiThread(()->{if(g!=a.generation)return;ScrollView scroll=new ScrollView(a);LinearLayout form=a.dialogForm(a.text(message,14,MainActivity.INK));scroll.addView(form);new AlertDialog.Builder(a).setTitle("Source availability").setView(scroll).setPositiveButton("Close",null).show();});
        }catch(Exception e){a.runOnUiThread(()->{if(g==a.generation)a.toast("Could not load source availability: "+Net.message(e));});}});
    }
    static void status(MainActivity a,boolean refresh){
        if(a.prefs.getString("proxyUrl","").isEmpty()){configure(a);return;}
        int g=a.generation;a.toast("Connecting to server…");
        a.jobs.execute(()->{try{
            if(refresh)ProxyClient.request(a,"/v1/refresh",new JSONObject());
            JSONObject response=ProxyClient.request(a,"/v1/status",null);
            if(response.optInt("api_version")!=1)throw new Exception("Incompatible server API version.");
            String updated=response.isNull("last_successful_update")?"Not yet completed":response.optString("last_successful_update");
            String message="Connected\nLast source update: "+updated+"\n"+(response.optBoolean("refreshing")?"Refresh in progress":"Daily refresh at 04:00 London time");
            a.runOnUiThread(()->{if(g==a.generation)new AlertDialog.Builder(a).setTitle("Media server").setMessage(message).setPositiveButton("OK",null).show();});
        }catch(Exception e){a.runOnUiThread(()->{if(g==a.generation)new AlertDialog.Builder(a).setTitle("Server unavailable").setMessage(Net.message(e)).setPositiveButton("Retry",(d,w)->status(a,refresh)).setNegativeButton("Close",null).show();});}});
    }
}
