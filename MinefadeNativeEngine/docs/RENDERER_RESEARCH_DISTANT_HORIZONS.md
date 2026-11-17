# Render research: Distant Horizons and Minefade's GLES path

## What was inspected

The supplied Distant Horizons source archive contains the LGPL-3.0 core renderer and terrain-data pipeline. Relevant files examined:

- `core/.../dataObjects/transformers/LodDataBuilder.java`: converts generated chunks into vertically stacked per-column terrain data points instead of keeping every voxel for distant rendering.
- `core/.../dataObjects/transformers/FullDataOcclusionCuller.java`: checks neighboring columns in the four cardinal directions and removes opaque vertical data segments that cannot be seen, while preserving transparent and exposed top/bottom segments.
- `core/.../render/QuadTree/LodQuadTree.java` and `LodRenderSection.java`: hold hierarchical terrain sections and select render detail by camera position/distance.
- `core/.../dataObjects/render/bufferBuilding/LodQuadBuilder.java` and `ColumnRenderBufferBuilder.java`: convert column/LOD data into compact render buffers.

This is materially different from drawing every far chunk with the same full block mesh. The key idea is to retain a useful terrain representation at a much lower data and vertex cost, then select it through a hierarchy.

## Adaptation implemented in Minefade

Minefade remains an OpenGL ES 3.0/3.2 Android renderer with a Rust simulation core and a C++ GLES/world adapter. The user-supplied Java code was inspected for design, but no Distant Horizons source files were copied into Minefade.

The far path in `VoxelWorld::BuildMesh` now builds a bordered 18×18 height/material field for distant chunks and emits:

- one top face per visible 16×16 surface column;
- vertical side quads only where a neighboring column is lower;
- separate transparent output for fluid/transparent surface materials;
- the existing full greedy voxel mesh for the nearest rings.

This is a first heightfield LOD stage, not a port of Distant Horizons' complete FullDataSource + quadtree system. It discards caves, overhangs and underground structures at far LOD by design; nearby chunks retain full voxel geometry. The per-chunk mesh cache and dirty-neighbor invalidation remain responsible for reusing and rebuilding these meshes.

## Remaining renderer work

1. Replace the single per-chunk heightfield ring with hierarchical region nodes (quadtree) so more than one distance band can be selected independently of the full chunk grid.
2. Store compressed vertical material segments per column instead of only the top surface; this can retain cliffs, layered strata and selected cave openings at far distances.
3. Add neighbor-aware occlusion culling for opaque segments, preserving leaves, water and other see-through materials.
4. Move far-mesh construction off the GL thread and upload bounded batches, with cancellation when the camera changes direction.
5. Add device-level frame-time, RAM, thermal and visual comparisons on Mali-G57 as well as lower/higher tier GPUs.

## License and clean-room adaptation

The inspected Distant Horizons core files carry LGPL v3 notices. Minefade uses the terrain-LOD concepts and an independent C++ implementation; it does not copy those Java files. Any future direct reuse or port of LGPL source must first preserve the required notices and satisfy the license's source/distribution obligations.
