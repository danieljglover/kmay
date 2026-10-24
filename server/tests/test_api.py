import asyncio
import os
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
os.environ['KMAY_TOKEN']='test-only-access-token-long-enough'
from fastapi.testclient import TestClient
import pytest
import main
from relay import Relay
from store import Store
from discovery import Discovery


class Content:
    async def iter_chunked(self,size):
        yield b'media-bytes'


class Upstream:
    status=206
    headers={'Content-Type':'video/mp4','Content-Range':'bytes 0-10/100','Content-Length':'11','Accept-Ranges':'bytes'}
    content=Content()
    url='https://public.example/video.mp4'
    closed=False
    def close(self):self.closed=True


class Network:
    headers=None
    response=None
    async def open(self,url,headers,method):
        self.headers=headers;self.response=Upstream();return self.response


@pytest.fixture
def client(tmp_path):
    main.TOKEN='test-only-access-token-long-enough'
    main.store=Store(tmp_path/'db')
    main.network=Network()
    main.relay=Relay(main.network)
    main.discovery=Discovery(main.network,main.store,None)
    # Deliberately don't run lifespan: deterministic fixtures must not contact directories.
    return TestClient(main.app)


def test_auth_and_api_version(client):
    assert client.get('/v1/status').status_code==401
    assert client.get('/v1/status',headers={'Authorization':'Bearer wrong'}).status_code==401
    response=client.get('/v1/status',headers={'Authorization':'Bearer '+main.TOKEN})
    assert response.status_code==200 and response.json()['api_version']==1


def test_range_relay_closes_and_counts_bytes(client):
    stream=main.relay.create('test',{'url':'https://public.example/video.mp4'},'http://testserver')
    response=client.get(stream['url'],headers={'Range':'bytes=0-10'})
    assert response.status_code==206
    assert response.headers['content-range']=='bytes 0-10/100'
    assert response.content==b'media-bytes'
    assert main.network.headers['Range']=='bytes=0-10'
    assert main.network.response.closed and main.relay.active==0
    assert main.relay.bytes==11


def test_expired_session_and_unknown_resource(client):
    stream=main.relay.create('test',{'url':'https://public.example/video.mp4'},'http://testserver')
    session=next(iter(main.relay.sessions.values()))
    session.expires=0
    assert client.get(stream['url']).status_code==410
    assert client.get('/relay/not-a-session/not-a-resource/video.mp4').status_code==410


def test_upstream_error_does_not_leak_url(client):
    class Broken:
        async def open(self,*args):raise ValueError('secret-token-in-url')
    main.relay.network=Broken()
    stream=main.relay.create('test',{'url':'https://public.example/video.mp4'},'http://testserver')
    response=client.get(stream['url'])
    assert response.status_code==502 and 'secret-token' not in response.text
    assert main.relay.active==0


def test_episode_input_validation(client):
    response=client.post('/v1/resolve',headers={'Authorization':'Bearer '+main.TOKEN},json={'type':'series','id':'tt123:1:2','season':-1})
    assert response.status_code==422
