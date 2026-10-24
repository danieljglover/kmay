import json
import sqlite3
from pathlib import Path
from datetime import datetime, timezone


def now():
    return datetime.now(timezone.utc).isoformat()


class Store:
    def __init__(self, path):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.execute('CREATE TABLE IF NOT EXISTS documents (key TEXT PRIMARY KEY, value TEXT NOT NULL)')
        self.db.commit()

    def get(self, key, default=None):
        row = self.db.execute('SELECT value FROM documents WHERE key=?', (key,)).fetchone()
        return json.loads(row[0]) if row else default

    def put(self, key, value):
        with self.db:
            self.db.execute('INSERT OR REPLACE INTO documents VALUES (?,?)', (key, json.dumps(value)))

    def close(self):
        self.db.close()
