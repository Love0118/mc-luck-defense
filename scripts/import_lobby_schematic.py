"""Convert a Sponge v2 schematic into fresh Anvil regions for a void lobby world.

Requires numpy and nbtlib. Never run against a live/existing world directory.
Preserves the source DataVersion so Minecraft upgrades block and block-entity data.
"""
import argparse
import copy
import hashlib
import io
import json
import math
from pathlib import Path
import re
import struct
import zlib

import nbtlib as nbt
import numpy as np


def decode_blocks(data, count):
    raw = np.asarray(data, dtype=np.uint8)
    end = np.flatnonzero(raw < 128)
    starts = np.r_[0, end[:-1] + 1]
    lengths = end - starts + 1
    if len(end) != count or end[-1] != len(raw)-1 or np.any(lengths > 5):
        raise ValueError("Invalid schematic block VarInts")
    result = np.zeros(count, dtype=np.uint32)
    for byte in range(int(lengths.max())):
        mask = lengths > byte
        result[mask] |= (raw[starts[mask]+byte].astype(np.uint32) & 127) << (7*byte)
    return result


def state(text):
    match = re.fullmatch(r"([^\[]+)(?:\[(.*)\])?", text)
    if not match:
        raise ValueError(f"Invalid block state: {text}")
    result = nbt.Compound({"Name": nbt.String(match[1])})
    if match[2]:
        result["Properties"] = nbt.Compound({k: nbt.String(v) for k,v in (p.split("=",1) for p in match[2].split(","))})
    return result


def pack(indices, bits):
    per_long = 64//bits
    words = np.zeros(math.ceil(len(indices)/per_long), dtype=np.uint64)
    for slot in range(per_long):
        values = indices[slot::per_long].astype(np.uint64)
        words[:len(values)] |= values << (slot*bits)
    # Check padded (post-1.16) palette packing before writing any chunks.
    unpacked = np.array([(int(words[i//per_long]) >> ((i%per_long)*bits)) & ((1<<bits)-1) for i in range(len(indices))])
    if not np.array_equal(indices, unpacked):
        raise ValueError("Block palette packing failed")
    return nbt.LongArray(words.view(np.int64))


def convert(source, output):
    if output.exists():
        raise ValueError("Output must be a fresh directory; existing worlds are never overwritten")
    schematic = nbt.load(source)
    if int(schematic["Version"]) != 2:
        raise ValueError("This importer supports Sponge schematic v2")
    width,height,length = (int(schematic[k]) for k in ("Width","Height","Length"))
    if width%16 or length%16 or height>384:
        raise ValueError("Expected chunk-aligned dimensions within the overworld height")
    palette = {int(i):state(text) for text,i in schematic["Palette"].items()}
    air = int(schematic["Palette"]["minecraft:air"])
    blocks = decode_blocks(schematic["BlockData"],width*height*length).reshape(height,length,width)
    if not set(map(int,np.unique(blocks))) <= palette.keys():
        raise ValueError("Unknown block palette ID")
    # The source's upper selection contains air; actual build must fit y=0..319.
    if height>320 and np.any(blocks[320:]!=air):
        raise ValueError("Non-air build exceeds y=319")
    if schematic.get("Entities"):
        raise ValueError("Entity import is not supported; refusing to drop entities")
    ox,oz = -(width//32)*16,-(length//32)*16
    block_entities = {}
    for original in schematic.get("BlockEntities",[]):
        tag = copy.deepcopy(original)
        x,y,z = map(int,tag.pop("Pos"))
        tag["id"] = tag.pop("Id")
        x+=ox;z+=oz
        tag.update(x=nbt.Int(x),y=nbt.Int(y),z=nbt.Int(z))
        block_entities.setdefault((x//16,z//16),[]).append(tag)
    output.mkdir(parents=True)
    region_dir=output/"region";region_dir.mkdir()
    regions={}
    for cz in range(length//16):
        for cx in range(width//16):
            wx,wz=cx+ox//16,cz+oz//16
            sections=[]
            for sy in range(-4,20):
                volume=np.full((16,16,16),air,dtype=np.uint32)
                y=sy*16
                if 0<=y<height:
                    available=min(16,height-y)
                    volume[:available]=blocks[y:y+available,cz*16:cz*16+16,cx*16:cx*16+16]
                ids,inverse=np.unique(volume.reshape(-1),return_inverse=True)
                states=nbt.Compound({"palette":nbt.List[nbt.Compound]([palette[int(i)] for i in ids])})
                if len(ids)>1:
                    states["data"]=pack(inverse,max(4,(len(ids)-1).bit_length()))
                sections.append(nbt.Compound({"Y":nbt.Byte(sy),"block_states":states,"biomes":nbt.Compound({"palette":nbt.List[nbt.String]([nbt.String("minecraft:plains")])})}))
            chunk=nbt.File({
                "DataVersion":nbt.Int(schematic["DataVersion"]),
                "xPos":nbt.Int(wx),"yPos":nbt.Int(-4),"zPos":nbt.Int(wz),
                "Status":nbt.String("minecraft:full"),"LastUpdate":nbt.Long(0),"InhabitedTime":nbt.Long(0),
                "isLightOn":nbt.Byte(0),"sections":nbt.List[nbt.Compound](sections),
                "block_entities":nbt.List[nbt.Compound](block_entities.get((wx,wz),[])),
                "block_ticks":nbt.List[nbt.Compound]([]),"fluid_ticks":nbt.List[nbt.Compound]([]),
                "PostProcessing":nbt.List[nbt.List[nbt.Short]]([nbt.List[nbt.Short]([]) for _ in range(24)]),
                "Heightmaps":nbt.Compound(),
                "structures":nbt.Compound({"starts":nbt.Compound(),"References":nbt.Compound()})
            })
            stream=io.BytesIO();chunk.write(stream)
            encoded=zlib.compress(stream.getvalue())
            regions.setdefault((wx//32,wz//32),{})[(wx%32)+(wz%32)*32]=struct.pack(">I",len(encoded)+1)+bytes([2])+encoded
        if (cz+1)%4==0:print(f"Converted {cz+1}/{length//16} chunk rows",flush=True)
    for (rx,rz),chunks in regions.items():
        header=bytearray(8192);data=bytearray();offset=2
        for slot,payload in sorted(chunks.items()):
            sectors=math.ceil(len(payload)/4096)
            if sectors>255:raise ValueError("Chunk too large")
            struct.pack_into(">I",header,slot*4,(offset<<8)|sectors)
            data.extend(payload);data.extend(bytes(sectors*4096-len(payload)));offset+=sectors
        (region_dir/f"r.{rx}.{rz}.mca").write_bytes(header+data)
    manifest={"source":source.name,"sha256":hashlib.sha256(source.read_bytes()).hexdigest(),
              "sourceDataVersion":int(schematic["DataVersion"]),"size":[width,height,length],
              "origin":[ox,0,oz],"chunks":width*length//256,
              "nonAirBlocks":int(np.count_nonzero(blocks!=air)),"blockEntities":len(schematic["BlockEntities"])}
    (output/"imported-source.json").write_text(json.dumps(manifest,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(manifest),flush=True)


if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("source",type=Path);parser.add_argument("output",type=Path)
    args=parser.parse_args();convert(args.source,args.output)
