"""Report authentication wiring without displaying credentials."""
import json
from pathlib import Path
import subprocess
import base64
import urllib.error
import urllib.request

values = dict(line.split('=', 1) for line in Path('/opt/streaming/shared/media.env').read_text().splitlines() if '=' in line)
container = json.loads(subprocess.check_output(['docker', 'inspect', 'streaming-media-mediamtx-1']))[0]
environment = dict(item.split('=', 1) for item in container['Config']['Env'] if '=' in item)
assert environment.get('MTX_AUTHINTERNALUSERS_2_USER') == 'monitor'
assert environment.get('MTX_AUTHINTERNALUSERS_2_PASS') == values['MEDIAMTX_MONITOR_PASSWORD']
url = 'http://127.0.0.1:9997/v3/paths/list'
credential = base64.b64encode(('monitor:' + values['MEDIAMTX_MONITOR_PASSWORD']).encode()).decode()
with urllib.request.urlopen(urllib.request.Request(url, headers={'Authorization': 'Basic ' + credential}), timeout=5) as response:
    assert response.status == 200
    print('Authenticated API check: HTTP 200')
try:
    urllib.request.urlopen(url, timeout=5)
except urllib.error.HTTPError as error:
    assert error.code == 401
    print('Anonymous API check: HTTP 401')
else:
    raise RuntimeError('Anonymous API access unexpectedly allowed')
