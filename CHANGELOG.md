# Changelog

## 0.1.0 (beta)

First public version, for Minecraft 1.21.1 with NeoForge.

- Bites for solid food, draining for drinks and potions, and food that sinks into its bowl, all generated from each item's texture.
- Works with resource packs: the frames follow the active texture.
- Recognizes bowls, bottles and glasses that mods draw with their own art.
- Hint files (`hints.json`) so mods and packs can say what a texture can't. Hints for Pam's HarvestCraft 2 and Croptopia are included.
- Leaves alone the items that already have hand-drawn eating frames (Eating Animation and packs made for it).
- Client config with an in-game screen: turn each kind of animation on or off, exclude items, disk cache.
- Disk cache for the generated frames, so later loads are faster.
