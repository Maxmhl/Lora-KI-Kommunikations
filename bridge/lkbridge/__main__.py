import argparse
import asyncio
import logging
import sys
import tomllib

from .airtime import RadioParams, dbm_to_mw, subband_limits
from .limiter import DutyCycleLimiter
from .gateway import Gateway
from .llm import NomadKnowledgeClient, OllamaClient
from .nomad_ops import NomadOps
from . import rawframe
from .nomad_sync import DEFAULT_EXCLUDE, Catalog
from .mc_transport import MeshCoreTransport
from .server import Bridge
from .transfer import Receiver, Sender

log = logging.getLogger("lkbridge")


async def run(cfg: dict) -> None:
    sc = cfg.get("serial", {})
    tr = MeshCoreTransport(sc.get("port", "/dev/ttyUSB0"), int(sc.get("baud", 115200)))
    await tr.start()

    rc = cfg.get("radio", {})
    radio = tr.radio_params() or RadioParams()
    for k in ("freq_mhz", "bw_khz", "sf", "cr", "preamble"):  # Config überschreibt Node-Werte
        if k in rc:
            setattr(radio, k, rc[k])
    duty, erp_mw, known = subband_limits(radio.freq_mhz)
    if "duty_cycle" in rc:
        duty = min(duty, float(rc["duty_cycle"]))
    if not known:
        log.warning("Frequenz %.3f MHz in keinem bekannten Subband – strengster Duty-Cycle %.1f %% angesetzt",
                    radio.freq_mhz, duty * 100)
    if radio.tx_power_dbm is not None and dbm_to_mw(radio.tx_power_dbm) > erp_mw:
        log.warning("Sendeleistung %d dBm (%.0f mW) > erlaubte ERP %d mW in diesem Subband – "
                    "Antennengewinn beachten, ggf. Leistung reduzieren!",
                    radio.tx_power_dbm, dbm_to_mw(radio.tx_power_dbm), erp_mw)
    log.info("Funk: %.3f MHz SF%d BW%.1f CR4/%d, Duty-Cycle %.1f %% (Sicherheitsfaktor %.2f)",
             radio.freq_mhz, radio.sf, radio.bw_khz, radio.cr, duty * 100, rc.get("margin", 0.8))

    limiter = DutyCycleLimiter(duty, float(rc.get("margin", 0.8)),
                               state_file=cfg.get("state_file", "limiter-state.json"))
    # Kompaktmodus: gemeinsames Geheimnis = Gateway-Token (muss in der App als Token eingetragen sein)
    secret = cfg.get("gateway", {}).get("token") or ""
    raw_key = rawframe.derive_key(secret) if len(secret) >= 16 and rc.get("raw_enabled", True) else None
    sender = Sender(tr.send_text, limiter, radio, ack_timeout_s=float(rc.get("ack_timeout_s", 90)),
                    raw_send=tr.send_raw if raw_key else None, raw_path_len=tr.raw_path_len, raw_key=raw_key)
    if raw_key:
        log.info("Kompaktmodus (Rohdatenpakete, beglaubigt) verfügbar – wird nur genutzt, wenn die App ihn anfordert")
    lc = cfg.get("llm", {})
    llm = OllamaClient(lc.get("base_url", "http://127.0.0.1:11434"), lc.get("model", "llama3.1"),
                       int(lc.get("num_predict", 400)))
    nc = cfg.get("nomad", {})
    kb_llm = NomadKnowledgeClient(llm, nc["url"]) if nc.get("url") else None
    ops = NomadOps(nc.get("kiwix_url", "http://127.0.0.1:8090"), nc.get("storage", "/opt/project-nomad/storage"), llm)
    receiver = Receiver(int(cfg.get("limits", {}).get("max_object_bytes", 200_000)))
    Bridge(cfg, tr, llm, sender, receiver, ops=ops, kb_llm=kb_llm).start()

    gc = cfg.get("gateway", {})
    if gc.get("enabled"):
        catalog = Catalog(nc.get("storage", "/opt/project-nomad/storage"), gc.get("exclude", DEFAULT_EXCLUDE),
                          int(float(gc["max_file_gb"]) * 1024**3) if gc.get("max_file_gb") else None)
        if not gc.get("token"):
            log.warning("Gateway läuft OHNE Token: jeder im Netzwerk kann NOMAD und die Downloads nutzen – nur in vertrauenswürdigen Netzen (LAN/VPN) verwenden, Port nie ins Internet")
        gw = Gateway(catalog, gc.get("token", ""), nc.get("url"), gc.get("host", "0.0.0.0"), int(gc.get("port", 8765)), ops)
        await gw.start()
    await asyncio.Event().wait()


def main() -> None:
    ap = argparse.ArgumentParser(prog="lkbridge")
    ap.add_argument("-c", "--config", default="config.toml")
    ap.add_argument("-v", "--verbose", action="store_true")
    a = ap.parse_args()
    logging.basicConfig(level=logging.DEBUG if a.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    try:
        with open(a.config, "rb") as f:
            cfg = tomllib.load(f)
    except OSError as e:
        sys.exit(f"Config nicht lesbar: {e}")
    asyncio.run(run(cfg))


if __name__ == "__main__":
    main()
