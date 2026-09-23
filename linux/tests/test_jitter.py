import unittest

from mqtt_call_linux.jitter import JitterBuffer


class JitterTest(unittest.TestCase):
    def test_starts_after_three_packets_and_rebuffers_after_underrun(self):
        jitter = JitterBuffer(startup_batches=3)
        session = b"talk-session"
        jitter.offer(session, [b"one"])
        jitter.offer(session, [b"two"])
        self.assertEqual(jitter.state()[0], "buffering")
        jitter.offer(session, [b"three"])
        self.assertEqual(jitter.state()[0], "playing")
        self.assertEqual(jitter.next_batch()[1], [b"one"])
        self.assertEqual(jitter.next_batch()[1], [b"two"])
        self.assertEqual(jitter.next_batch()[1], [b"three"])
        self.assertIsNone(jitter.next_batch())
        self.assertEqual(jitter.state()[0], "buffering")
        jitter.offer(session, [b"four"])
        jitter.offer(session, [b"five"])
        self.assertEqual(jitter.state()[0], "buffering")
        jitter.offer(session, [b"six"])
        self.assertEqual(jitter.state()[0], "playing")

    def test_release_flushes_remaining_packets_without_waiting_for_three(self):
        jitter = JitterBuffer(startup_batches=3)
        session = b"talk-session"
        jitter.offer(session, [b"last"])
        jitter.release(session)
        self.assertEqual(jitter.next_batch()[1], [b"last"])
        self.assertIsNone(jitter.next_batch())
        self.assertEqual(jitter.state()[1], 0)


if __name__ == "__main__":
    unittest.main()
