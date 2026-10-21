import asyncio

from lkbridge import codec
from lkbridge.airtime import RadioParams
from lkbridge.limiter import DutyCycleLimiter
from lkbridge.server import Bridge
from lkbridge.transfer import Receiver, Sender

from .test_transfer import VClock

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

    async def chat(self, messages, model=None, images=None):
        self.calls.append((messages, model, images))
        return "Antwort: " + messages[-1]["content"][-20:]


def make(cfg=None):
    c = VClock()
    tr, llm = FakeTransport(), FakeLLM()
    lim = DutyCycleLimiter(0.10, clock=c, sleep=c.sleep)
    sender = Sender(tr.send_text, lim, RadioParams(), ack_timeout_s=0.5)
    cfg = cfg or {"access": {"allowed_peers": [PEER]}}
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
    assert llm.calls and llm.calls[0][2] is None


async def test_plain_text_message_supported():
    b, tr, llm = make()
    await b.on_text(PEER, "Hallo KI")
    await settle()
    assert llm.calls[0][0][-1]["content"] == "Hallo KI"
