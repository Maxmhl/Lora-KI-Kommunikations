import asyncio
import os
import random

from lkbridge import codec
from lkbridge.airtime import RadioParams
from lkbridge.limiter import DutyCycleLimiter
from lkbridge.transfer import Receiver, Sender


class VClock:
    def __init__(self):
        self.t = 0.0

    def __call__(self):
        return self.t

    async def sleep(self, s):
        self.t += s
        await asyncio.sleep(0)


class Node:
    """Eine Seite eines simulierten Funklinks."""

    def __init__(self, name, clock, drop):
        self.name, self.drop = name, drop
        self.peer = None
        self.receiver = Receiver()
        lim = DutyCycleLimiter(0.10, clock=clock, sleep=clock.sleep)
        self.sender = Sender(self._send, lim, RadioParams(), ack_timeout_s=0.05)
        self.completed = []
        self.sent_texts = []

    async def _send(self, peer, text):
        self.sent_texts.append(text)
        if self.drop(text):
            return
        asyncio.get_running_loop().call_soon(lambda: asyncio.create_task(self.peer.on_text(self.name, text)))

    async def on_text(self, peer, text):
        f = codec.parse_frame(text)
        if f.type in (codec.T_ACK, codec.T_ABORT):
            self.sender.on_ack(peer, f)
            return
        reply, env = self.receiver.on_frame(peer, f)
        if reply:
            await self.sender.tx(peer, reply)
        if env is not None:
            self.completed.append(env)


def pair(drop_a=lambda t: False, drop_b=lambda t: False):
    c = VClock()
    a, b = Node("A", c, drop_a), Node("B", c, drop_b)
    a.peer, b.peer = b, a
    return a, b, c


async def test_transfer_clean():
    a, b, c = pair()
    env = codec.build_envelope(codec.KIND_FILE, 1, os.urandom(2000))
    assert await a.sender.send("B", 5, env)
    assert b.completed == [env]
    assert c.t > 60  # Duty-Cycle-Pausen wurden eingehalten


async def test_transfer_with_loss():
    rnd = random.Random(1)
    a, b, _ = pair(drop_a=lambda t: rnd.random() < 0.25, drop_b=lambda t: rnd.random() < 0.25)
    env = codec.build_envelope(codec.KIND_FILE, 0, os.urandom(1500))
    a.sender.max_retries = 30
    assert await a.sender.send("B", 9, env)
    assert b.completed == [env]


async def test_duplicate_complete_not_redelivered():
    a, b, _ = pair()
    env = codec.build_envelope(codec.KIND_TEXT, 0, b"hi")
    assert await a.sender.send("B", 1, env)
    assert await a.sender.send("B", 1, env)
    assert len(b.completed) == 1


async def test_sender_gives_up():
    a, b, _ = pair(drop_a=lambda t: True)
    a.sender.max_retries = 2
    assert not await a.sender.send("B", 2, codec.build_envelope(codec.KIND_TEXT, 0, b"x"))


async def test_abort_on_too_big():
    a, b, _ = pair()
    b.receiver = Receiver(max_object_bytes=300)
    assert not await a.sender.send("B", 3, codec.build_envelope(codec.KIND_FILE, 0, os.urandom(2000)))


async def test_ack_truncation_never_overclaims():
    rx = Receiver()
    total = 40
    # nur das letzte Fragment kommt an -> 39 Lücken, Liste auf 12 gekürzt
    f = codec.Frame(codec.T_POLL, 1, total - 1, total, b"x")
    reply, _ = rx.on_frame("P", f)
    a = codec.parse_frame(reply)
    received = set(range(a.hi)) - set(a.missing)
    assert received == set()  # nichts fälschlich als empfangen gemeldet
