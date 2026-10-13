#!/usr/bin/env python3
"""Invoked only for an explicit in-game message. REQUEST is injected as JSON by the bridge."""
import os, json, subprocess, tempfile, shutil, re
from pathlib import Path


def runtime_environment(home):
    def version(p):
        return tuple(int(v) for v in re.findall(r'\d+', p.parent.name))
    nodes = sorted((home / '.nvm/versions/node').glob('*/bin'), key=version, reverse=True)
    bins = [str(p) for p in nodes] + [str(home/'.local/bin'), str(home/'.npm-global/bin'), '/opt/homebrew/bin', '/usr/local/bin', '/usr/bin', '/bin']
    return {**os.environ, 'PATH': os.pathsep.join(bins) + os.pathsep + os.environ.get('PATH',''), 'NO_COLOR':'1'}


def prepare_request(request, home=None):
    home = home or Path.home()
    source = request['source']
    if source not in ('OpenClaw','Hermes'):
        raise ValueError('Unsupported main-agent runtime')
    text = request.get('text','')
    if not isinstance(text,str) or not text.strip() or len(text)>32000:
        raise ValueError('Message must contain 1–32000 characters')
    env = runtime_environment(home)
    binary = shutil.which('openclaw' if source=='OpenClaw' else os.environ.get('HQ_HERMES_BIN','hermes'), path=env['PATH'])
    if not binary:
        raise ValueError('Agent CLI not found on this machine')
    if source=='OpenClaw':
        # Explicitly the main agent. Never --deliver to Discord, email, or another channel.
        args = [binary,'agent','--agent','main','--session-key','agent:main:headquarters','--json','--timeout','600','--message-file']
    else:
        # Named conversation preserves HQ continuity while using the existing Hermes home,
        # identity, configured models, tools, skills, and memory. Never resume an arbitrary latest session.
        args = [binary,'chat','--continue','Headquarters','--create-if-missing','--oneshot','--quiet','--no-restore-cwd','--query-file']
    return args, env


def run(request):
    args, env = prepare_request(request)
    if request.get('check'):
        return {'ok':True,'source':request['source'],'executable':args[0],'conversation':'Headquarters','mainAgent':'main'}
    with tempfile.TemporaryDirectory(prefix='hq-message-') as directory:
        message = Path(directory)/'message.txt'
        context = ""
        if request.get('coordinator') and request['source']=='Hermes':
            context = """CrewCraft operating context:
You are the user's configured coordinator in CrewCraft. You retain your existing identity, runtime, tools, model, memory and host. No task board, repository, credentials or external services are implied by this role. Use only integrations the user has configured. Coordinate work by stable agent identity and report evidence. Builder is a separate local planner; the construction crew are game-owned NPCs. Minecraft validates and executes approved block proposals. Do not claim to have changed the world or installed game features without tool evidence. Treat game software changes and world construction as separate operations. Act only on the user's request below.

User request:
"""
        message.write_text(context + request['text']); message.chmod(0o600)
        try:
            result = subprocess.run(args+[str(message)],env=env,cwd=Path(os.environ.get('HQ_HERMES_CWD',str(Path.home()))) if request['source']=='Hermes' else Path.home(),stdin=subprocess.DEVNULL,capture_output=True,text=True,timeout=620)
        except subprocess.TimeoutExpired:
            return {'ok':False,'error':'Response timed out. The agent may still be working; check its original app before resending. No automatic retry was made.'}
    if result.returncode:
        diagnostic=(result.stdout+'\n'+getattr(result,'stderr','')).lower()
        if any(marker in diagnostic for marker in ('invalid_grant','refresh token expired','terminally invalid','hermes auth add anthropic')):
            return {'ok':False,'error':'Hermes Anthropic login expired. On this agent’s machine, run: hermes auth add anthropic. Complete sign-in, then send your message again.'}
        return {'ok':False,'error':f'Agent CLI exited with code {result.returncode}. Check the original app for authentication, permission prompts, or run details. No automatic retry was made.'}
    if request['source']=='OpenClaw':
        raw=result.stdout.strip()
        try:
            # Some plugin versions print startup notices before the JSON envelope.
            data=None
            for match in re.finditer(r'(?m)^\s*\{',raw):
                try:
                    value,end=json.JSONDecoder().raw_decode(raw[match.start():].lstrip())
                    if isinstance(value,dict): data=value; break
                except ValueError:pass
            if not data:raise ValueError('Missing JSON response')
            payload=data.get('result',data)
            texts=[p.get('text','') for p in payload.get('payloads',[]) if isinstance(p,dict)]
            reply='\n\n'.join(t for t in texts if isinstance(t,str) and t.strip())
            if not reply:
                return {'ok':False,'error':'The gateway returned no readable reply. Check the main agent in its original app; no automatic retry was made.'}
        except (ValueError,AttributeError):
            return {'ok':False,'error':'Could not decode the gateway reply. Check the original app; no automatic retry was made.'}
    else:
        reply=re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]','',result.stdout).strip()
        if not reply:return {'ok':False,'error':'Hermes returned no text. Check the original app.'}
    return {'ok':True,'reply':reply[:64000]}

if __name__=='__main__':
    try:
        print(json.dumps(run(REQUEST)))
    except Exception as error:
        print(json.dumps({'ok':False,'error':str(error)}))
