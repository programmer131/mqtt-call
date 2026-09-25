"""Wire-compatible MQTT Call v1 packet parsing and decryption."""

from __future__ import annotations

import hashlib
import struct
from dataclasses import dataclass

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

VERSION = 1
HEADER_SIZE = 34
TAG_SIZE = 16
CHANNEL_DEFAULT = "3344"
KEY_DEFAULT = "PTT-DEMO-3344"
MAX_FRAMES = 50
MAX_TALKER_NAME_BYTES = 64


@dataclass(frozen=True)
class Packet:
    kind: int
    session_id: bytes
    sequence: int
    plaintext: bytes


@dataclass(frozen=True)
class TalkClaim:
    expiry_ms: int
    user_name: str | None


def decode_talk_claim(data: bytes) -> TalkClaim | None:
    if len(data) < 8:
        return None
    name_bytes = data[8:]
    if len(name_bytes) > MAX_TALKER_NAME_BYTES:
        return None
    try:
        name = name_bytes.decode("utf-8").strip() or None
    except UnicodeDecodeError:
        return None
    expiry_ms = struct.unpack_from(">q", data, 0)[0]
    return TalkClaim(expiry_ms, name)


def topic_for(channel: str) -> str:
    value = channel.strip()
    if not value.isascii() or not value.isdigit() or not 1 <= len(value) <= 16:
        raise ValueError("Channel must contain 1 to 16 ASCII digits")
    return f"call/channel/{value}"


def derive_key(channel: str, passphrase: str) -> bytes:
    return hashlib.pbkdf2_hmac(
        "sha256",
        passphrase.encode("utf-8"),
        f"mqtt-ptt-v1/{channel}".encode("utf-8"),
        100_000,
        dklen=32,
    )


def decrypt_packet(data: bytes, key: bytes) -> Packet | None:
    if len(data) < HEADER_SIZE + TAG_SIZE or data[0] != VERSION:
        return None
    kind = data[1]
    if kind not in (1, 2, 3):
        return None
    session = data[2:18]
    sequence = struct.unpack_from(">I", data, 18)[0]
    nonce = data[22:34]
    try:
        plaintext = AESGCM(key).decrypt(nonce, data[HEADER_SIZE:], data[:HEADER_SIZE])
    except Exception:
        return None
    return Packet(kind, session, sequence, plaintext)


def decode_audio_batch(data: bytes) -> list[bytes] | None:
    if not data:
        return None
    count = data[0]
    if not 1 <= count <= MAX_FRAMES:
        return None
    pos = 1
    frames: list[bytes] = []
    for _ in range(count):
        if len(data) - pos < 2:
            return None
        size = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        if size == 0 or size > len(data) - pos:
            return None
        frames.append(data[pos : pos + size])
        pos += size
    return frames if pos == len(data) else None
