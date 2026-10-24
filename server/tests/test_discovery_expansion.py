import asyncio,json
import pytest
from feeds import parse_catalog,parse_links,parse_markdown,eligibility,stream_types,public_candidate
from health import save_health,allowed,refresh_health
from verification import dash_segment,probe,scoped_headers
from store import Store
from discovery import Discovery


def manifest(**kw):
    return {'id':'test','name':'Test','description':'English streams','types':['movie','series'],'resources':['stream'],**kw}


def test_catalog_preserves_shared_domain_and_configured_urls():
    urls=['https://host.example/a/manifest.json?key=secret','https://host.example/b/manifest.json']
    raw=json.dumps({'addons':[{'transportUrl':u,'manifest':manifest()} for u in urls]})
    items=parse_catalog(raw,'https://catalog.example')
    assert len(items)==2 and items[0]['manifest']==urls[0]
    assert 'secret' not in json.dumps(public_candidate(items[0]))
    assert len(parse_links(''.join(f'<a href="{u}">Source</a>' for u in urls),'https://catalog.example'))==2


def test_markdown_and_manifest_eligibility():
    assert len(parse_markdown(b'[Site](https://site.example) [bad](http://10.0.0.1)','https://wiki.example'))==1
    assert eligibility(manifest())[0]=='queued'
    assert eligibility(manifest(behaviorHints={'configurationRequired':True}))[0]=='configuration_required'
    assert eligibility(manifest(description='French streams'))[0]=='language_pending'
    assert eligibility(manifest(resources=['catalog']))[0]=='incompatible'
    assert stream_types(manifest(resources=[{'name':'stream','types':['movie'],'idPrefixes':['yt:']}]))=={}
    assert stream_types(manifest(resources=[{'name':'stream','types':['movie'],'idPrefixes':['tt']}]))=={'movie':['tt']}


def test_per_type_failure_grace(tmp_path):
    store=Store(tmp_path/'db')
    save_health(store,'test','movie')
    save_health(store,'test','series','failed')
    assert allowed(store.get('health')['test'],'movie')
    assert not allowed(store.get('health')['test'],'series')
    for _ in range(2):save_health(store,'test','movie','failed')
    assert allowed(store.get('health')['test'],'movie')
    save_health(store,'test','movie','failed')
    assert not allowed(store.get('health')['test'],'movie')


def test_dash_segment_inheritance_and_number_format():
    raw=b'<MPD><BaseURL>video/</BaseURL><Period><AdaptationSet><SegmentTemplate startNumber="3" media="$RepresentationID$/$Number%05d$.m4s"/><Representation id="720"/></AdaptationSet></Period></MPD>'
    assert dash_segment(raw,'https://cdn.example/a/manifest.mpd')=='https://cdn.example/a/video/720/00003.m4s'
    with pytest.raises(ValueError):dash_segment(b'<MPD/>','https://cdn.example/a.mpd')
    assert scoped_headers({'Cookie':'secret','Referer':'public'},'https://a.example','https://b.example')=={'Referer':'public'}


@pytest.mark.asyncio
async def test_probe_requires_dash_segment_bytes():
    class Content:
        def __init__(self,raw):self.raw=raw
        async def read(self,n):return self.raw[:n]
    class Response:
        def __init__(self,url):
            self.url=url;self.status=200;self.headers={'Content-Type':'application/dash+xml' if url.endswith('.mpd') else 'video/mp4'}
            self.content=Content(b'<MPD/>' if url.endswith('.mpd') else b'')
        def close(self):pass
    class Network:
        calls=[]
        async def open(self,url,headers):self.calls.append(url);return Response(url)
        async def read(self,*a,**kw):return b'<MPD><Period><AdaptationSet><Representation><BaseURL>movie.mp4</BaseURL></Representation></AdaptationSet></Period></MPD>','https://cdn.example/x.mpd'
    n=Network()
    with pytest.raises(ValueError,match='Media bytes unavailable'):await probe(n,{'url':'https://cdn.example/x.mpd'})
    assert n.calls[-1]=='https://cdn.example/movie.mp4'


@pytest.mark.asyncio
async def test_verification_queue_rotation_and_manual_preservation(tmp_path,monkeypatch):
    import health
    store=Store(tmp_path/'db')
    class Network:
        async def read(self,url):return json.dumps(manifest(description='Unknown')).encode(),url
    class Providers:addons=[]
    class Discovery:
        def candidates(self):
            states=store.get('candidate_states',{})
            return [{'id':f'addon-{i:03}','manifest':f'https://site{i}.example/manifest.json',**states.get(f'addon-{i:03}',{})} for i in range(105)]
    async def no_builtin(*a):pass
    monkeypatch.setattr(health,'check_provider',no_builtin)
    await refresh_health(Network(),Providers(),store,Discovery())
    assert len(store.get('candidate_states'))==100
    await refresh_health(Network(),Providers(),store,Discovery())
    assert len(store.get('candidate_states'))==105


@pytest.mark.asyncio
async def test_fmhy_backup_is_one_family(tmp_path):
    store=Store(tmp_path/'db')
    class Network:
        async def read(self,url):
            if 'raw.githubusercontent' in url:return '\n'.join(f'[Site](https://site{i}.example)' for i in range(8)).encode(),url
            raise ValueError('Unavailable')
    async def health():pass
    d=Discovery(Network(),store,health);await d.refresh()
    assert store.get('discovery')['directories']['fmhy']['ok']
    assert len(d.candidates())==8

@pytest.mark.asyncio
async def test_hls_rejects_missing_later_segment():
    class Content:
        async def read(self,n):return b'#EXTM3U' if self.playlist else b'video bytes'
    class Response:
        def __init__(self,url):
            self.url=url;self.status=403 if url.endswith('5.ts') else 200
            self.headers={'Content-Type':'application/vnd.apple.mpegurl' if url.endswith('.m3u8') else 'video/mp2t'}
            self.content=Content();self.content.playlist=url.endswith('.m3u8')
        def close(self):pass
    class Network:
        async def open(self,url,headers):return Response(url)
        async def read(self,url,*a,**kw):return ('#EXTM3U\n'+''.join(f'#EXTINF:4,\n{i}.ts\n' for i in range(6))).encode(),url
    with pytest.raises(ValueError,match='Later media segment unavailable'):
        await probe(Network(),{'url':'https://cdn.example/a.m3u8'})
