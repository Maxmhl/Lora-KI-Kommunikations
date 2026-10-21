"""Erzeugt protocol/test-vectors.json aus der Python-Referenzimplementierung."""
import hashlib
import json
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "bridge"))
from lkbridge import base91, codec  # noqa: E402
from lkbridge.airtime import RadioParams, airtime_s, mesh_packet_bytes, subband_limits  # noqa: E402

# Wörterbuch: Bridge-Datei ist die Quelle, die Kotlin-Ressource wird synchron gehalten
DICT_SRC = Path(__file__).resolve().parents[1] / "bridge" / "lkbridge" / "dictionary.txt"
DICT_DST = Path(__file__).resolve().parents[1] / "core/src/main/resources/de/loraki/core/dictionary.txt"
shutil.copyfile(DICT_SRC, DST := DICT_DST)

chunk = bytes(range(codec.FRAGMENT_BYTES))
german = "Die Hauptstadt von Frankreich ist Paris. Das ist eine gute Frage. Hier ist eine kurze Zusammenfassung:".encode()
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
    "base91": [{"data": d.hex(), "text": base91.encode(d)} for d in
               (b"", b"a", b"hello world", bytes(range(256)), b"\x00" * 30, b"\xff" * 30, b"\x58" * 40)],
    "dictionary_sha256": hashlib.sha256(DICT_SRC.read_bytes()).hexdigest(),
    "non_frames": ["hallo", "~", "~Dxx", "~DAAAAAA!!!!"],
    "envelopes": [
        {"kind": codec.KIND_TEXT, "conv": 3, "payload": b"Hallo".hex(),
         "raw": codec.build_envelope(codec.KIND_TEXT, 3, b"Hallo").hex()},
        {"kind": codec.KIND_REPLY, "conv": 0, "payload": long_text.hex(),
         "raw": codec.build_envelope(codec.KIND_REPLY, 0, long_text).hex()},
        {"kind": codec.KIND_REPLY, "conv": 7, "payload": german.hex(),
         "raw": codec.build_envelope(codec.KIND_REPLY, 7, german).hex()},
        {"kind": codec.KIND_SET_MODEL, "conv": 2, "payload": b"llama3.1".hex(),
         "raw": codec.build_envelope(codec.KIND_SET_MODEL, 2, b"llama3.1").hex()},
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
    "packet_bytes": [[n, mesh_packet_bytes(n)] for n in (0, 10, 11, 154, 155)],
    "subbands": [[f, *subband_limits(f)] for f in (865.5, 868.1, 868.9, 869.3, 869.618, 869.8, 915.0)],
    "constants": {"fragment_bytes": codec.FRAGMENT_BYTES, "text_max": codec.TEXT_MAX,
                  "window": codec.WINDOW, "max_missing": codec.MAX_MISSING,
                  "kinds": {"text": codec.KIND_TEXT, "image": codec.KIND_IMAGE, "file": codec.KIND_FILE,
                            "reply": codec.KIND_REPLY, "status": codec.KIND_STATUS, "reset": codec.KIND_RESET,
                            "model_list_req": codec.KIND_MODEL_LIST_REQ, "model_list": codec.KIND_MODEL_LIST,
                            "set_model": codec.KIND_SET_MODEL, "set_opts": codec.KIND_SET_OPTS,
                            "nomad_req": codec.KIND_NOMAD_REQ, "nomad_resp": codec.KIND_NOMAD_RESP}},
}
out = Path(__file__).with_name("test-vectors.json")
out.write_text(json.dumps(v, indent=1, ensure_ascii=False) + "\n")
print("geschrieben:", out)
