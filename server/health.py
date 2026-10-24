"""Daily provider eligibility and persistent, bounded candidate verification."""
import asyncio
import json
from feeds import addon_id,eligibility,stream_types
from discovery import safe_error
from store import now
from verification import probe
from providers import HOSTED

SAMPLES={
 'movie':[{'type':'movie','id':'tt0137523','tmdb_id':'550'},{'type':'movie','id':'tt0078748','tmdb_id':'348'},{'type':'movie','id':'tt0468569','tmdb_id':'155'}],
 'series':[{'type':'series','id':'tt4574334:1:1','tmdb_id':'66732','season':1,'episode':1},{'type':'series','id':'tt0903747:1:1','tmdb_id':'1396','season':1,'episode':1},{'type':'series','id':'tt0944947:1:1','tmdb_id':'1399','season':1,'episode':1}]}


def save_health(store,key,kind,error=None):
    checks=store.get('health',{});old=checks.get(key,{})
    typed=dict(old.get('types',{}));previous=typed.get(kind,{})
    failures=previous.get('failures',0)+1 if error else 0
    typed[kind]={'enabled':not error or (previous.get('enabled',False) and failures<3),'failures':failures,'checked_at':now()}
    if error:typed[kind]['error']=error
    else:typed[kind]['last_success']=now()
    checks[key]={'enabled':any(t['enabled'] for t in typed.values()),'types':typed,'checked_at':now(),
                 'failures':max(t['failures'] for t in typed.values())}
    if not error:checks[key]['last_success']=now()
    elif old.get('last_success'):checks[key]['last_success']=old['last_success']
    store.put('health',checks)


def allowed(check,kind):
    return check.get('types',{}).get(kind,{}).get('enabled',False)


async def check_provider(network,providers,store,key,kinds):
    for kind in kinds:
        error='No HTTP stream for representative titles'
        samples=[{'type':'movie','id':'bbb'}] if key=='open-film' else next((a.get('samples',{}).get(kind,SAMPLES[kind]) for a in providers.addons if a['id']==key),SAMPLES[kind])
        for title in samples:
            try:
                async with asyncio.timeout(55):
                    streams=await providers.resolve(key,title)
                    for stream in streams[:2]:
                        try:
                            await probe(network,stream)
                            error=None
                            break
                        except Exception as exc:error=safe_error(exc)
                if error is None:break
            except Exception as exc:
                error=safe_error(exc)
                if isinstance(exc,TimeoutError) or 'browser challenge' in error:break
        save_health(store,key,kind,error)


async def refresh_health(network,providers,store,discovery):
    def state(key,**fields):
        states=store.get('candidate_states',{})
        states[key]={**states.get(key,{}),**fields};store.put('candidate_states',states)
    candidates=[c for c in discovery.candidates() if c.get('manifest')]
    registered={a['id']:a for a in providers.addons}
    # Oldest checks first: unfinished work survives restarts and cannot starve.
    candidates.sort(key=lambda c:(c.get('checked_at',''),c.get('language_priority',0) if c.get('eligibility_hint')=='queued' else 2,c['id']))
    selected=[c for c in candidates if c['id'] in registered]
    selected += [c for c in candidates if c['id'] not in registered][:100]
    sem=asyncio.Semaphore(4)
    async def check_candidate(c):
        async with sem:
            key=c['id'];manual=registered.get(key,{}).get('managed_by','manual')=='manual' and key in registered
            try:
                async with asyncio.timeout(25):raw,_=await network.read(c['manifest'])
                manifest=json.loads(raw)
                status,reason=eligibility(manifest)
                if manual and status=='language_pending':status='queued'
                if status!='queued':
                    state(key,status=status,reason=reason,checked_at=now(),supported_types=list(stream_types(manifest)))
                    if key in registered and not manual:
                        for kind in ('movie','series'):save_health(store,key,kind,reason)
                    return
                kinds=list(stream_types(manifest))
                current=next((a for a in providers.addons if a['id']==key),{})
                addon={**current,'id':key,'name':manifest['name'],'manifest':c['manifest'],'capabilities':stream_types(manifest),'managed_by':current.get('managed_by','manual' if manual else 'discovery')}
                # Prefer titles actually advertised by niche catalogs over unrelated blockbusters.
                from urllib.parse import urlsplit,urlunsplit,quote
                import re
                u=urlsplit(c['manifest']);base=u.path.removesuffix('/manifest.json')
                samples={}
                for kind in kinds:
                    catalog=next((cat for cat in manifest.get('catalogs',[]) if cat.get('type')==kind and not any(e.get('isRequired') for e in cat.get('extra',[]))),None)
                    if not catalog:continue
                    try:
                        endpoint=urlunsplit((u.scheme,u.netloc,base+'/catalog/'+kind+'/'+quote(catalog['id'],safe='')+'.json',u.query,''))
                        async with asyncio.timeout(15):raw,_=await network.read(endpoint)
                        ids=[m['id'] for m in json.loads(raw).get('metas',[]) if re.fullmatch(r'tt\d+',str(m.get('id','')))][:2]
                        if ids:samples[kind]=[{'type':kind,'id':i+(':1:1' if kind=='series' else '')} for i in ids]+SAMPLES[kind][:1]
                    except Exception:pass
                addon['samples']=samples
                providers.addons=[a for a in providers.addons if a['id']!=key]+[addon];store.put('addons',providers.addons)
                await check_provider(network,providers,store,key,kinds)
                check=store.get('health',{}).get(key,{})
                state(key,status='enabled' if check.get('enabled') else 'unavailable',reason='Verified media bytes' if check.get('enabled') else 'No verified HTTP media',checked_at=now(),supported_types=kinds)
                successes=[v['last_success'] for v in check.get('types',{}).values() if v.get('last_success')]
                if successes:state(key,verified_at=max(successes))
            except Exception as exc:
                state(key,status='unavailable',reason=safe_error(exc),checked_at=now())
                if key in registered:
                    for kind in ('movie','series'):save_health(store,key,kind,safe_error(exc))
    async def builtins():
        for key in ['open-film',*HOSTED]:
            await check_provider(network,providers,store,key,['movie'] if key=='open-film' else ['movie','series'])
    tasks=[asyncio.create_task(builtins())]+[asyncio.create_task(check_candidate(c)) for c in selected]
    selected_ids={c['id'] for c in selected}
    for addon in list(providers.addons):
        if addon['id'] not in selected_ids:
            tasks.append(asyncio.create_task(check_candidate({'id':addon['id'],'manifest':addon['manifest']})))
    try:await asyncio.gather(*tasks)
    finally:
        for task in tasks:task.cancel()
        await asyncio.gather(*tasks,return_exceptions=True)
