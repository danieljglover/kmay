import asyncio
from urllib.parse import urlsplit
import pytest
from fastapi import Request
from verification import probe
from relay import Relay
from feeds import eligibility

BASE='https://cdn.example/'
MASTER=b'''#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="sound",LANGUAGE="en",DEFAULT=YES,NAME="English",URI="audio.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=2000,AUDIO="sound"
high.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO="sound"
low.m3u8
'''


def playlist(prefix):
    return ('#EXTM3U\n'+''.join(f'#EXTINF:4,\n{prefix}{i}.ts\n' for i in range(14))).encode()


class Network:
    def __init__(self, failures=()):
        self.files={'master.m3u8':MASTER,'high.m3u8':playlist('high'),
                    'low.m3u8':playlist('low'),'audio.m3u8':playlist('audio')}
        self.failures=set(failures);self.opened=[];self.closed=[]

    async def read(self,url,*args,**kwargs):
        return self.files[urlsplit(url).path.lstrip('/')],url

    async def open(self,url,headers):
        network=self;path=urlsplit(url).path.lstrip('/')
        class Response:
            status=403 if path in network.failures else 200
            headers={'Content-Type':'application/vnd.apple.mpegurl' if path.endswith('m3u8') else 'video/mp2t'}
            def __init__(self):self.url=url;self.content=self
            async def read(self,count):return network.files.get(path,b'G'+b'x'*2048)[:count]
            def close(self):network.closed.append(url)
        self.opened.append(url)
        return Response()


@pytest.mark.asyncio
async def test_audio_failure_cannot_pass_video_comparison():
    network=Network(['audio0.ts'])
    with pytest.raises(ValueError,match='Audio segment unavailable'):
        await probe(network,{'url':BASE+'master.m3u8'})
    assert len(network.opened)==len(network.closed)


@pytest.mark.asyncio
async def test_later_segment_eight_is_checked():
    with pytest.raises(ValueError,match='Later media segment unavailable'):
        await probe(Network(['low8.ts']),{'url':BASE+'low.m3u8'})


@pytest.mark.asyncio
async def test_relay_only_advertises_verified_variant_and_audio():
    network=Network(['high8.ts']);stream={'url':BASE+'master.m3u8'}
    await probe(network,stream)
    assert 'high.m3u8' not in stream['_hls_manifests'][stream['url']]
    assert 'low.m3u8' in stream['_hls_manifests'][stream['url']]
    assert 'audio.m3u8' in stream['_hls_manifests'][stream['url']]
    relay=Relay(None);result=relay.create('test',stream,'http://proxy')
    sid,rid=urlsplit(result['url']).path.split('/')[2:4]
    request=Request({'type':'http','method':'GET','scheme':'http','server':('proxy',80),'path':'/relay', 'headers':[]})
    response=await relay.serve(sid,rid,'master.m3u8',request)
    assert response.status_code==200 and b'http://proxy/relay/' in response.body
    assert relay.active==0
    targets=[x[0] for x in relay.sessions[sid].resources.values()]
    assert BASE+'low.m3u8' in targets and BASE+'audio.m3u8' in targets
    assert BASE+'high.m3u8' not in targets


def test_unspecified_language_is_verified_but_explicit_non_english_is_held():
    base={'id':'x','name':'Test','resources':['stream'],'types':['movie']}
    assert eligibility(base)[0]=='queued'
    assert eligibility({**base,'language':'fr'})[0]=='language_pending'
    assert eligibility({**base,'description':'English and French streams'})[0]=='queued'
    assert eligibility({**base,'name':'OnlyPorn'})[0]=='out_of_scope'
    assert eligibility({**base,'name':'Adult Addon'})[0]=='out_of_scope'


def test_upstream_status_reason_is_safe_and_visible():
    from discovery import safe_error
    assert safe_error(ValueError('Audio segment unavailable (HTTP 403)'))=='Audio segment unavailable (HTTP 403)'
    assert 'secret' not in safe_error(ValueError('HTTP 403 https://host.example/secret'))


@pytest.mark.asyncio
async def test_same_provider_alternatives_are_retained():
    from comparison import Comparisons
    class Providers:
        async def resolve(self,*args):return [{'url':BASE+'first.mp4'}, {'url':BASE+'second.mp4'}]
    async def measure(*args):return {'score':1,'response_ms':1,'sample_mbps':1}
    jobs=Comparisons(None,Providers(),Relay(None),measure_fn=measure)
    key=jobs.start({},[{'id':'one','name':'One'}],'http://proxy')
    await jobs.jobs[key]['task']
    streams=jobs.snapshot(key)['streams']
    assert len(streams)==2 and streams[0]['url']!=streams[1]['url']
