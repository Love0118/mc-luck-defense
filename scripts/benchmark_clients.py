"""Minimal local protocol-777 load clients. Consume real packets; no rendering simulation.
Packet IDs verified from Paper 26.3 build 19 GameProtocols/ConfigurationProtocols.
Python standard library only. Offline localhost benchmark servers only.
"""
import argparse
import asyncio
import json
import struct
import time
import uuid
import zlib
from pathlib import Path


def varint(value):
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def string(value):
    data = value.encode()
    return varint(len(data)) + data


def decode(data, offset=0):
    value = 0
    for i in range(5):
        b = data[offset + i]
        value |= (b & 127) << (7 * i)
        if b < 128:
            return value, offset + i + 1
    raise ValueError("Invalid VarInt")


async def frame_length(reader):
    value = 0
    for i in range(5):
        b = (await reader.readexactly(1))[0]
        value |= (b & 127) << (7 * i)
        if b < 128:
            return value
    raise ValueError("Invalid frame length")


async def client(index, args, states):
    name = f"MudBench{index:02}"
    stats = states[name] = dict(ready=False, bytes=0, packets=0, errors=[], playPackets={})
    reader, writer = await asyncio.open_connection("127.0.0.1", args.port)
    compression = -1
    phase = "login"

    def send(packet_id, payload=b""):
        packet = varint(packet_id) + payload
        if compression >= 0:
            packet = varint(len(packet)) + zlib.compress(packet) if len(packet) >= compression else b"\0" + packet
        writer.write(varint(len(packet)) + packet)

    send(0, varint(777) + string("localhost") + struct.pack(">H", args.port) + varint(2))
    send(0, string(name) + uuid.uuid3(uuid.NAMESPACE_DNS, name).bytes)
    try:
        while True:
            size = await frame_length(reader)
            data = await reader.readexactly(size)
            stats["bytes"] += size + len(varint(size))
            stats["packets"] += 1
            if compression >= 0:
                uncompressed, pos = decode(data)
                data = zlib.decompress(data[pos:]) if uncompressed else data[pos:]
            pid, pos = decode(data)
            payload = data[pos:]
            if phase == "login":
                if pid == 3:
                    compression = decode(payload)[0]
                elif pid == 2:
                    send(3)
                    phase = "configuration"
                    send(0, string("en_us") + bytes([3, 0, 1, 127, 1, 0, 1, 0]))
                elif pid == 0:
                    raise RuntimeError(f"Login disconnect {payload[:300]!r}")
            elif phase == "configuration":
                if pid == 15:
                    send(7, varint(0))
                elif pid == 3:
                    send(3)
                    phase = "play"
                    stats["ready"] = True
                    print(f"READY {name}", flush=True)
                elif pid == 4:
                    send(4, payload)
                elif pid == 5:
                    send(5, payload)
                elif pid == 20:
                    send(9)
                elif pid == 2:
                    raise RuntimeError(f"Configuration disconnect {payload[:300]!r}")
            else:
                counts = stats["playPackets"]
                counts[str(pid)] = counts.get(str(pid), 0) + 1
                if pid == 45:
                    send(28, payload)
                elif pid == 73:
                    teleport_id, offset = decode(payload)
                    # 26.3 acknowledgement includes position and rotation (not just the id).
                    send(0, varint(teleport_id) + payload[offset:offset+24] + payload[offset+48:offset+56])
                    send(44)
                elif pid == 62:
                    send(45, payload)
                elif pid == 11:
                    send(11, struct.pack(">f", 64))
                elif pid == 32:
                    if b"Server closed" in payload:
                        stats["closed"] = True
                        break
                    raise RuntimeError(f"Play disconnect {payload[:300]!r}")
            await writer.drain()
    except (asyncio.IncompleteReadError, ConnectionError):
        stats["closed"] = True
    except Exception as error:
        stats["errors"].append(str(error))
        print(f"ERROR {name} {error}", flush=True)
    finally:
        writer.close()


async def main(args):
    states = {}
    started = time.time()
    tasks = []
    for i in range(args.count):
        tasks.append(asyncio.create_task(client(i, args, states)))
        await asyncio.sleep(.12)
    async def snapshot():
        while True:
            args.output.write_text(json.dumps(dict(seconds=time.time()-started, clients=states), indent=2))
            await asyncio.sleep(1)
    writer = asyncio.create_task(snapshot())
    await asyncio.gather(*tasks)
    writer.cancel()
    args.output.write_text(json.dumps(dict(seconds=time.time()-started, clients=states), indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=25585)
    parser.add_argument("--count", type=int, default=20)
    parser.add_argument("--output", type=Path, required=True)
    asyncio.run(main(parser.parse_args()))
