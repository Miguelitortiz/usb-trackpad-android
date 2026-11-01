import socket
import unittest

from usb_adb_receiver import decode_frame, decode_report, recv_exact


class ReceiverProtocolTest(unittest.TestCase):
    def test_recv_exact_handles_fragmented_report(self):
        left, right = socket.socketpair()
        try:
            left.sendall(b"\x01")
            left.sendall(b"\xFE\x02\x00")
            self.assertEqual(recv_exact(right, 4), b"\x01\xFE\x02\x00")
        finally:
            left.close()
            right.close()

    def test_recv_exact_returns_none_on_disconnect(self):
        left, right = socket.socketpair()
        try:
            left.close()
            self.assertIsNone(recv_exact(right, 4))
        finally:
            right.close()

    def test_decode_report_rejects_wrong_size(self):
        with self.assertRaises(ValueError):
            decode_report(b"\x00")

    def test_decode_frame_supports_semantic_action(self):
        left, right = socket.socketpair()
        try:
            left.sendall(b"\xff\x04\x00\x2a\x00")
            self.assertEqual(decode_frame(right), ("semantic", 4, 42))
        finally:
            left.close()
            right.close()

    def test_decode_frame_supports_legacy_report(self):
        left, right = socket.socketpair()
        try:
            left.sendall(b"\x01\x0a\xf6\x01")  # button 1, dx 10, dy -10, wheel 1
            self.assertEqual(decode_frame(right), ("legacy", 1, 10, -10, 1))
        finally:
            left.close()
            right.close()


if __name__ == "__main__":
    unittest.main()
