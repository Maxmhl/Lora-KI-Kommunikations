import os

import pytest

from lkbridge import codec


def test_num_roundtrip():
    for n in (0, 1, 63, 64, 4095):
        assert codec.dec_num(codec.enc_num(n)) == n
    with pytest.raises(codec.ProtocolError):
        codec.enc_num(4096)


def test_envelope_roundtrip_and_crc():
    raw = codec.build_envelope(codec.KIND_TEXT, 3, ("Hallo Welt " * 50).encode())
    assert len(raw) < 200  # komprimiert
    e = codec.parse_envelope(raw)
    assert (e.kind, e.conv) == (codec.KIND_TEXT, 3) and e.payload.startswith(b"Hallo Welt")
    bad = bytearray(raw)
    bad[-1] ^= 1
    with pytest.raises(codec.ProtocolError):
        codec.parse_envelope(bytes(bad))


def test_incompressible_not_flagged():
    raw = codec.build_envelope(codec.KIND_FILE, 0, os.urandom(300))
    assert raw[4] == 0


def test_frame_roundtrip_and_size():
    chunk = os.urandom(codec.FRAGMENT_BYTES)
    t = codec.format_data(77, 5, 40, chunk, poll=True)
    assert len(t) == codec.HEADER_CHARS + codec.BODY_CHARS == 140
    f = codec.parse_frame(t)
    assert (f.type, f.msg_id, f.idx, f.total, f.body) == (codec.T_POLL, 77, 5, 40, chunk)
    a = codec.parse_frame(codec.format_ack(9, 12, [3, 7]))
    assert (a.type, a.msg_id, a.hi, a.missing) == (codec.T_ACK, 9, 12, [3, 7])
    assert codec.parse_frame(codec.format_abort(5)).type == codec.T_ABORT


def test_non_protocol_text():
    for t in ("hallo", "~", "~Dxx", "~D" + "AAAAAA" + "!!!!", ""):
        assert codec.parse_frame(t) is None


def test_file_pack():
    assert codec.unpack_file(codec.pack_file("a.txt", "text/plain", b"xyz")) == ("a.txt", "text/plain", b"xyz")
