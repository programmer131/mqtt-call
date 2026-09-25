"""MQTT receive session and audio playback worker."""

from __future__ import annotations

import secrets
import threading
from dataclasses import dataclass

import paho.mqtt.client as mqtt

from .audio import OpusOutput
from .jitter import JitterBuffer
from .protocol import decode_audio_batch, decode_talk_claim, decrypt_packet, derive_key, topic_for

BROKERS = {
    "EMQX public": ("broker.emqx.io", 1883, False),
    "EMQX public TLS": ("broker.emqx.io", 8883, True),
    "Mosquitto public": ("test.mosquitto.org", 1883, False),
    "Mosquitto public TLS": ("test.mosquitto.org", 8883, True),
}


@dataclass
class ReceiverStatus:
    state: str = "Disconnected"
    topic: str = "call/channel/3344"
    buffer: str = "Buffering"
    queued: int = 0
    batches: int = 0
    error: str = ""
    speaker: str = ""


class Receiver:
    def __init__(self):
        self.status = ReceiverStatus()
        self._lock = threading.RLock()
        self._stop = threading.Event()
        self._client: mqtt.Client | None = None
        self._key: bytes | None = None
        self._jitter = JitterBuffer()
        self._output: OpusOutput | None = None
        self._worker: threading.Thread | None = None

    def connect(self, host: str, port: int, tls: bool, channel: str, passphrase: str,
                username: str = "", password: str = ""):
        topic = topic_for(channel)
        if not host.strip() or not 1 <= int(port) <= 65535 or not passphrase:
            raise ValueError("Enter a broker host, valid port, and encryption key")
        with self._lock:
            if self._client is not None:
                raise RuntimeError("Disconnect before changing connection settings")
            self.status = ReceiverStatus(state="Connecting", topic=topic)
            self._key = derive_key(channel.strip(), passphrase)
            self._stop.clear()
            self._jitter.clear()
        client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id="mqtt-call-linux-" + secrets.token_hex(6),
            protocol=mqtt.MQTTv311,
        )
        client.on_connect = self._on_connect
        client.on_disconnect = self._on_disconnect
        client.on_message = self._on_message
        if username:
            client.username_pw_set(username, password or None)
        if tls:
            client.tls_set()
        with self._lock:
            self._client = client
        self._worker = threading.Thread(target=self._play_loop, daemon=True)
        self._worker.start()
        threading.Thread(target=self._connect_thread, args=(client, host.strip(), int(port)), daemon=True).start()

    def _connect_thread(self, client: mqtt.Client, host: str, port: int):
        try:
            client.connect(host, port, keepalive=30)
            client.loop_start()
        except Exception as exc:
            with self._lock:
                self.status.state = "Error"
                self.status.error = str(exc)
                self._client = None

    def disconnect(self):
        with self._lock:
            client, self._client = self._client, None
            self.status.state = "Disconnected"
            self._stop.set()
        self._jitter.clear()
        if client:
            try:
                client.disconnect()
                client.loop_stop()
            except Exception:
                pass
        if self._output:
            try:
                self._output.close()
            except Exception:
                pass
            self._output = None

    def snapshot(self) -> dict:
        state, queued = self._jitter.state()
        with self._lock:
            self.status.buffer = "Playing" if state == "playing" else "Buffering"
            self.status.queued = queued
            return vars(self.status).copy()

    def _on_connect(self, client, userdata, flags, reason_code, properties):
        if reason_code.is_failure:
            with self._lock:
                self.status.state = "Error"
                self.status.error = str(reason_code)
            return
        client.subscribe(self.status.topic, qos=0)
        with self._lock:
            self.status.state = "Listening"
            self.status.error = ""

    def _on_disconnect(self, client, userdata, disconnect_flags, reason_code, properties):
        with self._lock:
            if not self._stop.is_set():
                self.status.state = "Disconnected"
                self.status.error = str(reason_code)

    def _on_message(self, client, userdata, message):
        key = self._key
        packet = decrypt_packet(message.payload, key) if key else None
        if packet is None:
            return
        if packet.kind == 1:  # CLAIM
            claim = decode_talk_claim(packet.plaintext)
            with self._lock:
                self.status.state = "Receiving"
                self.status.speaker = claim.user_name if claim and claim.user_name else ""
        elif packet.kind == 2:  # RELEASE
            self._jitter.release(packet.session_id)
            with self._lock:
                self.status.state = "Listening"
                self.status.speaker = ""
        elif packet.kind == 3:  # AUDIO
            frames = decode_audio_batch(packet.plaintext)
            if frames is None:
                return
            self._jitter.offer(packet.session_id, frames)
            with self._lock:
                self.status.batches += 1
                self.status.state = "Receiving"

    def _play_loop(self):
        while not self._stop.is_set():
            batch = self._jitter.next_batch()
            if batch is None:
                self._stop.wait(0.01)
                continue
            _, frames = batch
            try:
                if self._output is None:
                    self._output = OpusOutput()
                    self._output.start()
                for frame in frames:
                    if self._stop.is_set():
                        break
                    self._output.play_frame(frame)
            except Exception as exc:
                with self._lock:
                    self.status.state = "Audio error"
                    self.status.error = str(exc)
                self._stop.set()
