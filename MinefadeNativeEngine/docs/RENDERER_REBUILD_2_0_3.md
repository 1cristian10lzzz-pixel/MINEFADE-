# Minefade 2.0.3 — renderer rebuild and phase audit

## Why the previous render looked wrong

The supplied Mali-G57 screenshot shows repeated checker-like tile rows, oversized black faces/gaps and a view where terrain surfaces do not read as a consistent voxel world. The source audit found two concrete defects in the texture path:

1. The atlas bytes are uploaded directly with `glTexImage2D`, but both world fragment shaders inverted the atlas V coordinate a second time. Atlas rows could therefore sample a different block row from the tile selected by the mesh.
2. The native atlas UV helper used a hard-coded 486-pixel height even though the generated 414-block catalog has 39 rows (702 pixels). The catalog generator could rewrite that stale formula on each build.

The renderer also rebuilt the visible outer chunk ring unconditionally and uploaded a single whole-world opaque VBO plus a single whole-world transparent VBO after chunk mesh changes. Transparent triangles in all chunks were re-sorted when a chunk arrived.

## Changes made in 2.0.3

### Texture/face path

- Both GLSL paths now sample the raw OpenGL texture rows without the extra atlas-row inversion.
- The native `GetAtlasUv` helper and `generate_block_catalog.py` use the same row orientation and derive the atlas height from the catalog.
- The fallback fragment shader decodes water and lava material tags instead of multiplying their negative tags into black RGB values.
- Baked face darkening is softened; hemisphere fill and the directional light provide the main face contrast.
- Mesh regression tests check finite positions/UV/normals, unit normals, non-degenerate triangles, winding/normal agreement and valid atlas tile indices.

### Chunk and GPU path

- A chunk mesh cache now exposes a geometry revision and vertical bounds. The revision changes only when that chunk's mesh is rebuilt.
- The visible outer border is no longer marked dirty unconditionally.
- A new `WorldMeshRenderer` owns separate opaque and transparent VAO/VBO resources per chunk. It uploads only a changed chunk revision and releases resources when chunks leave the active set.
- Opaque rendering applies frustum culling using per-chunk vertical mesh bounds.
- Transparent chunks are sorted far-to-near at draw time. Their internal triangle order is regenerated only when that chunk's geometry revision changes.
- Dirty mesh work is prioritized by distance and spread over a 30 Hz budget (one changed chunk per pass). Newly generated chunks can appear progressively instead of rebuilding all pending meshes in one frame.
- World generation remains on its existing background worker path; this change does not claim fully asynchronous mesh uploads using PBOs.

### World/content and Android experience

- New chunk-local surface ruins use existing Bedrock block IDs, remain inside their owning chunk, and are determined by the world seed.
- Phase-4 regression checks cover the existing NSD/mDNS discover/register/resolve path, bounded TCP fields, the local join/host callbacks, hand mirroring and touch-control modes.
- Existing saves, IDs and world format are not migrated by this feature. Ruins appear only in chunks generated after this update; already saved chunks are not rewritten.

## Tests added

- Stable chunk revisions are reused when no block edits occur.
- Changing one chunk increments its revision without incrementing unaffected neighbours.
- A one-chunk mesh budget fills a 3×3 visible region progressively and keeps the nearest chunk's revision stable.
- World mesh triangle attributes, normals, winding and atlas tile indices are validated.
- Catalog UV generation is checked against its actual generated row count and orientation.
- Phase-4 LAN/touch source contracts are checked by a separate test script.
- Existing Rust, C++, atlas, audio, mod and shader-profile tests continue to run in the CI workflow.

## Phase status (do not infer completion from a screen or class)

- **Foundation/startup and renderer areas 4–8:** code-level changes and CI regression coverage are included in this build. A Mali-G57 visual session and frame-time/thermal measurements are still required before claiming the on-device renderer has been fully verified.
- **Phase 3 — world/gameplay:** deterministic terrain, biome, cave and ore generation already existed; this release adds a first deterministic surface-ruin family. Full dungeon sets, advanced navigation/pathfinding, persistent entity inventories, advanced survival progression and broader structure content remain open.
- **Phase 4 — Android/LAN:** redesigned UI and touch modes, automatic menu-triggered LAN discovery, host/join integration and source-level protocol tests exist. A two-phone real-network test, reconnection, chat, inventory/drop synchronization, host-authoritative world saving and a free-position HUD editor remain open.
- **Area 7 — visibility/LOD:** per-chunk revisions, frustum bounds, outer-ring cache reuse and progressive mesh budgeting are implemented. This is not yet a full hierarchical quadtree.
- **Area 8 — lighting/shaders:** corrected atlas orientation, fluid fallback decoding and softer hemisphere/directional lighting are implemented. Dynamic shadows, volumetric effects and device-specific quality calibration remain open.

## Performance honesty

The previous whole-world VBO re-upload and unconditional outer-ring rebuild are removed in code. The build does not yet contain trustworthy P50/P95 frame-time, memory or thermal comparisons from the user's Mali-G57 device. The 30 Hz one-chunk mesh budget bounds the number of chunk mesh builds scheduled by one render pass; it is not a measured FPS guarantee.

## Distant Horizons adaptation

Minefade keeps its OpenGL ES implementation independent. The Distant Horizons-inspired direction remains a hierarchy of progressively coarser terrain data, neighbor-aware visibility/occlusion, retained material layers and far-data reduction. Minefade 2.0.3 adds per-chunk GPU residency and budgeted dirty-mesh processing but does not copy the Distant Horizons Java renderer or implement its complete data-source/quadtree pipeline. The existing far LOD still uses a top-surface height/material field and can omit caves and overhangs at distance. A multi-layer column representation and hierarchical region selection remain the next renderer milestone.

## Seguimiento de GPU buffers para Minefade 2.0.4

El renderer por chunk conserva ahora la capacidad reservada de cada VBO opaco y transparente mientras el chunk permanezca residente. Si una nueva malla cabe en esa capacidad, se actualizan sus bytes con `glBufferSubData`; `glBufferData` solo vuelve a reservar almacenamiento cuando la malla supera el máximo anterior. Al retirar un chunk o liberar el renderer, también se reinicia esa capacidad.

Esto reduce las reasignaciones de almacenamiento GPU durante ediciones repetidas del mismo chunk. No significa que el meshing ya sea asíncrono ni que exista un presupuesto rígido de bytes por frame: esos siguen siendo pasos separados del plan 2.1. La prueba `test_chunk_gpu_buffers_reuse_capacity` protege este contrato a nivel de integración del código.
