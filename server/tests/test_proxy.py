import asyncio
from datetime import datetime
import json
from pathlib import Path
import sys
import time
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import pytest
from fastapi import HTTPException
from discovery import parse_links, scheduled_day, Discovery
from network import valid_url, public_ip, PublicResolver
from relay import Relay
from store import Store
from providers import embed_url


@pytest.mark.parametrize('url',['http://127.0.0.1/a','http://10.0.0.1','http://[::1]','http://[::ffff:127.0.0.1]','http://169.254.169.254','file:///tmp/a','http://user:pass@example.com','http://example.com:8006','http://router.lan'])
def test_private_destinations_rejected(url):
    with pytest.raises(ValueError):valid_url(url)


@pytest.mark.asyncio
async def test_dns_checked_at_connection():
    resolver=PublicResolver()
    class Fake:
        async def resolve(self,*args):return [{'host':'127.0.0.1'}]
    resolver.resolver=Fake()
    with pytest.raises(ValueError):await resolver.resolve('public.example',443)


def test_hls_rewrites_keys_maps_and_variants():
    r=Relay(None);stream=r.create('test',{'url':'https://cdn.example/master.m3u8'},'http://proxy')
    sid=urlsplit(stream['url']).path.split('/')[2]
    raw=b'#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="keys/key"\n#EXT-X-MAP:URI="init.mp4"\n#EXT-X-STREAM-INF:BANDWIDTH=100\nvideo/playlist.m3u8\n'
    result=r.hls(raw,'https://cdn.example/master.m3u8',sid,'http://proxy').decode()
    assert result.count('http://proxy/relay/')==3
    assert 'https://cdn.example/keys/key' in [v[0] for v in r.sessions[sid].resources.values()]
    assert 'video/playlist.m3u8' not in result


def test_dash_templates_and_base_urls():
    r=Relay(None);stream=r.create('test',{'url':'https://cdn.example/a/manifest.mpd'},'http://proxy')
    sid=urlsplit(stream['url']).path.split('/')[2]
    raw=b'<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><BaseURL>video/</BaseURL><Period><AdaptationSet><SegmentTemplate media="$RepresentationID$/seg-$Number%05d$.m4s" initialization="init.mp4"/></AdaptationSet></Period></MPD>'
    result=r.dash(raw,'https://cdn.example/a/manifest.mpd',sid,'http://proxy').decode()
    assert 'https://cdn.example' not in result
    session=r.sessions[sid]
    rid=next(k for k,v in session.resources.items() if '$Number' in v[0])
    assert r.target(session,rid,'segment-video1-00042.m4s')=='https://cdn.example/a/video/video1/seg-00042.m4s'


def test_directory_dedupe_and_private_links():
    html='<main><a href="https://vidlink.pro/a">One</a><a href="https://vidlink.pro/b">Two</a><a href="http://10.0.0.1">Router</a></main>'
    items=parse_links(html,'https://directory.example')
    assert len(items)==1 and items[0]['adapter']=='vidlink'


def test_scheduler_london_dst():
    assert scheduled_day(datetime(2026,10,25,3,59,tzinfo=ZoneInfo('Europe/London')))=='2026-10-24'
    assert scheduled_day(datetime(2026,10,25,4,0,tzinfo=ZoneInfo('Europe/London')))=='2026-10-25'


@pytest.mark.asyncio
async def test_partial_refresh_preserves_snapshot(tmp_path):
    store=Store(tmp_path/'state.db')
    old=[{'id':'previous.example','origin':'old'}]*10
    store.put('directory:reddit',old)
    class Network:
        async def read(self,url):
            if 'reddit' in url:raise ValueError('Unavailable')
            return ''.join(f'<a href="https://site{i}.example">Site</a>' for i in range(8)).encode(),url
    async def health():pass
    d=Discovery(Network(),store,health)
    await d.refresh()
    assert store.get('directory:reddit')==old
    assert store.get('last_successful_update')
    assert store.get('discovery')['directories']['reddit']['ok'] is False


def test_session_expiry_and_limits():
    r=Relay(None);stream=r.create('test',{'url':'https://cdn.example/video.mp4'},'http://proxy')
    sid=urlsplit(stream['url']).path.split('/')[2]
    r.sessions[sid].expires=time.time()-1;r.prune()
    assert sid not in r.sessions


def test_episode_adapter():
    url=embed_url('vidlink',{'type':'series','tmdb_id':'123','season':2,'episode':3})
    assert '/tv/123/2/3?' in url
    with pytest.raises(ValueError):embed_url('vidlink',{'type':'series','tmdb_id':'123'})
