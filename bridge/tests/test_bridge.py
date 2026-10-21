import asyncio

from lkbridge import codec
from lkbridge.airtime import RadioParams
from lkbridge.limiter import DutyCycleLimiter
from lkbridge.server import Bridge
from lkbridge.transfer import Receiver, Sender

from .test_transfer import VClock

import tempfile
from pathlib import Path

PEER = "aabbccddeeff"


class FakeTransport:
    def __init__(self):
        self.out = []
        self.on_text = None

    async def send_text(self, peer, text):
        self.out.append(text)


class FakeLLM:
    def __init__(self):
        self.calls = []
        self.models = [("llama3.1", False), ("llava", True)]

    async def list_models(self):
        return self.models

    async def chat(self, messages, model=None, images=None):
        self.calls.append((messages, model, images))
        return "Antwort: " + messages[-1]["content"][-20:]


def make(cfg=None):
    c = VClock()
    tr, llm = FakeTransport(), FakeLLM()
    lim = DutyCycleLimiter(0.10, clock=c, sleep=c.sleep)
    sender = Sender(tr.send_text, lim, RadioParams(), ack_timeout_s=0.5)
    cfg = cfg or {"access": {"allowed_peers": [PEER]}}
    cfg.setdefault("model_state_file", str(Path(tempfile.mkdtemp()) / "models.json"))
    cfg.setdefault("llm", {}).setdefault("model", "llama3.1")
    b = Bridge(cfg, tr, llm, sender, Receiver())
    b.start()
    return b, tr, llm


async def feed(b, env, msg_id=1):
    chunks = codec.fragment(env)
    for i, ch in enumerate(chunks):
        await b.on_text(PEER, codec.format_data(msg_id, i, len(chunks), ch, i == len(chunks) - 1))


async def settle():
    for _ in range(20):
        await asyncio.sleep(0)


async def test_unknown_peer_ignored():
    b, tr, llm = make({"access": {"allowed_peers": ["112233445566"]}})
    await feed(b, codec.build_envelope(codec.KIND_TEXT, 0, b"hi"))
    await settle()
    assert not tr.out and not llm.calls


async def test_text_gets_ack_and_reply():
    b, tr, llm = make()
    await feed(b, codec.build_envelope(codec.KIND_TEXT, 0, "Wie spät ist es?".encode()))
    await settle()
    assert llm.calls and llm.calls[0][0][-1]["content"] == "Wie spät ist es?"
    kinds = [codec.parse_frame(t).type for t in tr.out]
    assert codec.T_ACK in kinds and codec.T_POLL in kinds  # ACK + Antwort-Fragment


async def test_file_is_staged_and_used():
    b, tr, llm = make()
    att = codec.build_envelope(codec.KIND_FILE, 0, codec.pack_file("n.txt", "text/plain", b"Geheimtext 42"))
    await feed(b, att, 1)
    await feed(b, codec.build_envelope(codec.KIND_TEXT, 0, b"Fasse zusammen"), 2)
    await settle()
    prompt = llm.calls[0][0][-1]["content"]
    assert "Geheimtext 42" in prompt and prompt.endswith("Fasse zusammen")


async def test_image_without_vision_model_notes():
    b, tr, llm = make()
    await feed(b, codec.build_envelope(codec.KIND_IMAGE, 0, codec.pack_file("a.jpg", "image/jpeg", b"\xff\xd8")), 1)
    await feed(b, codec.build_envelope(codec.KIND_TEXT, 0, b"Was ist das?"), 2)
    await settle()
    assert llm.calls and llm.calls[0][2] is None  # llama3.1 ohne Vision, kein vision_model


async def test_image_with_vision_capable_model():
    b, tr, llm = make()
    await feed(b, codec.build_envelope(codec.KIND_SET_MODEL, 0, b"llava"), 1)
    await feed(b, codec.build_envelope(codec.KIND_IMAGE, 0, codec.pack_file("a.jpg", "image/jpeg", b"\xff\xd8")), 2)
    await feed(b, codec.build_envelope(codec.KIND_TEXT, 0, b"Was ist das?"), 3)
    await settle()
    assert llm.calls[0][1] == "llava" and llm.calls[0][2]


async def test_image_falls_back_to_vision_model():
    b, tr, llm = make({"access": {"allowed_peers": [PEER]}, "llm": {"vision_model": "llava"}})
    await feed(b, codec.build_envelope(codec.KIND_IMAGE, 0, codec.pack_file("a.jpg", "image/jpeg", b"\xff\xd8")), 1)
    await feed(b, codec.build_envelope(codec.KIND_TEXT, 0, b"Was ist das?"), 2)
    await settle()
    assert llm.calls[0][1] == "llava" and llm.calls[0][2]


async def test_model_list_request():
    b, tr, llm = make()
    await feed(b, codec.build_envelope(codec.KIND_MODEL_LIST_REQ, 0, b""))
    await settle()
    # ACK + Antwort-Fragmente; Antwort zusammensetzen
    from lkbridge.transfer import Receiver
    rx, env = Receiver(), None
    for t in tr.out:
        f = codec.parse_frame(t)
        if f.type in (codec.T_DATA, codec.T_POLL):
            _, e = rx.on_frame("srv", f)
            env = e or env
    e = codec.parse_envelope(env)
    assert e.kind == codec.KIND_MODEL_LIST
    assert e.payload.decode().split("\n") == ["llama3.1\t", "llava\tv"]


async def test_set_model_per_conversation_and_reset():
    b, tr, llm = make()
    await feed(b, codec.build_envelope(codec.KIND_SET_MODEL, 2, b"llava"), 1)
    await settle()
    await b._answer(PEER, 2, "hi", [])
    await b._answer(PEER, 0, "ho", [])
    assert [c[1] for c in llm.calls] == ["llava", "llama3.1"]
    await feed(b, codec.build_envelope(codec.KIND_SET_MODEL, 2, b""), 4)
    await settle()
    await b._answer(PEER, 2, "hu", [])
    assert llm.calls[-1][1] == "llama3.1"


async def test_unknown_model_rejected():
    b, tr, llm = make()
    await feed(b, codec.build_envelope(codec.KIND_SET_MODEL, 0, b"gibtsnicht"), 1)
    await settle()
    assert "gibtsnicht" not in b.conv_models.values()
    assert any(codec.parse_frame(t).type in (codec.T_DATA, codec.T_POLL) for t in tr.out)  # Statusmeldung


async def test_plain_text_message_supported():
    b, tr, llm = make()
    await b.on_text(PEER, "Hallo KI")
    await settle()
    assert llm.calls[0][0][-1]["content"] == "Hallo KI"


class FakeOps:
    def __init__(self):
        self.seen = []

    async def handle(self, payload: bytes) -> bytes:
        self.seen.append(payload)
        return b"ok\tbooks\nwiki\tWikipedia\teng\t5"


def collect_reply(tr):
    from lkbridge.transfer import Receiver
    rx, env = Receiver(), None
    for t in tr.out:
        f = codec.parse_frame(t)
        if f.type in (codec.T_DATA, codec.T_POLL):
            _, e = rx.on_frame("srv", f)
            env = e or env
    return codec.parse_envelope(env)


async def test_nomad_request_gets_response_with_same_request_number():
    b, tr, llm = make()
    b.ops = FakeOps()
    await feed(b, codec.build_envelope(codec.KIND_NOMAD_REQ, 42, b"books"))
    await settle()
    e = collect_reply(tr)
    assert (e.kind, e.conv) == (codec.KIND_NOMAD_RESP, 42) and e.payload.startswith(b"ok\tbooks")
    assert b.ops.seen == [b"books"]


async def test_nomad_request_without_ops_returns_error():
    b, tr, llm = make()
    await feed(b, codec.build_envelope(codec.KIND_NOMAD_REQ, 3, b"books"))
    await settle()
    assert collect_reply(tr).payload.startswith(b"error\t")


async def test_kb_option_selects_knowledge_client_per_conversation():
    b, tr, llm = make()
    kb = FakeLLM()
    b.kb_llm = kb
    await feed(b, codec.build_envelope(codec.KIND_SET_OPTS, 5, b"kb=1"))
    await settle()
    await b._answer(PEER, 5, "Frage", [])
    await b._answer(PEER, 6, "Frage", [])
    assert len(kb.calls) == 1 and len(llm.calls) == 1            # conv 5 -> NOMAD-KB, conv 6 -> Ollama direkt
    await feed(b, codec.build_envelope(codec.KIND_SET_OPTS, 5, b"kb=0"), 2)
    await settle()
    await b._answer(PEER, 5, "nochmal", [])
    assert len(kb.calls) == 1 and len(llm.calls) == 2


async def settle_long():
    """Wartet auf echte Zeit: Antworten ohne ACK laufen in den (kurzen) Timeout, bevor der nächste Auftrag drankommt."""
    await asyncio.sleep(0.5)


def make_raw():
    from lkbridge import rawframe

    c = VClock()
    tr, llm = FakeTransport(), FakeLLM()
    raw_out = []

    async def raw_send(peer, frame):
        raw_out.append(frame)

    lim = DutyCycleLimiter(0.10, clock=c, sleep=c.sleep)
    sender = Sender(tr.send_text, lim, RadioParams(), ack_timeout_s=0.05, max_retries=1,
                    raw_send=raw_send, raw_key=rawframe.derive_key("geheimnis-geheimnis"), clock=c)
    b = Bridge({"access": {"allowed_peers": [PEER]}, "model_state_file": str(Path(tempfile.mkdtemp()) / "m.json"), "llm": {"model": "llama3.1"}},
               tr, llm, sender, Receiver())
    b.start()
    return b, tr, raw_out


async def test_raw_option_is_per_peer_and_map_has_its_own_switch():
    b, tr, raw_out = make_raw()

    class Ops:
        async def handle(self, payload):
            return b"ok\tmap\twebp\tr\t14\t96\t0\t0\t1\t1\t5\t3\nabc" if payload.startswith(b"map") else b"ok\tbooks\nwiki\tWiki\teng\t5"
    b.ops = Ops()
    # ohne Option: alles verschlüsselt per Text
    await feed(b, codec.build_envelope(codec.KIND_NOMAD_REQ, 1, b"books"), 1)
    await settle_long()
    assert not raw_out and any(codec.parse_frame(t).type == codec.T_POLL for t in tr.out)

    # raw=1: Wiki-Antworten per Rohdaten, Karten weiterhin verschlüsselt
    await feed(b, codec.build_envelope(codec.KIND_SET_OPTS, 0, b"raw=1"), 2)
    await settle_long()
    assert b.peer_raw[PEER] == {"text": True}
    n_text = len(tr.out)
    await feed(b, codec.build_envelope(codec.KIND_NOMAD_REQ, 2, b"books"), 3)
    await settle_long()
    assert raw_out and all(f[0] == 0xA7 for f in raw_out)
    raw_out.clear()
    await feed(b, codec.build_envelope(codec.KIND_NOMAD_REQ, 3, b"map\t1\t2\t500\t96\t8"), 4)
    await settle_long()
    assert not raw_out                                                           # Karte verrät den Standort -> eigener Schalter

    await feed(b, codec.build_envelope(codec.KIND_SET_OPTS, 0, b"rawmap=1"), 5)
    await settle_long()
    await feed(b, codec.build_envelope(codec.KIND_NOMAD_REQ, 4, b"map\t1\t2\t500\t96\t8"), 6)
    await settle_long()
    assert raw_out and b.peer_raw[PEER] == {"text": True, "map": True}


async def test_chat_replies_are_never_sent_raw():
    b, tr, raw_out = make_raw()
    await feed(b, codec.build_envelope(codec.KIND_SET_OPTS, 0, b"raw=1;rawmap=1"), 1)
    await settle_long()
    await feed(b, codec.build_envelope(codec.KIND_TEXT, 0, b"private Frage"), 2)
    await settle_long()
    assert not raw_out                                                           # KI-Antworten sind privat -> immer verschlüsselt
