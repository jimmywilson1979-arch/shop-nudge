"""
Builds Shop Nudge's shop map from an OpenStreetMap extract.

Input:  an .osm.pbf already filtered to shops (osmium tags-filter ... nwr/shop nwr/amenity=pharmacy,fuel)
Output: <out>/tiles/<lat>_<lon>.json  — one file per 0.5° x 0.5° square
        <out>/index.json              — date + tile list + counts

Each shop is [id, lat, lon, name, brand, catMask]. catMask bits follow the
app's Category order: 0 supermarket, 1 corner shop, 2 chemist, 3 DIY,
4 B&M/pound shop, 5 pet, 6 petrol.
"""
import collections
import datetime
import json
import math
import os
import sys

import osmium

SHOP_BITS = {
    "supermarket": 0,
    "convenience": 1,
    "chemist": 2,
    "doityourself": 3, "hardware": 3, "trade": 3,
    "variety_store": 4, "department_store": 4, "discount": 4,
    "pet": 5,
}


def cat_mask(tags):
    mask = 0
    shop = tags.get("shop", "")
    amenity = tags.get("amenity", "")
    if shop in SHOP_BITS:
        mask |= 1 << SHOP_BITS[shop]
    if amenity == "pharmacy":
        mask |= 1 << 2
    if amenity == "fuel":
        mask |= 1 << 6
    return mask


def tile_key(lat, lon):
    return f"{math.floor(lat * 2) / 2:.1f}_{math.floor(lon * 2) / 2:.1f}"


class Shops(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.tiles = collections.defaultdict(list)
        self.count = 0

    def _add(self, oid, lat, lon, tags):
        tags = {t.k: t.v for t in tags}
        mask = cat_mask(tags)
        name = tags.get("name", "")
        brand = tags.get("brand", "")
        # Keep every shop type the app knows, plus any branded chain shop
        # (so "Only at Boots / Argos / Screwfix" works).
        if mask == 0 and not brand:
            return
        if mask == 0 and "shop" not in tags and tags.get("amenity") not in ("pharmacy", "fuel"):
            return
        self.tiles[tile_key(lat, lon)].append([oid, round(lat, 5), round(lon, 5), name, brand, mask])
        self.count += 1

    def node(self, n):
        if n.tags and n.location.valid():
            if "shop" in n.tags or n.tags.get("amenity") in ("pharmacy", "fuel"):
                self._add(f"n{n.id}", n.location.lat, n.location.lon, n.tags)

    def way(self, w):
        if not ("shop" in w.tags or w.tags.get("amenity") in ("pharmacy", "fuel")):
            return
        pts = [(nd.location.lat, nd.location.lon) for nd in w.nodes if nd.location.valid()]
        if len(pts) > 1 and pts[0] == pts[-1]:
            pts = pts[:-1]  # closed shape: don't count the corner twice
        if not pts:
            return
        lat = sum(p[0] for p in pts) / len(pts)
        lon = sum(p[1] for p in pts) / len(pts)
        self._add(f"w{w.id}", lat, lon, w.tags)


def main(src, out):
    h = Shops()
    h.apply_file(src, locations=True)
    tiles_dir = os.path.join(out, "tiles")
    os.makedirs(tiles_dir, exist_ok=True)
    for key, shops in h.tiles.items():
        with open(os.path.join(tiles_dir, f"{key}.json"), "w", encoding="utf-8") as f:
            json.dump({"v": 1, "s": shops}, f, ensure_ascii=False, separators=(",", ":"))
    index = {
        "v": 1,
        "built": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%MZ"),
        "shops": h.count,
        "tiles": {k: len(v) for k, v in sorted(h.tiles.items())},
    }
    with open(os.path.join(out, "index.json"), "w") as f:
        json.dump(index, f, indent=1)
    print(f"{h.count} shops in {len(h.tiles)} tiles")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
