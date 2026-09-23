import struct
import unittest

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from mqtt_call_linux.protocol import (
    HEADER_SIZE,
    KEY_DEFAULT,
    decode_audio_batch,
    decrypt_packet,
    derive_key,
    topic_for,
)


class ProtocolTest(unittest.TestCase):
    def test_android_default_key_and_topic(self):
        self.assertEqual(topic_for(" 3344 "), "call/channel/3344")
        self.assertEqual(len(derive_key("3344", KEY_DEFAULT)), 32)

    def test_topic_rejects_invalid_channel(self):
        for channel in ("", "a34", "1234567890123", "３３４４"):
            with self.subTest(channel=channel), self.assertRaises(ValueError):
                topic_for(channel)

    def test_decrypts_android_wire_envelope_and_checks_header_authentication(self):
        channel, phrase = "3344", KEY_DEFAULT
        key = derive_key(channel, phrase)
        nonce, session = bytes(range(12)), bytes(range(16))
        sequence = 0x01020304
        header = bytes([1, 3]) + session + struct.pack(">I", sequence) + nonce
        plaintext = b"audio-test"
        payload = header + AESGCM(key).encrypt(nonce, plaintext, header)

        packet = decrypt_packet(payload, key)
        self.assertEqual(HEADER_SIZE, 34)
        self.assertEqual((packet.kind, packet.session_id, packet.sequence, packet.plaintext),
                         (3, session, sequence, plaintext))
        damaged = bytearray(payload)
        damaged[2] ^= 1
        self.assertIsNone(decrypt_packet(bytes(damaged), key))
        self.assertIsNone(decrypt_packet(payload, derive_key(channel, "wrong")))

    def test_audio_batch_uses_unsigned_big_endian_lengths(self):
        first, second = b"opus-one", b"opus-two"
        blob = bytes([2]) + struct.pack(">H", len(first)) + first + struct.pack(">H", len(second)) + second
        self.assertEqual(decode_audio_batch(blob), [first, second])
        self.assertIsNone(decode_audio_batch(blob + b"extra"))
        self.assertIsNone(decode_audio_batch(bytes([1, 0, 4]) + b"x"))

    def test_internet_batch_can_contain_twenty_frames(self):
        frames = [bytes([index]) for index in range(20)]
        blob = bytes([len(frames)]) + b"".join(
            struct.pack(">H", len(frame)) + frame for frame in frames
        )

        self.assertEqual(decode_audio_batch(blob), frames)


if __name__ == "__main__":
    unittest.main()
