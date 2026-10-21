"""Erzeugt protocol/test-vectors.json aus der Python-Referenzimplementierung."""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "bridge"))
from lkbridge import codec  # noqa: E402
from lkbridge.airtime import RadioParams, airtime_s, mesh_packet_bytes, subband_limits  # noqa: E402

chunk = bytes(range(codec.FRAGMENT_BYTES))
long_text = ("Das ist ein längerer Text, der sich gut komprimieren lässt. " * 8).encode()
v = {
    "nums": [[n, codec.enc_num(n)] for n in (0, 1, 63, 64, 100, 4095)],
    "data_frames": [
        {"id": 77, "idx": 5, "total": 40, "poll": True, "chunk": chunk.hex(),
         "text": codec.format_data(77, 5, 40, chunk, True)},
        {"id": 0, "idx": 0, "total": 1, "poll": False, "chunk": b"hi".hex(),
         "text": codec.format_data(0, 0, 1, b"hi", False)},
    ],
    "acks": [
        {"id": 9, "hi": 12, "missing": [3, 7], "text": codec.format_ack(9, 12, [3, 7])},
        {"id": 4095, "hi": 40, "missing": [], "text": codec.format_ack(4095, 40, [])},
    ],
    "aborts": [{"id": 5, "text": codec.format_abort(5)}],
    "non_frames": ["hallo", "~", "~Dxx", "~DAAAAAA!!!!"],
    "envelopes": [
        {"kind": codec.KIND_TEXT, "conv": 3, "payload": b"Hallo".hex(),
         "raw": codec.build_envelope(codec.KIND_TEXT, 3, b"Hallo").hex()},
        {"kind": codec.KIND_REPLY, "conv": 0, "payload": long_text.hex(),
         "raw": codec.build_envelope(codec.KIND_REPLY, 0, long_text).hex()},
        {"kind": codec.KIND_FILE, "conv": 1, "payload": codec.pack_file("a.txt", "text/plain", b"xyz").hex(),
         "raw": codec.build_envelope(codec.KIND_FILE, 1, codec.pack_file("a.txt", "text/plain", b"xyz")).hex()},
    ],
    "airtime": [
        {"freq": 869.618, "bw": 62.5, "sf": 8, "cr": 8, "preamble": 16, "bytes": 160,
         "seconds": airtime_s(160, RadioParams(869.618, 62.5, 8, 8, 16))},
        {"freq": 869.618, "bw": 62.5, "sf": 8, "cr": 5, "preamble": 16, "bytes": 30,
         "seconds": airtime_s(30, RadioParams(869.618, 62.5, 8, 5, 16))},
        {"freq": 868.1, "bw": 125.0, "sf": 12, "cr": 5, "preamble": 8, "bytes": 100,
         "seconds": airtime_s(100, RadioParams(868.1, 125.0, 12, 5, 8))},
    ],
    "packet_bytes": [[n, mesh_packet_bytes(n)] for n in (0, 11, 12, 140)],
    "subbands": [[f, *subband_limits(f)] for f in (865.5, 868.1, 868.9, 869.3, 869.618, 869.8, 915.0)],
    "constants": {"fragment_bytes": codec.FRAGMENT_BYTES, "body_chars": codec.BODY_CHARS,
                  "window": codec.WINDOW, "max_missing": codec.MAX_MISSING},
}
out = Path(__file__).with_name("test-vectors.json")
out.write_text(json.dumps(v, indent=1, ensure_ascii=False) + "\n")
print("geschrieben:", out)
