"""Compile the local MG4 OBJ into a bounded, little-endian runtime mesh.

No Blender or third-party Python package is needed. Geometry and derived binary
remain local (CC BY-NC-SA 4.0). Run after model/paint export:
  python3 tools/birdseye/compile_car_mesh.py --model-dir DIRECTORY [--overwrite]
"""
import argparse
import array
import hashlib
import json
import math
import os
import struct
import sys
from pathlib import Path

PARTS = ("body", "front_left", "front_right", "rear_left", "rear_right")
MAGIC = b"MG4MSH01"


def compile_mesh(directory, overwrite=False):
    source = directory/"car.obj"
    target = directory/"car.meshbin"
    metadata_file = directory/"model.json"
    if target.exists() and not overwrite:
        raise ValueError("Output exists; use --overwrite to replace the prepared mesh")
    metadata = json.loads(metadata_file.read_text())
    if source.stat().st_size > 24*1024*1024:
        raise ValueError("OBJ exceeds input size limit")
    source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
    if source_hash != metadata["sha256"]["car.obj"]:
        raise ValueError("OBJ does not match model.json")
    positions, normals, uvs, groups, pivots = [], [], [], [], {}
    vertices = array.array("f")
    current = None
    for line in source.open():
        if len(line) > 4096:
            raise ValueError("OBJ line exceeds limit")
        fields = line.split()
        if not fields:
            continue
        kind = fields[0]
        if kind == "#" and len(fields) > 1 and fields[1] == "pivot":
            if len(fields) != 6 or fields[2] not in PARTS[1:] or fields[2] in pivots:
                raise ValueError("Invalid wheel pivot")
            pivots[fields[2]] = tuple(map(float, fields[3:]))
        elif kind in ("v", "vn", "vt"):
            values = tuple(map(float, fields[1:]))
            if len(values) != (2 if kind == "vt" else 3) or not all(math.isfinite(v) for v in values):
                raise ValueError("Invalid OBJ attribute")
            destination = {"v": positions, "vn": normals, "vt": uvs}[kind]
            destination.append(values)
            if len(destination) > 300000:
                raise ValueError("Too many OBJ attributes")
        elif kind == "g":
            if len(fields) != 2 or len(groups) >= 5 or fields[1] != PARTS[len(groups)]:
                raise ValueError("Expected body and four ordered wheel groups")
            current = {"name": fields[1], "first": len(vertices)//8, "count": 0}
            groups.append(current)
        elif kind == "f":
            if current is None or len(fields) != 4 or len(vertices)//8+3 > 300000:
                raise ValueError("Expected at most 100,000 grouped triangles")
            for corner in fields[1:]:
                indices = tuple(map(int, corner.split("/")))
                if len(indices) != 3 or min(indices) < 1:
                    raise ValueError("Invalid triangle indices")
                p, uv, n = indices
                vertices.extend((*positions[p-1], *normals[n-1], *uvs[uv-1]))
            current["count"] += 3
    if len(groups) != 5 or any(g["count"] == 0 for g in groups):
        raise ValueError("Missing mesh groups")
    if len(vertices)//24 != metadata["triangles"]:
        raise ValueError("Triangle count differs from metadata")
    header = bytearray(struct.pack("<8sIIII32s", MAGIC, 1, 8, len(vertices)//8, 5, bytes.fromhex(source_hash)))
    for group in groups:
        pivot = (0, 0, 0) if group["name"] == "body" else pivots[group["name"]]
        info = next(p for p in metadata["parts"] if p["name"] == group["name"])
        if info["triangles"]*3 != group["count"] or any(abs(a-b) > .0001 for a, b in zip(pivot, info["pivot_mm"])):
            raise ValueError("Group differs from metadata")
        if not all(math.isfinite(v) and abs(v) < 10000 for v in pivot):
            raise ValueError("Invalid pivot")
        header.extend(struct.pack("<16sII3f", group["name"].encode("ascii"), group["first"], group["count"], *pivot))
    if sys.byteorder != "little":
        vertices.byteswap()
    temporary = target.with_suffix(".meshbin.tmp")
    with temporary.open("wb") as stream:
        stream.write(header)
        vertices.tofile(stream)
    binary_hash = hashlib.sha256(temporary.read_bytes()).hexdigest()
    os.replace(temporary, target)
    metadata["runtime_mesh"] = {"file": target.name, "format_version": 1, "vertex_stride_floats": 8,
                                "vertices": len(vertices)//8, "bytes": target.stat().st_size,
                                "sha256": binary_hash, "source_sha256": source_hash}
    temporary_meta = metadata_file.with_suffix(".json.tmp")
    temporary_meta.write_text(json.dumps(metadata, indent=2)+"\n")
    os.replace(temporary_meta, metadata_file)
    print(json.dumps(metadata["runtime_mesh"], indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--overwrite", action="store_true")
    options = parser.parse_args()
    compile_mesh(options.model_dir.resolve(), options.overwrite)
