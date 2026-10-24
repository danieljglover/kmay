import asyncio
import json
import pytest
from comparison import Comparisons, measure


class Providers:
    def __init__(self):
        self.cancelled = False

    async def resolve(self, key, title):
        if key == 'slow':
            try:
                await asyncio.sleep(60)
            finally:
                self.cancelled = True
        return [{'url': 'https://secret.example/'+key, 'name': key}]


class Relay:
    def create(self, key, stream, base):
        return {'provider_id': key, 'url': base+'/relay/'+key}


async def sample(network, stream):
    if stream['name'] == 'bad':
        raise ValueError('Broken segment')
    score = 20 if stream['name'] == 'fast' else 5
    return {'score': score, 'sample_mbps': score, 'response_ms': 100}


def candidates(*names):
    return [{'id': name, 'name': name} for name in names]


def test_deadline_ranking_redaction_and_fallback():
    async def run():
        providers = Providers()
        jobs = Comparisons(None, providers, Relay(), budget=.05, measure_fn=sample)
        key = jobs.start({}, candidates('good', 'fast', 'bad', 'slow'), 'http://proxy')
        await jobs.jobs[key]['task']
        result = jobs.snapshot(key)
        assert result['state'] == 'complete'
        assert [s['provider_id'] for s in result['streams']] == ['fast', 'good']
        assert result['providers'][-1]['state'] == 'timed_out'
        assert result['providers'][2]['state'] == 'unavailable'
        assert providers.cancelled
        assert 'secret.example' not in json.dumps(result)
        assert jobs.snapshot(key)['streams'] is result['streams']
    asyncio.run(run())


@pytest.mark.parametrize('immediate', [False, True])
def test_cancel_never_returns_playback(immediate):
    async def run():
        jobs = Comparisons(None, Providers(), Relay(), measure_fn=sample)
        key = jobs.start({}, candidates('slow'), 'http://proxy')
        if not immediate:
            await asyncio.sleep(.01)
        result = await jobs.stop(key, cancel=True)
        assert result['state'] == 'cancelled' and result['streams'] == []
        assert jobs.jobs[key]['task'].done()
    asyncio.run(run())


def test_finish_uses_partial_result_and_cancels_pending_work():
    async def run():
        providers = Providers()
        jobs = Comparisons(None, providers, Relay(), measure_fn=sample)
        key = jobs.start({}, candidates('fast', 'slow'), 'http://proxy')
        await asyncio.sleep(.01)
        assert jobs.snapshot(key)['working'] == 1
        result = await jobs.stop(key)
        assert result['state'] == 'complete' and result['streams'][0]['provider_id'] == 'fast'
        assert providers.cancelled
    asyncio.run(run())


def test_empty_and_concurrency_limit():
    async def run():
        jobs = Comparisons(None, Providers(), Relay(), measure_fn=sample)
        key = jobs.start({}, [], 'http://proxy')
        await jobs.jobs[key]['task']
        assert jobs.snapshot(key)['state'] == 'complete'
        assert jobs.snapshot(key)['streams'] == []
        jobs.start({}, candidates('slow'), 'http://proxy')
        jobs.start({}, candidates('slow'), 'http://proxy')
        with pytest.raises(ValueError):
            jobs.start({}, candidates('slow'), 'http://proxy')
        await jobs.close()
    asyncio.run(run())


def test_measure_limits_bytes_and_closes_failed_response(monkeypatch):
    import comparison
    class Response:
        status = 206
        headers = {'Content-Type': 'video/mp2t'}
        def __init__(self):self.content=self;self.read_bytes=0;self.closed=False
        async def read(self, count):self.read_bytes+=count;return b'x'*count
        def close(self):self.closed=True
    class Network:
        def __init__(self):self.responses=[]
        async def open(self, url, headers):
            assert headers['Range']=='bytes=0-524287'
            r=Response();self.responses.append(r);return r
    async def fake_probe(network, stream):return [stream['url']]
    monkeypatch.setattr(comparison, 'probe', fake_probe)
    async def run():
        network=Network()
        result=await measure(network, {'url':'https://media.example/video'})
        assert result['score']>0 and len(network.responses)==2
        assert all(r.read_bytes==524288 and r.closed for r in network.responses)
        Response.headers={'Content-Type':'text/html'}
        with pytest.raises(ValueError):await measure(network, {'url':'https://media.example/video'})
        assert network.responses[-1].closed
    asyncio.run(run())
