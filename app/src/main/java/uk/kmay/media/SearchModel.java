package uk.kmay.media;

import org.json.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Search identity never replaces the provider's playback identity. */
final class SearchModel {
    private static final Pattern MARKS=Pattern.compile("\\p{M}+"), PUNCTUATION=Pattern.compile("[^\\p{L}\\p{N}]+"), SPACES=Pattern.compile("\\s+");
    static String normalize(String value) {
        String plain=MARKS.matcher(Normalizer.normalize(value, Normalizer.Form.NFKD)).replaceAll("").toLowerCase(Locale.ROOT);
        return SPACES.matcher(PUNCTUATION.matcher(plain).replaceAll(" ").trim()).replaceAll(" ");
    }

    static final class Request {
        final String query, type;
        Request(String query, String type) { this.query=query.trim(); this.type=type; }
        boolean accepts(JSONObject item) {
            String t=item.optString("type");
            return (t.equals("movie")||t.equals("series")) && (type.isEmpty()||type.equals(t));
        }
    }

    static final class ProviderRef {
        final String base;
        final JSONObject item;
        ProviderRef(String base, JSONObject item) { this.base=base; this.item=item; }
    }

    static final class Result {
        final String key, normalizedTitle;
        final List<ProviderRef> references=new ArrayList<>();
        boolean local;
        int providerRank=Integer.MAX_VALUE,providerOrder=Integer.MAX_VALUE;
        Result(JSONObject item, boolean local) {
            this.key=identity(item); this.local=local;normalizedTitle=normalize(item.optString("name"));
            references.add(new ProviderRef(item.optString("_base", Net.CATALOG), item));
        }
        JSONObject item() { return references.get(0).item; }
        void merge(Result other) {
            local |= other.local;
            if(other.providerRank<providerRank||(other.providerRank==providerRank&&other.providerOrder<providerOrder)){
                providerRank=other.providerRank;providerOrder=other.providerOrder;
            }
            for(ProviderRef ref:other.references) {
                boolean exists=false;
                for(ProviderRef old:references) if(old.base.equals(ref.base)&&old.item.optString("id").equals(ref.item.optString("id"))) exists=true;
                if(!exists) references.add(ref);
            }
        }
    }

    static String identity(JSONObject item) {
        String id=item.optString("id"), type=item.optString("type");
        // IMDb IDs have a shared namespace. Arbitrary add-on IDs do not.
        if(id.matches("tt[0-9]+")) return type+":imdb:"+id;
        String imdb=item.optString("imdb_id");
        if(imdb.matches("tt[0-9]+"))return type+":imdb:"+imdb;
        String tmdb=item.optString("moviedb_id");
        if(tmdb.matches("[1-9][0-9]*"))return type+":tmdb:"+tmdb;
        return type+":"+item.optString("_base",Net.CATALOG)+":"+id;
    }

    static int rank(String title, String query, boolean fuzzy) {
        return rankNormalized(normalize(title),normalize(query),fuzzy);
    }
    static int rankNormalized(String name,String q,boolean fuzzy) {
        if(q.isEmpty()) return 0;
        if(name.equals(q)) return 0;
        if(name.startsWith(q)) return 1;
        boolean all=true;
        for(String token:q.split(" ")) if(!name.contains(token)) { all=false; break; }
        if(all) return 2;
        if(fuzzy && q.length()>=4 && Math.abs(name.length()-q.length())<=1 && oneEdit(name,q)) return 3;
        return 4;
    }

    static boolean oneEdit(String a,String b) {
        int i=0,j=0,edits=0;
        while(i<a.length()&&j<b.length()) {
            if(a.charAt(i)==b.charAt(j)){i++;j++;continue;}
            if(++edits>1)return false;
            if(a.length()>=b.length())i++;
            if(b.length()>=a.length())j++;
        }
        return edits+(a.length()-i)+(b.length()-j)<=1;
    }

    static List<Result> ranked(Collection<Result> results, Request request) {
        List<Result> list=new ArrayList<>(results);
        String query=normalize(request.query);Map<String,Integer> ranks=new HashMap<>();
        for(Result r:list)ranks.put(r.key,rankNormalized(r.normalizedTitle,query,r.local));
        list.sort(Comparator.comparingInt((Result r)->ranks.get(r.key))
                .thenComparing(r->!r.local)
                .thenComparingInt(r->r.providerRank)
                .thenComparingInt(r->r.providerOrder)
                .thenComparing(r->r.normalizedTitle)
                .thenComparing(r->r.key));
        return list;
    }

    static final class Provider {
        final String base,id,type,name;
        final boolean paging;
        final List<Integer> pages;
        Provider(String base,String id,String type,String name,boolean paging,List<Integer> pages) {
            this.base=base;this.id=id;this.type=type;this.name=name;this.paging=paging;this.pages=pages;
        }
        String key(){return base+"|"+type+"|"+id;}
        int next(int current) {
            if(!paging)return -1;
            if(pages.isEmpty())return current+100;
            for(int page:pages)if(page>current)return page;
            return -1;
        }
    }

    static final class Providers {
        final List<Provider> available=new ArrayList<>();
        int unsupported;
    }

    static Providers providers(JSONArray addons, String type) {
        Providers out=new Providers();
        for(String t:new String[]{"movie","series"}) if(type.isEmpty()||type.equals(t))
            out.available.add(new Provider(Net.CATALOG,"top",t,"Cinemeta "+(t.equals("movie")?"films":"series"),true,Collections.emptyList()));
        Set<String> seen=new HashSet<>();
        for(JSONObject addon:Net.objects(addons)) {
            JSONObject manifest=addon.optJSONObject("manifest"); if(manifest==null)continue;
            for(JSONObject cat:Net.objects(manifest.optJSONArray("catalogs"))) {
                String t=cat.optString("type");
                if(!(t.equals("movie")||t.equals("series"))||(!type.isEmpty()&&!type.equals(t)))continue;
                boolean search=false, unsupported=false, paging=false;
                List<Integer> pages=new ArrayList<>();
                for(JSONObject extra:Net.objects(cat.optJSONArray("extra"))) {
                    String name=extra.optString("name");
                    if(name.equals("search"))search=true;
                    else if(name.equals("skip")) {
                        paging=true;JSONArray opts=extra.optJSONArray("options");
                        if(opts!=null)for(int i=0;i<opts.length();i++)try{int p=Integer.parseInt(opts.optString(i));if(p>=0)pages.add(p);}catch(NumberFormatException ignored){}
                        if(opts!=null&&!pages.contains(0))unsupported=true;
                    } else if(extra.optBoolean("isRequired"))unsupported=true;
                }
                String base=addon.optString("base"),id=cat.optString("id");
                if(!search||unsupported||!Net.http(base)||id.isEmpty()){out.unsupported++;continue;}
                Collections.sort(pages);
                Provider p=new Provider(base,id,t,manifest.optString("name","Add-on")+" / "+cat.optString("name",id),paging,pages);
                if(seen.add(p.key()))out.available.add(p);
            }
        }
        return out;
    }
}
