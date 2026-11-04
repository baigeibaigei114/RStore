"""Read a bounded, local, single-granule Sentinel-2 L2A SAFE ZIP.

The first verified layout is PSD 15 / PB 05.13. No network access or XML
schema/resource resolution is performed. Limits are probe defaults, not API policy.
"""
import hashlib
import math
import re
import stat
import unicodedata
import zipfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path, PurePosixPath, PureWindowsPath

PARSER_VERSION = "safe-l2a-probe-v1"
BANDS = ("B02", "B03", "B04", "B08")
CALIBRATION_NAMES = ("blue", "green", "red", "nir")
# PSD spectral identifiers are not positions in the four-band output array.
BAND_IDS = {"B02": "1", "B03": "2", "B04": "3", "B08": "7"}
PRODUCT_NS = "https://psd-15.sentinel2.eo.esa.int/PSD/User_Product_Level-2A.xsd"
TILE_NS = "https://psd-15.sentinel2.eo.esa.int/PSD/S2_PDI_Level-2A_Tile_Metadata.xsd"


@dataclass(frozen=True)
class ProbeLimits:
    archive_bytes: int = 2 * 1024**3
    entries: int = 4096
    entry_bytes: int = 1024**3
    expanded_bytes: int = 8 * 1024**3
    xml_bytes: int = 8 * 1024**2


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def member_path(name):
    """Reject path aliases that become escapes/collisions on Linux or Windows."""
    if (not name or "\\" in name or "\x00" in name or name.startswith("/")
            or PureWindowsPath(name).drive or ":" in name):
        raise ValueError("Unsafe ZIP member path")
    parts = name.rstrip("/").split("/")
    reserved = {"CON", "PRN", "AUX", "NUL"} | {f"{p}{i}" for p in ("COM", "LPT") for i in range(1, 10)}
    if any(p in ("", ".", "..") or p.endswith((" ", "."))
           or p.split(".")[0].upper() in reserved for p in parts):
        raise ValueError("Unsafe ZIP member path")
    return PurePosixPath(*parts)


def parse_xml(raw, expected_tag):
    # Reject UTF-16/32 (NUL), DTDs and entities before ElementTree sees content.
    if b"\x00" in raw or re.search(br"<!\s*(DOCTYPE|ENTITY)\b", raw, re.I):
        raise ValueError("DTD/entity declarations and non-UTF-8 XML are unsupported")
    root = ET.fromstring(raw.decode("utf-8-sig"))
    if root.tag != expected_tag:
        raise ValueError("Unsupported SAFE metadata namespace/layout")
    return root


def elements(root, name):
    return [node for node in root.iter() if node.tag.rsplit("}", 1)[-1] == name]


def single(root, name):
    found = elements(root, name)
    if len(found) != 1 or not (found[0].text or "").strip():
        raise ValueError(f"Expected exactly one {name}")
    return found[0].text.strip()


def finite(text):
    value = float(text)
    if not math.isfinite(value):
        raise ValueError("Non-finite metadata number")
    return value


class SafeProduct:
    def __init__(self, archive, limits=None):
        self.archive = Path(archive)
        self.limits = limits or ProbeLimits()
        self._zip = None
        self.extracted_bytes = 0

    def __enter__(self):
        if self.archive.stat().st_size > self.limits.archive_bytes:
            raise ValueError("SAFE ZIP exceeds probe byte limit")
        self._zip = zipfile.ZipFile(self.archive)
        try:
            self._inspect()
        except BaseException:
            self._zip.close()
            raise
        return self

    def __exit__(self, *args):
        self._zip.close()

    def _inspect(self):
        entries = self._zip.infolist()
        if len(entries) > self.limits.entries:
            raise ValueError("Too many ZIP entries")
        seen, self.members, total = set(), {}, 0
        for item in entries:
            path = member_path(item.orig_filename)
            key = unicodedata.normalize("NFC", str(path)).casefold()
            mode = item.external_attr >> 16
            if key in seen or stat.S_ISLNK(mode) or (stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR)):
                raise ValueError("Duplicate/aliased or special ZIP member")
            seen.add(key)
            if item.flag_bits & 1 or item.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                raise ValueError("Encrypted/unsupported ZIP entry")
            if item.file_size > self.limits.entry_bytes:
                raise ValueError("ZIP entry exceeds probe limit")
            total += item.file_size
            if total > self.limits.expanded_bytes:
                raise ValueError("ZIP expanded-byte budget exceeded")
            if not item.is_dir():
                self.members[str(path)] = item
        roots = {PurePosixPath(n).parts[0] for n in self.members}
        if len(roots) != 1 or not next(iter(roots)).endswith(".SAFE"):
            raise ValueError("Expected exactly one top-level SAFE product")
        self.root = next(iter(roots))
        self.total_expanded_bytes = total
        metadata = self.read_xml(f"{self.root}/MTD_MSIL2A.xml")
        product = parse_xml(metadata, f"{{{PRODUCT_NS}}}Level-2A_User_Product")
        if single(product, "PROCESSING_LEVEL") != "Level-2A" or single(product, "PRODUCT_TYPE") != "S2MSI2A":
            raise ValueError("Only Sentinel-2 L2A is supported")
        self.baseline = single(product, "PROCESSING_BASELINE")
        if self.baseline != "05.13":
            raise ValueError("Unsupported processing baseline; verified support is 05.13")
        self.source_id = single(product, "PRODUCT_URI")
        if self.source_id != self.root or not re.fullmatch(r"S2[ABC]_MSIL2A_\d{8}T\d{6}_N0513_R\d{3}_T\w{5}_\d{8}T\d{6}\.SAFE", self.root):
            raise ValueError("Product identity and SAFE root disagree")
        self.capture_time = single(product, "PRODUCT_START_TIME")
        if datetime.fromisoformat(self.capture_time.replace("Z", "+00:00")).tzinfo is None:
            raise ValueError("Product time requires a timezone")
        granules = elements(product, "Granule")
        if len(granules) != 1:
            raise ValueError("Only one granule is supported")
        files = [node.text.strip() for node in elements(granules[0], "IMAGE_FILE") if node.text]
        files = [str(member_path(p if p.endswith(".jp2") else p + ".jp2")) for p in files]
        if not files or len(files) != len(set(files)):
            raise ValueError("Missing/duplicate image references")
        for name in files:
            self.require_member(f"{self.root}/{name}")
        directories = {PurePosixPath(n).parts[1] for n in files if n.startswith("GRANULE/")}
        archive_granules = {PurePosixPath(n).parts[2] for n in self.members
                            if n.startswith(self.root + "/GRANULE/")}
        if len(directories) != 1 or directories != archive_granules:
            raise ValueError("Inconsistent or multiple granule directories")
        self.granule = next(iter(directories))
        self.band_members = {}
        for band in (*BANDS, "SCL"):
            resolution = 20 if band == "SCL" else 10
            suffix = f"_{band}_{resolution}m.jp2"
            found = [n for n in files if n.endswith(suffix) and f"/IMG_DATA/R{resolution}m/" in n]
            if len(found) != 1:
                raise ValueError(f"Expected one {band} at {resolution}m")
            self.band_members[band] = f"{self.root}/{found[0]}"
        self.quantification = finite(single(product, "BOA_QUANTIFICATION_VALUE"))
        if self.quantification <= 0:
            raise ValueError("BOA quantification must be positive")
        offsets = {}
        for node in elements(product, "BOA_ADD_OFFSET"):
            key = node.get("band_id")
            if key in offsets or key not in {str(i) for i in range(13)}:
                raise ValueError("Invalid/duplicate BOA offset identifier")
            offsets[key] = finite(node.text)
        spectral = {n.get("bandId"): n.get("physicalBand") for n in elements(product, "Spectral_Information")}
        if any(spectral.get(BAND_IDS[b]) != "B" + str(int(b[1:])) for b in BANDS):
            raise ValueError("Spectral identifier mapping disagrees with PSD")
        if not set(BAND_IDS.values()) <= offsets.keys():
            raise ValueError("Missing per-band BOA offsets")
        self.raw_offsets = {b: offsets[BAND_IDS[b]] for b in BANDS}
        self.calibration = {name: {"scale": 1 / self.quantification,
                                  "offset": self.raw_offsets[b] / self.quantification}
                            for b, name in zip(BANDS, CALIBRATION_NAMES)}
        tile = parse_xml(self.read_xml(f"{self.root}/GRANULE/{self.granule}/MTD_TL.xml"),
                         f"{{{TILE_NS}}}Level-2A_Tile_ID")
        if single(tile, "TILE_ID") != granules[0].get("granuleIdentifier"):
            raise ValueError("Tile identity mismatch")
        self.crs = single(tile, "HORIZONTAL_CS_CODE")
        sizes = [n for n in elements(tile, "Size") if n.get("resolution") == "10"]
        positions = [n for n in elements(tile, "Geoposition") if n.get("resolution") == "10"]
        if len(sizes) != 1 or len(positions) != 1:
            raise ValueError("Missing/ambiguous 10m grid")
        self.width, self.height = int(single(sizes[0], "NCOLS")), int(single(sizes[0], "NROWS"))
        self.transform = [finite(single(positions[0], n)) for n in ("XDIM", "ULX", "YDIM", "ULY")]
        if self.transform[0] != 10 or self.transform[2] != -10 or self.width <= 0 or self.height <= 0:
            raise ValueError("Unsupported 10m grid")
        self.transform = [10, 0, self.transform[1], 0, -10, self.transform[3]]
        self.require_member(f"{self.root}/manifest.safe")
        self.require_member(f"{self.root}/INSPIRE.xml")
        self.metadata_sha256 = hashlib.sha256(metadata).hexdigest()

    def require_member(self, name):
        if name not in self.members:
            raise ValueError("Referenced SAFE file is missing")
        return self.members[name]

    def read_xml(self, name):
        item = self.require_member(name)
        if item.file_size > self.limits.xml_bytes:
            raise ValueError("XML exceeds probe limit")
        with self._zip.open(item) as stream:
            raw = stream.read(self.limits.xml_bytes + 1)
        if len(raw) != item.file_size or len(raw) > self.limits.xml_bytes:
            raise ValueError("Invalid XML size")
        return raw

    def materialize(self, name, directory):
        """Copy one validated member into a caller-owned fresh attempt directory."""
        item = self.require_member(name)
        destination = Path(directory) / member_path(name)
        destination.parent.mkdir(parents=True, exist_ok=True)
        count = 0
        with self._zip.open(item) as source, destination.open("xb") as output:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                count += len(chunk)
                self.extracted_bytes += len(chunk)
                if count > item.file_size or self.extracted_bytes > self.limits.expanded_bytes:
                    raise ValueError("Actual extraction byte budget exceeded")
                output.write(chunk)
        if count != item.file_size:
            raise ValueError("Truncated ZIP entry")
        return destination
