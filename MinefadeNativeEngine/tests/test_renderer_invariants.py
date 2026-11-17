#!/usr/bin/env python3
"""Static integration checks for Minefade's GLES chunk renderer and atlas path."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / "app/src/main/cpp"
ENGINE = (CPP / "engine/Engine.cpp").read_text(encoding="utf-8")
ENGINE_H = (CPP / "engine/Engine.h").read_text(encoding="utf-8")
WORLD = (CPP / "world/VoxelWorld.cpp").read_text(encoding="utf-8")
WORLD_H = (CPP / "world/VoxelWorld.h").read_text(encoding="utf-8")
RENDERER = (CPP / "render/WorldMeshRenderer.cpp").read_text(encoding="utf-8")
RENDERER_H = (CPP / "render/WorldMeshRenderer.h").read_text(encoding="utf-8")
CMAKE = (CPP / "CMakeLists.txt").read_text(encoding="utf-8")


def test_chunk_cache_is_revision_driven_not_outer_border_driven():
    assert "std::uint64_t revision{0};" in WORLD_H
    assert "std::uint64_t revision{0};" in WORLD_H[WORLD_H.index("struct ChunkMeshCache"):]
    needs = WORLD[WORLD.index("needsBuild[i] ="):WORLD.index("static constexpr int neighborOffsets")]
    assert "cached->second.outerBorder != outerBorder" in needs
    assert "|| outerBorder" not in needs
    assert "fresh.revision = previous == chunkMeshCache_.end() ? 1u : previous->second.revision + 1u;" in WORLD
    assert "range.revision=data.revision;" in WORLD


def test_terrain_gpu_resources_are_incremental_per_chunk():
    assert "render/WorldMeshRenderer.cpp" in CMAKE
    assert '#include "../render/WorldMeshRenderer.h"' in ENGINE_H
    assert "WorldMeshRenderer worldRenderer_;" in ENGINE_H
    assert "worldRenderer_.Sync(worldChunkViews_)" in ENGINE
    assert "world_.GetChunkMeshViews(centerX, centerZ, renderDistanceChunks_, worldChunkViews_)" in ENGINE
    assert "world_.BuildMesh(meshBuildScratch_" in ENGINE
    assert "1u, false);" in ENGINE
    assert "gpu.revision != view.revision" in RENDERER
    assert "const std::vector<ChunkMeshView>& chunkViews" in RENDERER_H
    assert "const std::vector<WorldVertex>* opaque" in WORLD_H
    assert "GL_STATIC_DRAW" in RENDERER
    assert "worldRenderer_.DrawOpaque(frustum)" in ENGINE
    assert "worldRenderer_.DrawTransparent(frustum, eye)" in ENGINE
    assert "glBufferData(GL_ARRAY_BUFFER,\n                 static_cast<GLsizeiptr>(worldMesh_.size()" not in ENGINE
    assert "worldVao_" not in ENGINE and "transparentVao_" not in ENGINE
    assert "worldChunkRanges_" not in ENGINE
    assert "worldMesh_" not in ENGINE and "transparentWorldMesh_" not in ENGINE
    assert "worldChunkViews_.clear();" in ENGINE
    assert "meshBuildScratch_.clear();" in ENGINE
    assert "ResidentChunkCount" in RENDERER_H


def test_frustum_uses_real_mesh_height_bounds():
    assert "float minY{0.0f};" in WORLD_H
    assert "float maxY{0.0f};" in WORLD_H
    assert "range.minY=data.minY;" in WORLD
    assert "range.maxY=data.maxY;" in WORLD
    assert "gpu.minY = view.minY;" in RENDERER
    assert "gpu.maxY = view.maxY;" in RENDERER


def test_chunk_mesh_work_is_budgeted_and_nearby_first():
    signature = WORLD[WORLD.index("void VoxelWorld::BuildMesh"):WORLD.index("bool VoxelWorld::HasPendingMeshBuild")]
    assert "std::size_t meshBuildBudget" in signature
    assert "if (meshBuildBudget > 0u)" in WORLD
    assert "if (scheduled < meshBuildBudget)" in WORLD
    assert "return distanceA < distanceB;" in WORLD
    assert "bool VoxelWorld::HasPendingMeshBuild" in WORLD
    assert "constexpr float kChunkMeshInterval = 1.0f / 30.0f;" in ENGINE
    assert "world_.HasPendingMeshBuild(meshCenterX, meshCenterZ, renderDistanceChunks_)" in ENGINE
    assert "worldChunkViews_)" in ENGINE
    assert "collectMergedMesh" in WORLD
    assert "if (!collectMergedMesh) return;" in WORLD
    assert "worldMesh_" not in ENGINE and "transparentWorldMesh_" not in ENGINE


def test_both_shaders_sample_raw_opengl_atlas_rows_without_double_flip():
    uv = "return vec2(pixel.x / atlasSize.x, pixel.y / atlasSize.y);"
    assert ENGINE.count(uv) == 2
    assert "1.0 - pixel.y / atlasSize.y" not in ENGINE
    assert "if (vColor.r < -1.5) material = vec3(1.0, 0.43, 0.16);" in ENGINE
    assert "else if (vColor.r < -0.5) material = vec3(0.36, 0.72, 0.91);" in ENGINE
    assert "float hemisphereFill = mix(0.88, 1.08, skyExposure);" in ENGINE


if __name__ == "__main__":
    test_chunk_cache_is_revision_driven_not_outer_border_driven()
    test_terrain_gpu_resources_are_incremental_per_chunk()
    test_frustum_uses_real_mesh_height_bounds()
    test_chunk_mesh_work_is_budgeted_and_nearby_first()
    test_both_shaders_sample_raw_opengl_atlas_rows_without_double_flip()
    print("Minefade renderer invariants passed.")
