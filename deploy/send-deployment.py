"""GitHub runner: send data to the forced SSH command, never upload executable files."""
import json
import os
from pathlib import Path
import subprocess

component = os.environ['COMPONENT']
if component == 'backend':
    request = {'image': os.environ['APP_IMAGE'], 'registry_user': os.environ['REGISTRY_USER'],
               'registry_token': os.environ['REGISTRY_TOKEN']}
elif component == 'media':
    request = {'image': 'bluenviron/mediamtx:1.21.4',
               'config': Path('deploy/media/mediamtx.yml').read_text()}
else:
    raise SystemExit('Invalid component')
subprocess.run(['ssh', '-T', '-i', str(Path.home() / '.ssh/deploy'),
                '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes',
                '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=10',
                'streaming-deploy@' + os.environ['DEPLOY_HOST']],
               input=json.dumps(request), text=True, check=True)
