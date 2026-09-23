"""Browser UI for the Python Linux listener. Runs only on localhost."""

from __future__ import annotations

import json
import threading
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .receiver import Receiver

receiver = Receiver()
PAGE = r'''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>MQTT Call</title>
<style>
:root{font-family:system-ui,sans-serif;color:#e8edf5;background:#0b1119}*{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;padding:24px;background:radial-gradient(ellipse at 50% 0,#18314b,transparent 55%),#0b1119}main{width:min(100%,480px);background:#111c28;border:1px solid #26384b;border-radius:23px;padding:28px;box-shadow:0 24px 90px #0008}.brand{display:flex;gap:13px;align-items:center}.mark{width:44px;height:44px;border-radius:14px;background:#49d7b0;color:#092119;display:grid;place-items:center;font-size:23px;font-weight:850}h1{font-size:22px;margin:0}.sub,.detail,.hint,.meter{color:#91a5b8}.sub{font-size:13px}.status{margin:25px 0 18px;background:#0c151e;border:1px solid #263747;padding:16px;border-radius:15px}.status b{display:block}.detail{font-size:12px;margin-top:4px}label{display:block;color:#98adbf;text-transform:uppercase;font-size:11px;font-weight:700;letter-spacing:.07em;margin:15px 0 6px}input,select{width:100%;background:#0c151e;color:#edf4fb;border:1px solid #304355;border-radius:10px;padding:11px;font-size:14px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:10px}.buttons{display:flex;gap:9px;margin-top:22px}button{border:0;border-radius:10px;padding:13px;font-weight:750;cursor:pointer}.connect{flex:1;background:#49d7b0;color:#092119}.disconnect{background:#253443;color:#dce7ef}button:disabled{opacity:.45}.hint{font-size:12px;line-height:1.55}.meter{font-size:12px;border-top:1px solid #263747;padding-top:15px;display:flex;justify-content:space-between}.error{color:#ff9e92;font-size:12px;min-height:16px}</style>
<main><div class="brand"><div class="mark">◖</div><div><h1>MQTT Call</h1><div class="sub">Linux listener · encrypted voice</div></div></div><section class="status"><b id="state">Disconnected</b><div id="topic" class="detail">call/channel/3344</div></section>
<label>Broker</label><select id="broker"><option value="emqx">EMQX public · MQTT</option><option value="emqx_tls">EMQX public · TLS</option><option value="mosq">Mosquitto public · MQTT</option><option value="mosq_tls">Mosquitto public · TLS</option><option value="custom">Custom broker</option></select>
<div id="custom" style="display:none"><div class="grid"><div><label>Host</label><input id="host" placeholder="mqtt.example.com"></div><div><label>Port</label><input id="port" type="number" value="1883"></div></div><label>TLS</label><select id="tls"><option value="false">Off</option><option value="true">On</option></select><div class="grid"><div><label>Username</label><input id="username"></div><div><label>Password</label><input id="password" type="password"></div></div></div>
<div class="grid"><div><label>Channel</label><input id="channel" value="3344" inputmode="numeric"></div><div><label>Encryption key</label><input id="key" value="PTT-DEMO-3344" autocomplete="off"></div></div><div class="buttons"><button id="connect" class="connect" onclick="connectNow()">Connect &amp; listen</button><button id="disconnect" class="disconnect" disabled onclick="disconnectNow()">Disconnect</button></div><div id="error" class="error"></div><div class="meter"><span>Buffer <b id="buffer">Buffering</b></span><span id="batches">0 packets</span></div><p class="hint">Public test brokers may be unavailable. Audio is encrypted; broker and channel metadata remain visible. Playback starts after three 200 ms packets arrive.</p></main>
<script>
const $=x=>document.getElementById(x),profiles={emqx:['broker.emqx.io',1883,false],emqx_tls:['broker.emqx.io',8883,true],mosq:['test.mosquitto.org',1883,false],mosq_tls:['test.mosquitto.org',8883,true]};$("broker").onchange=()=>$("custom").style.display=$("broker").value==="custom"?"block":"none";
async function connectNow(){let b=$("broker").value,c=b==="custom",p=c?[$("host").value,+$("port").value,$("tls").value==="true"]:profiles[b];$("error").textContent="";try{let r=await fetch('/connect',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({host:p[0],port:p[1],tls:p[2],channel:$("channel").value,key:$("key").value,username:c?$("username").value:"",password:c?$("password").value:""})}),x=await r.json();if(!r.ok)throw Error(x.error);refresh()}catch(e){$("error").textContent=e.message}}
async function disconnectNow(){await fetch('/disconnect',{method:'POST'});refresh()}async function refresh(){try{let s=await(await fetch('/status')).json();$("state").textContent=s.state;$("topic").textContent=s.topic;$("buffer").textContent=s.buffer+" · "+s.queued+" queued";$("batches").textContent=s.batches+" packets";$("error").textContent=s.error||"";let a=!['Disconnected','Error','Audio error'].includes(s.state);$("connect").disabled=a;$("disconnect").disabled=!a}catch{}}setInterval(refresh,500);refresh();
</script></html>'''


class Handler(BaseHTTPRequestHandler):
    def _send(self, status: int, value, content_type="application/json"):
        body = value if isinstance(value, bytes) else json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/":
            self._send(200, PAGE.encode(), "text/html; charset=utf-8")
        elif self.path == "/status":
            self._send(200, receiver.snapshot())
        else:
            self._send(404, {"error": "Not found"})

    def do_POST(self):
        try:
            data = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
            if self.path == "/connect":
                receiver.connect(str(data["host"]), int(data["port"]), bool(data["tls"]),
                    str(data["channel"]), str(data["key"]), str(data.get("username", "")), str(data.get("password", "")))
                self._send(200, {"ok": True})
            elif self.path == "/disconnect":
                receiver.disconnect()
                self._send(200, {"ok": True})
            else:
                self._send(404, {"error": "Not found"})
        except Exception as exc:
            self._send(400, {"error": str(exc)})

    def log_message(self, fmt, *args):
        pass


def main():
    server = ThreadingHTTPServer(("127.0.0.1", 8765), Handler)
    print("MQTT Call browser listener: http://127.0.0.1:8765")
    threading.Timer(0.5, lambda: webbrowser.open("http://127.0.0.1:8765")).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        receiver.disconnect()
        server.server_close()


if __name__ == "__main__":
    main()
