#include "VoxelWorld.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <chrono>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <limits>
#include <set>
#include <utility>

namespace minefade {
namespace {
constexpr float kEpsilon = 0.0001f;
constexpr std::uint32_t kMaxSavedChunks = 4096u;

Vec3 Subtract(const Vec3& a, const Vec3& b) {
    return {a.x - b.x, a.y - b.y, a.z - b.z};
}
Vec3 Cross(const Vec3& a, const Vec3& b) {
    return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
}
Vec3 Normalize(const Vec3& v) {
    const float length = std::sqrt(std::max(v.x * v.x + v.y * v.y + v.z * v.z, kEpsilon));
    return {v.x / length, v.y / length, v.z / length};
}
std::uint32_t Hash(std::uint32_t x) {
    x ^= x >> 16u; x *= 0x7feb352du; x ^= x >> 15u; x *= 0x846ca68bu; x ^= x >> 16u;
    return x;
}
float Smooth(float t) { return t * t * (3.0f - 2.0f * t); }
float Lerp(float a, float b, float t) { return a + (b - a) * t; }

bool IsValidBlock(BlockId block) {
    return static_cast<std::size_t>(block) < GetBlockDefinitionCount();
}

bool IsTargetable(BlockId block) {
    const BlockDefinition* def = GetBlockDefinition(block);
    return def != nullptr && def->shape != BlockShape::None;
}

bool ShouldHideFace(BlockId current, BlockId neighbor) {
    const BlockDefinition* currentDef = GetBlockDefinition(current);
    const BlockDefinition* neighborDef = GetBlockDefinition(neighbor);
    if (neighborDef == nullptr || currentDef == nullptr) return false;
    if ((neighborDef->flags & BlockOpaque) != 0u) return true;
    return current == neighbor && (currentDef->flags & BlockTransparent) != 0u;
}

std::array<float, 3> FaceShade(int face) {
    // Slight directional contrast without the old single flat-color appearance.
    switch (face) {
        case 2: return {1.00f, 1.00f, 1.00f}; // top
        case 3: return {0.57f, 0.57f, 0.57f}; // bottom
        case 0: case 1: return {0.82f, 0.82f, 0.82f};
        default: return {0.91f, 0.91f, 0.91f};
    }
}

void AddFace(std::vector<WorldVertex>& out, const std::array<Vec3, 4>& p,
             const std::array<float, 3>& color, std::uint16_t tile) {
    const Vec3 normal = Normalize(Cross(Subtract(p[1], p[0]), Subtract(p[2], p[0])));
    float u0=0, v0=0, u1=1, v1=1;
    GetAtlasUv(tile, u0, v0, u1, v1);
    const std::array<std::array<float,2>,4> uv{{{{u0,v0}},{{u1,v0}},{{u1,v1}},{{u0,v1}}}};
    constexpr int order[6] = {0, 1, 2, 0, 2, 3};
    for (int i : order) {
        const std::size_t index = static_cast<std::size_t>(i);
        const Vec3& v = p[index];
        out.push_back({v.x,v.y,v.z,normal.x,normal.y,normal.z,
                       color[0],color[1],color[2],uv[index][0],uv[index][1]});
    }
}

std::uint16_t TileForFace(const BlockDefinition& def, int face) {
    if (face == 2) return def.tileTop;
    if (face == 3) return def.tileBottom;
    return def.tileSide;
}

void AppendBox(std::vector<WorldVertex>& out, float x0, float y0, float z0,
               float x1, float y1, float z1, const BlockDefinition& def) {
    static constexpr std::array<std::array<Vec3,4>,6> kFaces{{
        {{{1,0,0},{1,1,0},{1,1,1},{1,0,1}}},
        {{{0,0,1},{0,1,1},{0,1,0},{0,0,0}}},
        {{{0,1,1},{1,1,1},{1,1,0},{0,1,0}}},
        {{{0,0,0},{1,0,0},{1,0,1},{0,0,1}}},
        {{{1,0,1},{1,1,1},{0,1,1},{0,0,1}}},
        {{{0,0,0},{0,1,0},{1,1,0},{1,0,0}}}
    }};
    for (int face=0;face<6;++face) {
        std::array<Vec3,4> points{};
        for (std::size_t i=0;i<4;++i) {
            const Vec3& p=kFaces[static_cast<std::size_t>(face)][i];
            points[i]={x0+(x1-x0)*p.x,y0+(y1-y0)*p.y,z0+(z1-z0)*p.z};
        }
        AddFace(out,points,FaceShade(face),TileForFace(def,face));
    }
}

void AppendCrossPlant(std::vector<WorldVertex>& out, float x, float y, float z,
                      const BlockDefinition& def) {
    float u0=0,v0=0,u1=1,v1=1; GetAtlasUv(def.tileSide,u0,v0,u1,v1);
    const auto appendDouble = [&out,u0,v0,u1,v1](Vec3 a,Vec3 b,Vec3 c,Vec3 d) {
        const Vec3 n=Normalize(Cross(Subtract(b,a),Subtract(c,a)));
        const std::array<Vec3,4> p{{a,b,c,d}};
        const std::array<std::array<float,2>,4> uv{{{{u0,v0}},{{u1,v0}},{{u1,v1}},{{u0,v1}}}};
        constexpr int order[6]={0,1,2,0,2,3};
        for (int i:order) { auto j=static_cast<std::size_t>(i); const auto& q=p[j]; out.push_back({q.x,q.y,q.z,n.x,n.y,n.z,1,1,1,uv[j][0],uv[j][1]}); }
        constexpr int backOrder[6]={0,2,1,0,3,2};
        for (int i:backOrder) { auto j=static_cast<std::size_t>(i); const auto& q=p[j]; out.push_back({q.x,q.y,q.z,-n.x,-n.y,-n.z,1,1,1,uv[j][0],uv[j][1]}); }
    };
    const float a=0.12f,b=0.88f;
    appendDouble({x+a,y,z+a},{x+a,y+0.9f,z+a},{x+b,y+0.9f,z+b},{x+b,y,z+b});
    appendDouble({x+b,y,z+a},{x+b,y+0.9f,z+a},{x+a,y+0.9f,z+b},{x+a,y,z+b});
}

void AppendBlockShape(std::vector<WorldVertex>& out, int x,int y,int z,const BlockDefinition& def) {
    const float fx=static_cast<float>(x), fy=static_cast<float>(y), fz=static_cast<float>(z);
    switch (def.shape) {
        case BlockShape::None: return;
        case BlockShape::CrossPlant:
            AppendCrossPlant(out,fx,fy,fz,def); return;
        case BlockShape::Slab:
            AppendBox(out,fx,fy,fz,fx+1.0f,fy+0.5f,fz+1.0f,def); return;
        case BlockShape::Stairs:
            AppendBox(out,fx,fy,fz,fx+1.0f,fy+0.5f,fz+1.0f,def);
            AppendBox(out,fx,fy+0.5f,fz+0.5f,fx+1.0f,fy+1.0f,fz+1.0f,def); return;
        case BlockShape::Thin:
            if (std::string_view(def.key) == "bamboo" || std::string_view(def.key) == "end_rod") {
                AppendBox(out,fx+0.39f,fy,fz+0.39f,fx+0.61f,fy+1.0f,fz+0.61f,def);
            } else if (std::string_view(def.key) == "waterlily") {
                AppendBox(out,fx+0.04f,fy+0.02f,fz+0.04f,fx+0.96f,fy+0.10f,fz+0.96f,def);
            } else if (std::string_view(def.key).find("torch")!=std::string_view::npos) {
                AppendBox(out,fx+0.42f,fy,fz+0.42f,fx+0.58f,fy+0.72f,fz+0.58f,def);
            } else if (std::string_view(def.key).find("rail")!=std::string_view::npos ||
                       std::string_view(def.key).find("carpet")!=std::string_view::npos ||
                       std::string_view(def.key).find("snow_layer")!=std::string_view::npos ||
                       std::string_view(def.key).find("pressure_plate")!=std::string_view::npos) {
                AppendBox(out,fx+0.03f,fy,fz+0.03f,fx+0.97f,fy+0.10f,fz+0.97f,def);
            } else if (std::string_view(def.key).find("ladder")!=std::string_view::npos ||
                       std::string_view(def.key).find("vine")!=std::string_view::npos) {
                AppendBox(out,fx+0.04f,fy+0.04f,fz+0.90f,fx+0.96f,fy+0.96f,fz+1.0f,def);
            } else {
                AppendBox(out,fx+0.15f,fy+0.15f,fz+0.15f,fx+0.85f,fy+0.30f,fz+0.85f,def);
            }
            return;
        case BlockShape::Pane:
            AppendBox(out,fx+0.43f,fy,fz,fx+0.57f,fy+1.0f,fz+1.0f,def);
            AppendBox(out,fx,fy,fz+0.43f,fx+1.0f,fy+1.0f,fz+0.57f,def); return;
        case BlockShape::Fence:
            AppendBox(out,fx+0.38f,fy,fz+0.38f,fx+0.62f,fy+1.0f,fz+0.62f,def);
            AppendBox(out,fx+0.10f,fy+0.30f,fz+0.43f,fx+0.90f,fy+0.42f,fz+0.57f,def);
            AppendBox(out,fx+0.10f,fy+0.62f,fz+0.43f,fx+0.90f,fy+0.74f,fz+0.57f,def);
            AppendBox(out,fx+0.43f,fy+0.30f,fz+0.10f,fx+0.57f,fy+0.42f,fz+0.90f,def);
            AppendBox(out,fx+0.43f,fy+0.62f,fz+0.10f,fx+0.57f,fy+0.74f,fz+0.90f,def); return;
        case BlockShape::Door:
            AppendBox(out,fx+0.03f,fy,fz+0.88f,fx+0.97f,fy+1.0f,fz+1.0f,def); return;
        case BlockShape::Trapdoor:
            AppendBox(out,fx,fy+0.82f,fz,fx+1.0f,fy+1.0f,fz+1.0f,def); return;
        case BlockShape::Fluid:
            AppendBox(out,fx,fy,fz,fx+1.0f,fy+0.88f,fz+1.0f,def); return;
        case BlockShape::Scaffolding:
            AppendBox(out,fx+0.06f,fy,fz+0.06f,fx+0.18f,fy+1.0f,fz+0.18f,def);
            AppendBox(out,fx+0.82f,fy,fz+0.06f,fx+0.94f,fy+1.0f,fz+0.18f,def);
            AppendBox(out,fx+0.06f,fy,fz+0.82f,fx+0.18f,fy+1.0f,fz+0.94f,def);
            AppendBox(out,fx+0.82f,fy,fz+0.82f,fx+0.94f,fy+1.0f,fz+0.94f,def);
            AppendBox(out,fx+0.12f,fy+0.78f,fz+0.12f,fx+0.88f,fy+0.90f,fz+0.88f,def); return;
        case BlockShape::Cube:
        default:
            AppendBox(out,fx,fy,fz,fx+1.0f,fy+1.0f,fz+1.0f,def); return;
    }
}

struct RayHit { int x{0},y{0},z{0}; int previousX{0},previousY{0},previousZ{0}; bool hit{false}; };
RayHit TraceVoxelRay(const VoxelWorld& world,const Vec3& origin,const Vec3& direction,float maxDistance) {
    RayHit result;
    const Vec3 dir=Normalize(direction);
    int x=static_cast<int>(std::floor(origin.x));
    int y=static_cast<int>(std::floor(origin.y));
    int z=static_cast<int>(std::floor(origin.z));
    result.previousX=x; result.previousY=y; result.previousZ=z;
    const int sx=(dir.x>0.0f)?1:((dir.x<0.0f)?-1:0);
    const int sy=(dir.y>0.0f)?1:((dir.y<0.0f)?-1:0);
    const int sz=(dir.z>0.0f)?1:((dir.z<0.0f)?-1:0);
    const float inf=std::numeric_limits<float>::infinity();
    const float dx=(sx==0)?inf:std::abs(1.0f/dir.x);
    const float dy=(sy==0)?inf:std::abs(1.0f/dir.y);
    const float dz=(sz==0)?inf:std::abs(1.0f/dir.z);
    float tx=(sx>0?(static_cast<float>(x+1)-origin.x):(origin.x-static_cast<float>(x)))*dx;
    float ty=(sy>0?(static_cast<float>(y+1)-origin.y):(origin.y-static_cast<float>(y)))*dy;
    float tz=(sz>0?(static_cast<float>(z+1)-origin.z):(origin.z-static_cast<float>(z)))*dz;
    if (sx == 0) tx = inf;
    if (sy == 0) ty = inf;
    if (sz == 0) tz = inf;
    float distance=0.0f;
    for(int step=0;step<4096 && distance<=maxDistance;++step) {
        const BlockId block=world.GetBlock(x,y,z);
        if(IsTargetable(block)) { result.x=x;result.y=y;result.z=z;result.hit=true;return result; }
        result.previousX=x;result.previousY=y;result.previousZ=z;
        if(tx<=ty && tx<=tz) { x+=sx;distance=tx;tx+=dx; }
        else if(ty<=tz) { y+=sy;distance=ty;ty+=dy; }
        else { z+=sz;distance=tz;tz+=dz; }
    }
    return result;
}

std::uint16_t RemapLegacyBlock(std::uint8_t oldId) {
    // MFVOX001 stored the first ten prototype IDs in a different order.
    static constexpr std::array<std::uint16_t,10> map{{0,2,3,1,12,17,24,9,16,15}};
    return oldId<map.size()?map[oldId]:0;
}
std::uint16_t RemapLegacyInventoryId(std::uint16_t oldId) {
    static constexpr std::array<std::uint16_t,10> map{{0,2,3,1,12,17,24,9,16,15}};
    return oldId<map.size()?map[oldId]:0;
}

} // namespace

VoxelWorld::VoxelWorld(std::uint32_t seed) : seed_(seed) {}

int VoxelWorld::FloorDiv(int value,int divisor) {
    int quotient=value/divisor; const int remainder=value%divisor;
    if(remainder!=0 && ((remainder<0)!=(divisor<0)))--quotient;
    return quotient;
}
int VoxelWorld::PositiveMod(int value,int divisor) {
    const int remainder=value%divisor; return remainder<0?remainder+divisor:remainder;
}
std::size_t VoxelWorld::Index(int x,int y,int z) {
    return static_cast<std::size_t>((y*kChunkSize+z)*kChunkSize+x);
}

float VoxelWorld::Noise2D(float x,float z) const {
    const int ix=static_cast<int>(std::floor(x)); const int iz=static_cast<int>(std::floor(z));
    const float fx=Smooth(x-static_cast<float>(ix)); const float fz=Smooth(z-static_cast<float>(iz));
    const auto lattice=[this](int px,int pz) {
        std::uint32_t h=seed_; h^=static_cast<std::uint32_t>(px)*0x9e3779b9u;
        h^=static_cast<std::uint32_t>(pz)*0x85ebca6bu;
        return static_cast<float>(Hash(h)&0x00ffffffu)/8388607.5f-1.0f;
    };
    const float a=Lerp(lattice(ix,iz),lattice(ix+1,iz),fx);
    const float b=Lerp(lattice(ix,iz+1),lattice(ix+1,iz+1),fx);
    return Lerp(a,b,fz);
}

VoxelWorld::Biome VoxelWorld::GetBiome(int x,int z) const {
    const float temperature=Noise2D(static_cast<float>(x)*0.009f+217.0f,static_cast<float>(z)*0.009f-71.0f);
    const float humidity=Noise2D(static_cast<float>(x)*0.013f-493.0f,static_cast<float>(z)*0.013f+119.0f);
    const float continental=Noise2D(static_cast<float>(x)*0.006f+901.0f,static_cast<float>(z)*0.006f+401.0f);
    if(continental<-0.47f)return Biome::Ocean;
    if(temperature>0.34f && humidity<-0.05f)return Biome::Desert;
    if(temperature<-0.34f)return Biome::Snow;
    if(humidity>0.22f)return Biome::Forest;
    return Biome::Plains;
}

int VoxelWorld::TerrainHeight(int x,int z) const {
    const float broad=Noise2D(static_cast<float>(x)*0.022f,static_cast<float>(z)*0.022f);
    const float detail=Noise2D(static_cast<float>(x)*0.075f+31.0f,static_cast<float>(z)*0.075f-17.0f);
    const float ridged=1.0f-std::abs(Noise2D(static_cast<float>(x)*0.041f-83.0f,static_cast<float>(z)*0.041f+29.0f));
    const Biome biome=GetBiome(x,z);
    float h=39.0f+broad*13.0f+detail*4.0f+ridged*3.5f;
    if(biome==Biome::Desert)h+=2.0f;
    if(biome==Biome::Snow)h+=4.0f;
    if(biome==Biome::Ocean)h=27.0f+broad*5.0f;
    return std::clamp(static_cast<int>(std::lround(h)),8,kWorldHeight-8);
}

VoxelWorld::Chunk VoxelWorld::MakeChunk(int chunkX,int chunkZ) const {
    Chunk chunk; chunk.x=chunkX;chunk.z=chunkZ;chunk.blocks.fill(BlockId::Air);
    for(int z=0;z<kChunkSize;++z)for(int x=0;x<kChunkSize;++x) {
        const int wx=chunkX*kChunkSize+x,wz=chunkZ*kChunkSize+z;
        const Biome biome=GetBiome(wx,wz); const int height=TerrainHeight(wx,wz);
        for(int y=0;y<=height;++y) {
            BlockId block=BlockId::Stone;
            if(y==height) {
                if(biome==Biome::Desert || biome==Biome::Ocean)block=BlockId::Sand;
                else if(biome==Biome::Snow)block=BlockId::Snow;
                else block=BlockId::Grass;
            } else if(y>=height-3) {
                block=(biome==Biome::Desert)?BlockId::Sand:BlockId::Dirt;
            } else {
                const float caveA=Noise2D(static_cast<float>(wx)*0.115f+static_cast<float>(y)*0.037f,
                                           static_cast<float>(wz)*0.115f-static_cast<float>(y)*0.031f);
                const float caveB=Noise2D(static_cast<float>(wx)*0.061f-static_cast<float>(y)*0.021f+45.0f,
                                           static_cast<float>(wz)*0.061f+static_cast<float>(y)*0.027f-12.0f);
                if(y>5 && caveA>0.68f && caveB>0.12f) block=BlockId::Air;
                else {
                    const float ore=Noise2D(static_cast<float>(wx)*0.29f+static_cast<float>(y)*0.13f,
                                            static_cast<float>(wz)*0.29f-static_cast<float>(y)*0.17f);
                    if(y<22 && ore>0.91f)block=BlockId::DiamondOre;
                    else if(y<28 && ore<-0.90f)block=BlockId::GoldOre;
                    else if(y<37 && ore>0.82f)block=BlockId::IronOre;
                    else if(y<48 && ore<-0.78f)block=BlockId::CoalOre;
                    else if(y<32 && ore>0.76f && caveB<-0.4f)block=BlockId::RedstoneOre;
                    else if(y<42 && ore<-0.83f && caveB>0.55f)block=BlockId::LapisOre;
                }
            }
            chunk.blocks[Index(x,y,z)]=block;
        }
        if(height<kSeaLevel && biome==Biome::Ocean) {
            for(int y=height+1;y<=kSeaLevel;++y)chunk.blocks[Index(x,y,z)]=BlockId::Water;
        }
        const std::uint32_t cellHash=Hash(seed_^static_cast<std::uint32_t>((wx/7)*0x45d9f3bu)^static_cast<std::uint32_t>((wz/7)*0x119de1f3u));
        const bool treeCell=(cellHash%9u)==0u && (x%7==3) && (z%7==3);
        if(treeCell && (biome==Biome::Forest || biome==Biome::Plains) && height+6<kWorldHeight) {
            for(int dy=1;dy<=4;++dy)chunk.blocks[Index(x,height+dy,z)]=BlockId::Log;
            for(int dy=3;dy<=6;++dy)for(int dz=-2;dz<=2;++dz)for(int dx=-2;dx<=2;++dx) {
                if(std::abs(dx)+std::abs(dz)+(dy==6?1:0)>4)continue;
                const int lx=x+dx,lz=z+dz;
                if(lx>=0&&lx<kChunkSize&&lz>=0&&lz<kChunkSize&&chunk.blocks[Index(lx,height+dy,lz)]==BlockId::Air)
                    chunk.blocks[Index(lx,height+dy,lz)]=BlockId::Leaves;
            }
        }
        if(biome==Biome::Desert && height+4<kWorldHeight && (cellHash%47u)==0u) {
            const int cactusHeight=2+static_cast<int>((cellHash>>8u)%3u);
            for(int dy=1;dy<=cactusHeight;++dy)chunk.blocks[Index(x,height+dy,z)]=BlockId::Cactus;
        }
    }
    return chunk;
}

const VoxelWorld::Chunk* VoxelWorld::FindChunk(int chunkX,int chunkZ) const {
    const auto it=chunks_.find({chunkX,chunkZ});return it==chunks_.end()?nullptr:&it->second;
}
VoxelWorld::Chunk* VoxelWorld::FindChunk(int chunkX,int chunkZ) {
    const auto it=chunks_.find({chunkX,chunkZ});return it==chunks_.end()?nullptr:&it->second;
}
void VoxelWorld::StartNextChunkJob() {
    // Keep two chunk builds in flight, leaving CPU capacity for rendering and input.
    while (pendingChunkJobs_.size() < kChunkWorkerCount && !generationQueue_.empty()) {
        const auto key = generationQueue_.front();
        generationQueue_.pop_front();
        const int chunkX = key.first;
        const int chunkZ = key.second;
        ChunkJob job;
        job.key = key;
        job.future = std::async(std::launch::async, [this, chunkX, chunkZ]() {
            return MakeChunk(chunkX, chunkZ);
        });
        pendingChunkJobs_.push_back(std::move(job));
    }
}

void VoxelWorld::RequestGenerateAround(int worldX, int worldZ, int radiusInChunks) {
    const int centerX = FloorDiv(worldX, kChunkSize);
    const int centerZ = FloorDiv(worldZ, kChunkSize);
    radiusInChunks = std::clamp(radiusInChunks, 0, 8);
    generationQueue_.clear();
    queuedChunks_.clear();

    std::vector<std::pair<int, int>> missing;
    for (int z = centerZ - radiusInChunks; z <= centerZ + radiusInChunks; ++z) {
        for (int x = centerX - radiusInChunks; x <= centerX + radiusInChunks; ++x) {
            const auto key = std::make_pair(x, z);
            if (chunks_.find(key) != chunks_.end()) continue;
            const bool alreadyBuilding = std::any_of(
                pendingChunkJobs_.begin(), pendingChunkJobs_.end(),
                [&key](const ChunkJob& job) { return job.key == key; });
            if (alreadyBuilding) continue;
            missing.push_back(key);
        }
    }
    std::sort(missing.begin(), missing.end(), [centerX, centerZ](const auto& a, const auto& b) {
        const int da = (a.first - centerX) * (a.first - centerX) + (a.second - centerZ) * (a.second - centerZ);
        const int db = (b.first - centerX) * (b.first - centerX) + (b.second - centerZ) * (b.second - centerZ);
        return da == db ? (a.second == b.second ? a.first < b.first : a.second < b.second) : da < db;
    });
    for (const auto& key : missing) {
        generationQueue_.push_back(key);
        queuedChunks_.insert(key);
    }
    StartNextChunkJob();
}

bool VoxelWorld::PumpChunkGeneration() {
    bool addedChunk = false;
    for (std::size_t i = 0; i < pendingChunkJobs_.size();) {
        ChunkJob& job = pendingChunkJobs_[i];
        if (!job.future.valid() ||
            job.future.wait_for(std::chrono::seconds(0)) == std::future_status::ready) {
            Chunk chunk = job.future.valid() ? job.future.get() : Chunk{};
            queuedChunks_.erase(job.key);
            const auto key = std::make_pair(chunk.x, chunk.z);
            if (chunks_.find(key) == chunks_.end() && job.future.valid()) {
                chunks_.emplace(key, std::move(chunk));
                addedChunk = true;
            }
            pendingChunkJobs_.erase(pendingChunkJobs_.begin() + static_cast<std::ptrdiff_t>(i));
        } else {
            ++i;
        }
    }
    StartNextChunkJob();
    return addedChunk;
}

void VoxelWorld::WaitForChunkGeneration() {
    while (!pendingChunkJobs_.empty() || !generationQueue_.empty()) {
        StartNextChunkJob();
        if (pendingChunkJobs_.empty()) continue;
        ChunkJob job = std::move(pendingChunkJobs_.front());
        pendingChunkJobs_.erase(pendingChunkJobs_.begin());
        if (job.future.valid()) {
            Chunk chunk = job.future.get();
            queuedChunks_.erase(job.key);
            const auto key = std::make_pair(chunk.x, chunk.z);
            if (chunks_.find(key) == chunks_.end()) chunks_.emplace(key, std::move(chunk));
        }
    }
}

std::size_t VoxelWorld::PendingChunkCount() const {
    return generationQueue_.size() + pendingChunkJobs_.size();
}

void VoxelWorld::GenerateAround(int worldX,int worldZ,int radiusInChunks) {
    WaitForChunkGeneration();
    generationQueue_.clear();
    queuedChunks_.clear();
    const int centerX=FloorDiv(worldX,kChunkSize),centerZ=FloorDiv(worldZ,kChunkSize);
    radiusInChunks=std::clamp(radiusInChunks,0,8);
    for(int z=centerZ-radiusInChunks;z<=centerZ+radiusInChunks;++z)
        for(int x=centerX-radiusInChunks;x<=centerX+radiusInChunks;++x)
            if(chunks_.find({x,z})==chunks_.end())chunks_.emplace(std::make_pair(x,z),MakeChunk(x,z));
}

BlockId VoxelWorld::GetBlock(int x,int y,int z) const {
    if(y<0||y>=kWorldHeight)return BlockId::Air;
    const int cx=FloorDiv(x,kChunkSize),cz=FloorDiv(z,kChunkSize);
    const Chunk* chunk=FindChunk(cx,cz);if(chunk==nullptr)return BlockId::Air;
    const BlockId block=chunk->blocks[Index(PositiveMod(x,kChunkSize),y,PositiveMod(z,kChunkSize))];
    return IsValidBlock(block)?block:BlockId::Air;
}

bool VoxelWorld::SetBlock(int x,int y,int z,BlockId block) {
    if(y<0||y>=kWorldHeight||!IsValidBlock(block))return false;
    Chunk* chunk=FindChunk(FloorDiv(x,kChunkSize),FloorDiv(z,kChunkSize));if(chunk==nullptr)return false;
    chunk->blocks[Index(PositiveMod(x,kChunkSize),y,PositiveMod(z,kChunkSize))]=block;
    return true;
}
bool VoxelWorld::IsSolid(int x,int y,int z) const {
    const BlockId block=GetBlock(x,y,z);return HasBlockFlag(block,BlockCollidable);
}
bool VoxelWorld::IsOpaque(int x,int y,int z) const {
    const BlockId block=GetBlock(x,y,z);return HasBlockFlag(block,BlockOpaque);
}
int VoxelWorld::GetSurfaceHeight(int x,int z) const {
    for(int y=kWorldHeight-1;y>=0;--y)if(IsSolid(x,y,z))return y;
    return 0;
}

bool VoxelWorld::GetRayTargetBlock(const Vec3& origin, const Vec3& direction, float maxDistance,
                                   Vec3* targetBlock, BlockId* targetType) const {
    if (maxDistance <= 0.0f) return false;
    const RayHit hit = TraceVoxelRay(*this, origin, direction, maxDistance);
    if (!hit.hit) return false;
    const BlockId block = GetBlock(hit.x, hit.y, hit.z);
    if (!IsTargetable(block)) return false;
    if (targetBlock != nullptr)
        *targetBlock = {static_cast<float>(hit.x), static_cast<float>(hit.y), static_cast<float>(hit.z)};
    if (targetType != nullptr) *targetType = block;
    return true;
}

bool VoxelWorld::BreakRay(const Vec3& origin,const Vec3& direction,float maxDistance,Vec3* brokenBlock,BlockId* brokenType) {
    if(maxDistance<=0.0f)return false;
    const RayHit hit=TraceVoxelRay(*this,origin,direction,maxDistance);if(!hit.hit)return false;
    const BlockId block=GetBlock(hit.x,hit.y,hit.z);
    const BlockDefinition* definition=GetBlockDefinition(block);
    // Unbreakable blocks (for example bedrock) remain protected by the world rules.
    if(definition==nullptr || definition->hardness<0.0f)return false;
    if(!SetBlock(hit.x,hit.y,hit.z,BlockId::Air))return false;
    if(brokenBlock)*brokenBlock={static_cast<float>(hit.x),static_cast<float>(hit.y),static_cast<float>(hit.z)};
    if(brokenType)*brokenType=block;
    return true;
}
bool VoxelWorld::PlaceRay(const Vec3& origin,const Vec3& direction,float maxDistance,BlockId block) {
    const BlockDefinition* definition=GetBlockDefinition(block);
    if(maxDistance<=0.0f||definition==nullptr||(definition->flags&BlockPlaceable)==0u)return false;
    const RayHit hit=TraceVoxelRay(*this,origin,direction,maxDistance);if(!hit.hit)return false;
    if(hit.previousY<0||hit.previousY>=kWorldHeight||IsSolid(hit.previousX,hit.previousY,hit.previousZ))return false;
    return SetBlock(hit.previousX,hit.previousY,hit.previousZ,block);
}

void VoxelWorld::BuildMesh(std::vector<WorldVertex>& output,int centerWorldX,int centerWorldZ,int radiusInChunks,
                           std::vector<WorldVertex>* transparentOutput) const {
    output.clear();
    if (transparentOutput != nullptr) transparentOutput->clear();
    const int centerChunkX=FloorDiv(centerWorldX,kChunkSize),centerChunkZ=FloorDiv(centerWorldZ,kChunkSize);
    const std::size_t reserveChunks=radiusInChunks>=0?static_cast<std::size_t>((radiusInChunks*2+1)*(radiusInChunks*2+1)):chunks_.size();
    output.reserve(std::min(chunks_.size(),reserveChunks)*14000u);
    if (transparentOutput != nullptr)
        transparentOutput->reserve(std::min(chunks_.size(), reserveChunks) * 1800u);
    static constexpr std::array<std::array<Vec3,4>,6> faces{{
        {{{1,0,0},{1,1,0},{1,1,1},{1,0,1}}},{{{0,0,1},{0,1,1},{0,1,0},{0,0,0}}},
        {{{0,1,1},{1,1,1},{1,1,0},{0,1,0}}},{{{0,0,0},{1,0,0},{1,0,1},{0,0,1}}},
        {{{1,0,1},{1,1,1},{0,1,1},{0,0,1}}},{{{0,0,0},{0,1,0},{1,1,0},{1,0,0}}}
    }};
    static constexpr int offsets[6][3]={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    for(const auto& entry:chunks_) {
        if(radiusInChunks>=0&&(std::abs(entry.first.first-centerChunkX)>radiusInChunks||std::abs(entry.first.second-centerChunkZ)>radiusInChunks))continue;
        const Chunk& chunk=entry.second;const int baseX=chunk.x*kChunkSize,baseZ=chunk.z*kChunkSize;
        for(int y=0;y<kWorldHeight;++y)for(int z=0;z<kChunkSize;++z)for(int x=0;x<kChunkSize;++x) {
            const BlockId block=chunk.blocks[Index(x,y,z)];if(block==BlockId::Air)continue;
            const BlockDefinition* def=GetBlockDefinition(block);if(def==nullptr||def->shape==BlockShape::None)continue;
            const bool transparent = (def->flags & (BlockTransparent | BlockFluid)) != 0u;
            std::vector<WorldVertex>& destination =
                (transparent && transparentOutput != nullptr) ? *transparentOutput : output;
            const int wx=baseX+x,wz=baseZ+z;
            if(def->shape!=BlockShape::Cube) { AppendBlockShape(destination,wx,y,wz,*def); continue; }
            for(int face=0;face<6;++face) {
                const BlockId neighbor=GetBlock(wx+offsets[face][0],y+offsets[face][1],wz+offsets[face][2]);
                if(ShouldHideFace(block,neighbor))continue;
                std::array<Vec3,4> points{};
                for(std::size_t i=0;i<4;++i) {
                    const Vec3& p=faces[static_cast<std::size_t>(face)][i];
                    points[i]={static_cast<float>(wx)+p.x,static_cast<float>(y)+p.y,static_cast<float>(wz)+p.z};
                }
                AddFace(destination,points,FaceShade(face),TileForFace(*def,face));
            }
        }
    }
}

std::size_t VoxelWorld::CountNonAirBlocks() const {
    std::size_t total=0;for(const auto& pair:chunks_)for(BlockId id:pair.second.blocks)if(id!=BlockId::Air)++total;return total;
}

bool VoxelWorld::Save(const std::string& filePath,const Vec3& playerPosition,
                      const std::array<std::uint32_t,9>& inventory) const {
    if(chunks_.size()>kMaxSavedChunks||!std::isfinite(playerPosition.x)||!std::isfinite(playerPosition.y)||!std::isfinite(playerPosition.z))return false;
    try {
        const std::filesystem::path path(filePath);if(path.has_parent_path())std::filesystem::create_directories(path.parent_path());
        const std::filesystem::path temp=path.string()+".tmp";
        std::ofstream file(temp,std::ios::binary|std::ios::trunc);if(!file)return false;
        constexpr char magic[8]={'M','F','V','O','X','0','0','2'};
        const std::uint32_t count=static_cast<std::uint32_t>(chunks_.size());
        file.write(magic,sizeof(magic));file.write(reinterpret_cast<const char*>(&seed_),sizeof(seed_));
        file.write(reinterpret_cast<const char*>(&playerPosition),sizeof(playerPosition));
        file.write(reinterpret_cast<const char*>(inventory.data()),static_cast<std::streamsize>(inventory.size()*sizeof(inventory[0])));
        file.write(reinterpret_cast<const char*>(&count),sizeof(count));
        for(const auto& pair:chunks_) {
            const Chunk& chunk=pair.second;
            file.write(reinterpret_cast<const char*>(&chunk.x),sizeof(chunk.x));file.write(reinterpret_cast<const char*>(&chunk.z),sizeof(chunk.z));
            file.write(reinterpret_cast<const char*>(chunk.blocks.data()),static_cast<std::streamsize>(chunk.blocks.size()*sizeof(BlockId)));
        }
        file.flush();if(!file){file.close();std::filesystem::remove(temp);return false;}file.close();
        std::error_code ec;std::filesystem::rename(temp,path,ec);
        if(ec){std::filesystem::remove(path,ec);ec.clear();std::filesystem::rename(temp,path,ec);}
        if(ec){std::filesystem::remove(temp);return false;}
        return true;
    } catch(...) { return false; }
}

bool VoxelWorld::Load(const std::string& filePath,Vec3& playerPosition,
                      std::array<std::uint32_t,9>& inventory) {
    WaitForChunkGeneration();
    generationQueue_.clear();
    queuedChunks_.clear();
    std::ifstream file(filePath,std::ios::binary);if(!file)return false;
    char magic[8]{};file.read(magic,sizeof(magic));if(!file)return false;
    const bool legacy=std::memcmp(magic,"MFVOX001",8)==0;
    if(!legacy&&std::memcmp(magic,"MFVOX002",8)!=0)return false;
    std::uint32_t loadedSeed=0,count=0;Vec3 loadedPlayer{};std::array<std::uint32_t,9> loadedInventory{};
    file.read(reinterpret_cast<char*>(&loadedSeed),sizeof(loadedSeed));file.read(reinterpret_cast<char*>(&loadedPlayer),sizeof(loadedPlayer));
    if(legacy) {
        std::array<std::uint16_t,9> old{};file.read(reinterpret_cast<char*>(old.data()),static_cast<std::streamsize>(sizeof(old)));
        for(std::size_t i=0;i<old.size();++i){const std::uint16_t oldId=old[i]/1000u,amount=old[i]%1000u;loadedInventory[i]=static_cast<std::uint32_t>(RemapLegacyInventoryId(oldId))*1000u+amount;}
    } else file.read(reinterpret_cast<char*>(loadedInventory.data()),static_cast<std::streamsize>(sizeof(loadedInventory)));
    file.read(reinterpret_cast<char*>(&count),sizeof(count));
    if(!file||count>kMaxSavedChunks||!std::isfinite(loadedPlayer.x)||!std::isfinite(loadedPlayer.y)||!std::isfinite(loadedPlayer.z))return false;
    std::map<std::pair<int,int>,Chunk> loadedChunks;
    for(std::uint32_t i=0;i<count;++i) {
        Chunk chunk;file.read(reinterpret_cast<char*>(&chunk.x),sizeof(chunk.x));file.read(reinterpret_cast<char*>(&chunk.z),sizeof(chunk.z));if(!file)return false;
        if(legacy) {
            std::array<std::uint8_t,kChunkVolume> oldBlocks{};file.read(reinterpret_cast<char*>(oldBlocks.data()),static_cast<std::streamsize>(oldBlocks.size()));if(!file)return false;
            for(std::size_t n=0;n<oldBlocks.size();++n)chunk.blocks[n]=static_cast<BlockId>(RemapLegacyBlock(oldBlocks[n]));
        } else {
            file.read(reinterpret_cast<char*>(chunk.blocks.data()),static_cast<std::streamsize>(chunk.blocks.size()*sizeof(BlockId)));if(!file)return false;
            for(BlockId& block:chunk.blocks)if(!IsValidBlock(block))block=BlockId::Air;
        }
        if(!loadedChunks.emplace(std::make_pair(chunk.x,chunk.z),std::move(chunk)).second)return false;
    }
    if(file.peek()!=std::char_traits<char>::eof())return false;
    for (std::uint32_t& encoded : loadedInventory) {
        if (encoded == 0u) continue;
        const std::uint32_t itemId = encoded / 1000u;
        const std::uint32_t quantity = encoded % 1000u;
        const BlockDefinition* definition = itemId < GetBlockDefinitionCount()
            ? GetBlockDefinition(static_cast<BlockId>(itemId)) : nullptr;
        if (definition == nullptr || quantity == 0u || (definition->stackLimit > 0u && quantity > definition->stackLimit)) {
            encoded = 0u;
        }
    }
    seed_=loadedSeed;chunks_=std::move(loadedChunks);playerPosition=loadedPlayer;inventory=loadedInventory;return true;
}

void AppendCube(std::vector<WorldVertex>& output,const Vec3& center,const Vec3& half,const std::array<float,3>& color) {
    const float x0=center.x-half.x,x1=center.x+half.x,y0=center.y-half.y,y1=center.y+half.y,z0=center.z-half.z,z1=center.z+half.z;
    const auto def=GetBlockDefinition(BlockId::Stone);if(def==nullptr)return;
    AppendBox(output,x0,y0,z0,x1,y1,z1,*def);
    if(color[0]<0.999f||color[1]<0.999f||color[2]<0.999f) {
        // Legacy entity tint is retained as a post-process over just-appended vertices.
        const std::size_t begin=output.size()>=36u?output.size()-36u:0u;
        for(std::size_t i=begin;i<output.size();++i){output[i].r*=color[0];output[i].g*=color[1];output[i].b*=color[2];}
    }
}

} // namespace minefade
