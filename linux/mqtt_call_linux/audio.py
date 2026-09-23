"""Decode 16 kHz mono Opus frames and stream PCM to ALSA."""

from __future__ import annotations

import ctypes
import ctypes.util
import shutil
import subprocess


class OpusOutput:
    SAMPLE_RATE = 16_000
    FRAME_SAMPLES = 320

    def __init__(self):
        library = ctypes.util.find_library("opus")
        if not library:
            raise RuntimeError("libopus is missing; install libopus0")
        if not shutil.which("aplay"):
            raise RuntimeError("aplay is missing; install alsa-utils")
        self._opus = ctypes.CDLL(library)
        self._opus.opus_decoder_create.restype = ctypes.c_void_p
        self._opus.opus_decoder_create.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
        self._opus.opus_decode.restype = ctypes.c_int
        self._opus.opus_decode.argtypes = [
            ctypes.c_void_p, ctypes.POINTER(ctypes.c_uchar), ctypes.c_int,
            ctypes.POINTER(ctypes.c_int16), ctypes.c_int, ctypes.c_int,
        ]
        self._opus.opus_decoder_destroy.argtypes = [ctypes.c_void_p]
        error = ctypes.c_int()
        self._decoder = self._opus.opus_decoder_create(self.SAMPLE_RATE, 1, ctypes.byref(error))
        if not self._decoder or error.value != 0:
            raise RuntimeError(f"libopus decoder initialization failed ({error.value})")
        self._player: subprocess.Popen | None = None

    def start(self):
        self._player = subprocess.Popen(
            ["aplay", "-q", "-f", "S16_LE", "-c", "1", "-r", str(self.SAMPLE_RATE)],
            stdin=subprocess.PIPE, stderr=subprocess.DEVNULL,
        )

    def play_frame(self, packet: bytes):
        source = (ctypes.c_uchar * len(packet)).from_buffer_copy(packet)
        pcm = (ctypes.c_int16 * self.FRAME_SAMPLES)()
        samples = self._opus.opus_decode(self._decoder, source, len(packet), pcm, self.FRAME_SAMPLES, 0)
        if samples < 0:
            raise RuntimeError(f"Opus decode failed ({samples})")
        if self._player is None or self._player.stdin is None:
            self.start()
        assert self._player is not None and self._player.stdin is not None
        self._player.stdin.write(bytes(memoryview(pcm).cast("B")[: samples * 2]))

    def close(self):
        if self._player is not None:
            if self._player.stdin and not self._player.stdin.closed:
                self._player.stdin.close()
            try:
                self._player.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self._player.terminate()
                self._player.wait(timeout=1)
            self._player = None
        if self._decoder:
            self._opus.opus_decoder_destroy(self._decoder)
            self._decoder = None

