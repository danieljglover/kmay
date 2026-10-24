import asyncio
from contextlib import asynccontextmanager, suppress
import json
import os
from pathlib import Path
import secrets
import hashlib
from typing import Literal
from fastapi import FastAPI, Depends, Header, HTTPException, Request
from pydantic import BaseModel, Field
from network import Network, valid_url
from store import Store, now
from discovery import Discovery, safe_error
from providers import Providers, HOSTED, OPEN_FILM
from relay import Relay
from verification import probe as verify_media
from health import refresh_health, allowed, save_health
from feeds import stream_types
from comparison import Comparisons

DATA = Path(os.environ.get('KMAY_DATA', 'data'))
TOKEN = os.environ.get('KMAY_TOKEN', '')
store = network = providers = relay = discovery = None
refresh_task = None
comparisons = None


async def probe(stream):
    await verify_media(network, stream)


async def health():
    await refresh_health(network, providers, store, discovery)


@asynccontextmanager
async def lifespan(app):
    global store, network, providers, relay, discovery, comparisons
    if not TOKEN.strip():
        raise RuntimeError('Set KMAY_TOKEN to a non-empty passphrase')
    DATA.mkdir(parents=True, exist_ok=True)
    store = Store(DATA / 'kmay.sqlite3')
    network = Network()
    await network.start()
    config = DATA / 'addons.json'
    providers = Providers(network, store.get('addons', json.loads(config.read_text()) if config.exists() else []))
    relay = Relay(network)
    comparisons = Comparisons(network, providers, relay)
    discovery = Discovery(network, store, health)
    task = asyncio.create_task(discovery.schedule())
    yield
    task.cancel()
    if refresh_task:
        refresh_task.cancel()
        with suppress(asyncio.CancelledError):
            await refresh_task
    with suppress(asyncio.CancelledError):
        await task
    await comparisons.close()
    await providers.close()
    await network.close()
    store.close()


app = FastAPI(title='KMay proxy', version='1.2.0', lifespan=lifespan, docs_url=None, redoc_url=None)


def authenticate(authorization: str = Header(default='')):
    if not TOKEN or not secrets.compare_digest(authorization, 'Bearer ' + TOKEN):
        raise HTTPException(401, 'Invalid server access token')


@app.get('/healthz')
def healthz():
    return {'ok':True, 'api_version':1}


@app.get('/v1/status', dependencies=[Depends(authenticate)])
async def status():
    return {'api_version':1, 'version':'1.2.0', 'last_successful_update':store.get('last_successful_update'),
            'refreshing':discovery.lock.locked(), 'discovery':store.get('discovery'),
            'relay_requests':relay.requests, 'relay_bytes':relay.bytes, 'active_requests':relay.active,
            'providers':store.get('health', {}), 'source_counts':discovery.counts()}


@app.get('/v1/providers', dependencies=[Depends(authenticate)])
async def provider_list():
    supported = [{'id':key, **value} for key,value in HOSTED.items()]
    supported += [{'id':'open-film', 'name':'Blender open film'}]
    supported += [{'id':a['id'], 'name':a['name']} for a in providers.addons]
    checks = store.get('health', {})
    return {'providers':[{**p, **checks.get(p['id'], {'enabled':False})} for p in supported],
            'candidates':discovery.candidates(public=True)}


@app.post('/v1/refresh', status_code=202, dependencies=[Depends(authenticate)])
async def refresh():
    global refresh_task
    if discovery.lock.locked() or (refresh_task and not refresh_task.done()):
        return {'started':False, 'refreshing':True}
    refresh_task = asyncio.create_task(discovery.refresh())
    return {'started':True, 'refreshing':True}


async def register_addon(manifest_url):
    valid_url(manifest_url)
    if not manifest_url.split('?')[0].endswith('/manifest.json'):
        raise ValueError('Use a configured /manifest.json URL')
    raw,_=await network.read(manifest_url)
    manifest=json.loads(raw)
    resources=manifest.get('resources',[])
    if not manifest.get('id') or not manifest.get('name') or not any(r=='stream' or isinstance(r,dict) and r.get('name')=='stream' for r in resources):
        raise ValueError('Add-on must support stream responses')
    key='addon-'+hashlib.sha256(manifest_url.encode()).hexdigest()[:16]
    addon={'id':key,'name':manifest['name'],'manifest':manifest_url,'capabilities':stream_types(manifest),'managed_by':'manual'}
    providers.addons=[a for a in providers.addons if a['id']!=key]+[addon]
    store.put('addons',providers.addons)
    return {'id':key,'name':manifest['name']}


class Addon(BaseModel):
    manifest: str = Field(max_length=2048)


@app.post('/v1/addons', dependencies=[Depends(authenticate)])
async def add_addon(addon: Addon):
    try:
        result=await register_addon(addon.manifest)
        await refresh()
        return {**result,'message':'Source added; playback verification queued.'}
    except Exception as exc:
        raise HTTPException(400,safe_error(exc)) from None


class Resolve(BaseModel):
    type: Literal['movie','series']
    id: str = Field(min_length=1, max_length=200)
    tmdb_id: str | None = Field(default=None, pattern=r'^\d{1,12}$')
    season: int | None = Field(default=None, ge=1, le=1000)
    episode: int | None = Field(default=None, ge=1, le=10000)
    provider_id: str | None = Field(default=None, max_length=100)


@app.post('/v1/comparisons', status_code=202, dependencies=[Depends(authenticate)])
async def compare(title: Resolve, request: Request):
    checks = store.get('health', {})
    candidates = [{'id': p, 'name': value['name']} for p, value in HOSTED.items()]
    candidates += [{'id': a['id'], 'name': a['name']} for a in providers.addons]
    candidates = [p for p in candidates if allowed(checks.get(p['id'], {}), title.type)]
    if title.id in ('bbb', 'open:bbb'):
        candidates = [{'id': 'open-film', 'name': 'Blender open film'}]
    try:
        key = comparisons.start(title.model_dump(), candidates, str(request.base_url).rstrip('/'))
    except ValueError as exc:
        raise HTTPException(429, str(exc)) from None
    return comparisons.snapshot(key)


@app.get('/v1/comparisons/{key}', dependencies=[Depends(authenticate)])
async def comparison_status(key: str):
    if key not in comparisons.jobs:
        raise HTTPException(404, 'Comparison expired. Please retry.')
    return comparisons.snapshot(key)


@app.post('/v1/comparisons/{key}/{action}', dependencies=[Depends(authenticate)])
async def comparison_action(key: str, action: Literal['finish', 'cancel']):
    if key not in comparisons.jobs:
        raise HTTPException(404, 'Comparison expired. Please retry.')
    return await comparisons.stop(key, cancel=action == 'cancel')


@app.post('/v1/resolve', dependencies=[Depends(authenticate)])
async def resolve(title: Resolve, request: Request):
    checks = store.get('health', {})
    is_open_film=title.id in ('bbb','open:bbb')
    available = ['open-film'] if is_open_film else [*HOSTED, *[a['id'] for a in providers.addons]]
    if title.provider_id:
        if title.provider_id not in available:
            raise HTTPException(400, 'Unknown provider')
        available = [title.provider_id]
    # Resolve one provider per request so a slow browser never blocks all options.
    if not title.provider_id and not is_open_film:
        return {'streams':[], 'providers':[{'id':p,'name':HOSTED.get(p,{}).get('name',next((a['name'] for a in providers.addons if a['id']==p),p))} for p in available if allowed(checks.get(p,{}),title.type)],
                'message':'Choose a provider to resolve its stream.'}
    streams, errors = [], []
    for provider in available:
        try:
            async with asyncio.timeout(65):
                resolved = await providers.resolve(provider, title.model_dump())
                for stream in resolved[:6]:
                    try:
                        await probe(stream)
                        streams.append(relay.create(provider, stream, str(request.base_url).rstrip('/')))
                    except Exception as exc:
                        errors.append({'provider':provider, 'error':safe_error(exc)})
                if any(s['provider_id']==provider for s in streams):
                    save_health(store,provider,title.type)
        except Exception as exc:
            errors.append({'provider':provider, 'error':safe_error(exc)})
    return {'streams':streams, 'errors':errors, 'providers':[]}


@app.api_route('/relay/{sid}/{rid}/{tail:path}', methods=['GET','HEAD'])
async def media(sid: str, rid: str, tail: str, request: Request):
    return await relay.serve(sid, rid, tail, request)
