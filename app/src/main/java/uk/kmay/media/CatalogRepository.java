package uk.kmay.media;

import org.json.*;
import java.util.*;

/** Read provider capabilities from manifests before displaying catalogue controls. */
final class CatalogRepository {
    static final class Catalog {
        final String key, name, base, type, id;
        final boolean search, skip;
        Catalog(String base, String type, String id, String name, boolean search, boolean skip) {
            this.base=base; this.type=type; this.id=id; this.name=name; this.search=search; this.skip=skip;
            key=base+"|"+type+"|"+id;
        }
        String url(String query, String genre, int offset) {
            List<String> extras=new ArrayList<>();
            if (!query.isEmpty() && search) extras.add("search="+Net.segment(query));
            if (!genre.isEmpty() && base.equals(Net.CATALOG)) extras.add("genre="+Net.segment(genre));
            if (offset>0 && skip) extras.add("skip="+offset);
            return base+"/catalog/"+Net.segment(type)+"/"+Net.segment(id)+(extras.isEmpty()?"":"/"+String.join("&",extras))+".json";
        }
    }
    static Catalog films() { return new Catalog(Net.CATALOG,"movie","top","Films",true,true); }
    static Catalog series() { return new Catalog(Net.CATALOG,"series","top","Series",true,true); }
    static List<Catalog> addons(LibraryStore store) {
        List<Catalog> out=new ArrayList<>();
        for(JSONObject addon:Net.objects(store.array("addons"))) {
            JSONObject manifest=addon.optJSONObject("manifest"); if(manifest==null)continue;
            for(JSONObject cat:Net.objects(manifest.optJSONArray("catalogs"))) {
                boolean search=false,skip=false,required=false;
                for(JSONObject extra:Net.objects(cat.optJSONArray("extra"))) {
                    String name=extra.optString("name"); if(name.equals("search"))search=true; else if(name.equals("skip"))skip=true;
                    if(extra.optBoolean("isRequired"))required=true;
                }
                if(!required)out.add(new Catalog(addon.optString("base"),cat.optString("type"),cat.optString("id"),cat.optString("name",manifest.optString("name")),search,skip));
            }
        }
        return out;
    }
    static List<JSONObject> load(Catalog cat,String query,String genre,int offset)throws Exception {
        List<JSONObject> items=Net.objects(Net.json(cat.url(query,genre,offset)).optJSONArray("metas"));
        for(JSONObject item:items) { item.put("_base",cat.base); if(!item.has("type"))item.put("type",cat.type); }
        return items;
    }
    static JSONObject details(JSONObject item)throws Exception {
        JSONObject full=Net.json(item.optString("_base",Net.CATALOG)+"/meta/"+Net.segment(item.optString("type","movie"))+"/"+Net.segment(item.optString("id"))+".json").getJSONObject("meta");
        full.put("_base",item.optString("_base",Net.CATALOG)); if(!full.has("type"))full.put("type",item.optString("type","movie"));
        return full;
    }
}
