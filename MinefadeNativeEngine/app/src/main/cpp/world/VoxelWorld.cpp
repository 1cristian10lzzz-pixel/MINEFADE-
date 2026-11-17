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
#include <system_error>
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
    // Keep only a subtle baked face tint. OpenGL ES supplies the main
    // directional/hemisphere lighting; strong baked multipliers made side and
    // underside faces nearly black on lower-end mobile GPUs.
    switch (face) {
        case 2: return {1.00f, 1.00f, 1.00f}; // top
        case 3: return {0.88f, 0.88f, 0.88f}; // bottom
        case 0: case 1: return {0.94f, 0.94f, 0.94f};
        default: return {0.97f, 0.97f, 0.97f};
    }
}

void AddFace(std::vector<WorldVertex>& out, const std::array<Vec3, 4>& p,
             const std::array<float, 3>& color, std::uint16_t tile,
             float repeatU = 1.0f, float repeatV = 1.0f) {
    const Vec3 normal = Normalize(Cross(Subtract(p[1], p[0]), Subtract(p[2], p[0])));
    const std::array<std::array<float,2>,4> uv{{
        {{0.0f,0.0f}},{{repeatU,0.0f}},{{repeatU,repeatV}},{{0.0f,repeatV}}
    }};
    constexpr int order[6] = {0, 1, 2, 0, 2, 3};
    for (int i : order) {
        const std::size_t index = static_cast<std::size_t>(i);
        const Vec3& v = p[index];
        out.push_back({v.x,v.y,v.z,normal.x,normal.y,normal.z,
                       color[0],color[1],color[2],uv[index][0],uv[index][1],
                       static_cast<float>(tile)});
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
        std::array<float, 3> shade = FaceShade(face);
        if ((def.flags & BlockFluid) != 0u) {
            // The renderer recognises these material tags and animates only the
            // exposed top vertices, while retaining separate water/lava palettes.
            const bool lava = std::string_view(def.key).find("lava") != std::string_view::npos;
            shade = lava ? std::array<float,3>{-2.0f, 0.0f, 0.0f}
                         : std::array<float,3>{-1.0f, 0.0f, 0.0f};
        }
        AddFace(out,points,shade,TileForFace(def,face));
    }
}

void AppendCrossPlant(std::vector<WorldVertex>& out, float x, float y, float z,
                      const BlockDefinition& def) {
    const auto appendDouble = [&out,tile=def.tileSide](Vec3 a,Vec3 b,Vec3 c,Vec3 d) {
        const Vec3 n=Normalize(Cross(Subtract(b,a),Subtract(c,a)));
        const std::array<Vec3,4> p{{a,b,c,d}};
        const std::array<std::array<float,2>,4> uv{{{{0,0}},{{1,0}},{{1,1}},{{0,1}}}};
        constexpr int order[6]={0,1,2,0,2,3};
        for (int i:order) {
            const auto j=static_cast<std::size_t>(i); const auto& q=p[j];
            out.push_back({q.x,q.y,q.z,n.x,n.y,n.z,1,1,1,uv[j][0],uv[j][1],static_cast<float>(tile)});
        }
        constexpr int backOrder[6]={0,2,1,0,3,2};
        for (int i:backOrder) {
            const auto j=static_cast<std::size_t>(i); const auto& q=p[j];
            out.push_back({q.x,q.y,q.z,-n.x,-n.y,-n.z,1,1,1,uv[j][0],uv[j][1],static_cast<float>(tile)});
        }
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
    const int ix=static_cast<int>(std::floor(x));
    const int iz=static_cast<int>(std::floor(z));
    const float fx=x-static_cast<float>(ix), fz=z-static_cast<float>(iz);
    // Gradient Perlin noise avoids the square-cell look of the original value
    // noise while remaining deterministic across chunks and Android ABIs.
    const auto gradient=[this](int px,int pz,float dx,float dz) {
        std::uint32_t h=seed_;
        h^=static_cast<std::uint32_t>(px)*0x9e3779b9u;
        h^=static_cast<std::uint32_t>(pz)*0x85ebca6bu;
        static constexpr std::array<std::array<float,2>,8> directions{{
            {{1.0f,0.0f}},{{-1.0f,0.0f}},{{0.0f,1.0f}},{{0.0f,-1.0f}},
            {{0.7071067f,0.7071067f}},{{-0.7071067f,0.7071067f}},
            {{0.7071067f,-0.7071067f}},{{-0.7071067f,-0.7071067f}}
        }};
        const auto& g=directions[Hash(h)&7u];
        return g[0]*dx+g[1]*dz;
    };
    const float u=fx*fx*fx*(fx*(fx*6.0f-15.0f)+10.0f);
    const float v=fz*fz*fz*(fz*(fz*6.0f-15.0f)+10.0f);
    const float n00=gradient(ix,iz,fx,fz);
    const float n10=gradient(ix+1,iz,fx-1.0f,fz);
    const float n01=gradient(ix,iz+1,fx,fz-1.0f);
    const float n11=gradient(ix+1,iz+1,fx-1.0f,fz-1.0f);
    return std::clamp(Lerp(Lerp(n00,n10,u),Lerp(n01,n11,u),v)*1.4142135f,-1.0f,1.0f);
}

float VoxelWorld::FractalNoise2D(float x,float z,int octaves,float persistence,float lacunarity) const {
    float amplitude=1.0f,frequency=1.0f,total=0.0f,normalizer=0.0f;
    const int count=std::clamp(octaves,1,6);
    for(int octave=0;octave<count;++octave) {
        total+=Noise2D(x*frequency,z*frequency)*amplitude;
        normalizer+=amplitude;
        amplitude*=std::clamp(persistence,0.2f,0.85f);
        frequency*=std::clamp(lacunarity,1.5f,3.0f);
    }
    return normalizer>0.0f?std::clamp(total/normalizer,-1.0f,1.0f):0.0f;
}

VoxelWorld::Biome VoxelWorld::GetBiome(int x,int z) const {
    // Climate, erosion and continentalness are sampled at independent scales.
    // Broad fields define biome provinces; ridged and detail fields only modify
    // local shape so boundaries do not dissolve into noisy checkerboard patches.
    const float fx=static_cast<float>(x),fz=static_cast<float>(z);
    const float temperature=FractalNoise2D(fx*0.0028f+217.0f,fz*0.0028f-71.0f,3,0.52f,2.0f);
    const float humidity=FractalNoise2D(fx*0.0042f-493.0f,fz*0.0042f+119.0f,3,0.54f,2.0f);
    const float continental=FractalNoise2D(fx*0.0019f+901.0f,fz*0.0019f+401.0f,4,0.56f,2.0f);
    const float mountainField=FractalNoise2D(fx*0.009f-83.0f,fz*0.009f+29.0f,3,0.50f,2.1f);
    const float riverWarp=Noise2D(fx*0.0022f+173.0f,fz*0.0022f-311.0f)*0.75f;
    const float riverField=Noise2D(fx*0.0105f+riverWarp*2.0f+57.0f,
                                   fz*0.0105f-riverWarp*2.0f-93.0f);

    if(continental<-0.50f)return Biome::Ocean;
    if(continental>-0.30f&&continental<0.43f&&std::abs(riverField)<0.026f)
        return Biome::River;
    if(continental>-0.55f&&continental<-0.39f&&std::abs(riverField)>0.045f)
        return Biome::Beach;
    if(temperature<-0.52f&&mountainField>0.12f)return Biome::IceSpikes;
    if(temperature>0.38f&&humidity<-0.14f)return Biome::Badlands;
    if(temperature>0.27f&&humidity>0.42f&&continental<0.24f)return Biome::Jungle;
    if(temperature>0.16f&&humidity>0.47f&&continental<0.10f)return Biome::MangroveSwamp;
    if(humidity>0.48f&&temperature>-0.12f&&continental<0.24f)return Biome::Swamp;
    if(temperature<-0.15f&&humidity>0.08f)return Biome::Taiga;
    if(temperature>0.02f&&temperature<0.38f&&humidity>0.17f&&humidity<0.44f&&mountainField>0.02f)
        return Biome::CherryGrove;
    if(continental>0.38f&&mountainField>0.24f&&temperature>0.10f)return Biome::StonyPeaks;
    if(continental>0.38f&&mountainField>-0.10f)return Biome::Mountains;
    if(temperature>0.34f&&humidity<-0.04f)return Biome::Desert;
    if(temperature<-0.36f)return Biome::Snow;
    if(humidity>0.53f)return Biome::DarkForest;
    if(humidity>0.22f)return Biome::BirchForest;
    if(temperature>0.20f&&humidity<0.12f)return Biome::Savanna;
    if(humidity>0.08f)return Biome::Forest;
    if(temperature>-0.05f&&humidity<-0.20f&&continental>-0.18f)return Biome::MushroomFields;
    return Biome::Plains;
}

int VoxelWorld::TerrainHeight(int x,int z) const {
    const float fx=static_cast<float>(x),fz=static_cast<float>(z);
    const float continental=FractalNoise2D(fx*0.0019f+901.0f,fz*0.0019f+401.0f,4,0.56f,2.0f);
    const float erosion=FractalNoise2D(fx*0.0075f+31.0f,fz*0.0075f-17.0f,3,0.48f,2.1f);
    const float peakNoise=FractalNoise2D(fx*0.017f-83.0f,fz*0.017f+29.0f,3,0.56f,2.05f);
    const float detail=FractalNoise2D(fx*0.061f+151.0f,fz*0.061f-97.0f,2,0.36f,2.0f);
    const float ridged=1.0f-std::abs(peakNoise);
    const Biome biome=GetBiome(x,z);

    // Continental shelves, erosion, ridge noise and small octave detail form
    // broad coastlines, rolling hills, sharper mountain spines and local variation.
    const float continentalMask=std::clamp((continental+0.18f)*1.65f,0.0f,1.0f);
    const float mountainMask=std::clamp((continental-0.13f)*2.25f,0.0f,1.0f);
    float height=44.0f+continental*16.0f+erosion*8.5f
               +continentalMask*4.0f+mountainMask*(ridged*34.0f-5.0f)
               +detail*2.6f;

    if(biome==Biome::Desert||biome==Biome::Savanna||biome==Biome::Badlands)height+=1.5f;
    if(biome==Biome::Snow)height+=3.0f;
    if(biome==Biome::IceSpikes)height+=11.0f+std::max(0.0f,peakNoise)*13.0f;
    if(biome==Biome::StonyPeaks)height+=8.0f+ridged*7.0f;
    if(biome==Biome::Badlands)height=48.0f+continental*5.0f+std::abs(erosion)*4.0f+detail*1.3f;
    if(biome==Biome::MushroomFields)height=42.0f+erosion*2.2f+detail*1.1f;
    if(biome==Biome::Swamp||biome==Biome::MangroveSwamp)height=33.4f+erosion*1.9f+detail*0.7f;
    if(biome==Biome::Beach)height=29.0f+erosion*1.0f+detail*0.45f;
    if(biome==Biome::River)height=30.2f+erosion*1.1f+detail*0.45f;
    if(biome==Biome::Ocean)height=22.5f+continental*4.5f+erosion*1.8f;
    return std::clamp(static_cast<int>(std::lround(height)),5,kWorldHeight-7);
}

VoxelWorld::Chunk VoxelWorld::MakeChunk(int chunkX,int chunkZ) const {
    Chunk chunk; chunk.x=chunkX;chunk.z=chunkZ;chunk.blocks.fill(BlockId::Air);
    for(int z=0;z<kChunkSize;++z)for(int x=0;x<kChunkSize;++x) {
        const int wx=chunkX*kChunkSize+x,wz=chunkZ*kChunkSize+z;
        const Biome biome=GetBiome(wx,wz); const int height=TerrainHeight(wx,wz);
        for(int y=0;y<=height;++y) {
            BlockId block = y < 17 ? BlockId::Deepslate : BlockId::Stone;
            if(y==height) {
                if(biome==Biome::Desert || biome==Biome::Ocean || biome==Biome::Beach) block=BlockId::Sand;
                else if(biome==Biome::River) block=BlockId::Gravel;
                else if(biome==Biome::Badlands) block=BlockId::HardenedClay;
                else if(biome==Biome::MushroomFields) block=BlockId::Mycelium;
                else if(biome==Biome::MangroveSwamp) block=BlockId::Mud;
                else if(biome==Biome::IceSpikes) block=BlockId::PackedIce;
                else if(biome==Biome::Snow || (biome==Biome::Mountains && height>69)) block=BlockId::Snow;
                else if(biome==Biome::StonyPeaks && height>63) block=BlockId::Stone;
                else block=BlockId::Grass;
            } else if(y>=height-3) {
                block=(biome==Biome::Desert || biome==Biome::Savanna || biome==Biome::Beach)?BlockId::Sand:
                      biome==Biome::Badlands?BlockId::RedSandstone:
                      biome==Biome::MangroveSwamp?BlockId::Mud:BlockId::Dirt;
            } else {
                // Two differently scaled cave fields make winding chambers and
                // connecting passages. The vertical term opens larger caverns
                // deeper underground while preserving a solid surface roof.
                const float caveA=Noise2D(static_cast<float>(wx)*0.086f+static_cast<float>(y)*0.029f,
                                           static_cast<float>(wz)*0.086f-static_cast<float>(y)*0.033f);
                const float caveB=Noise2D(static_cast<float>(wx)*0.047f-static_cast<float>(y)*0.018f+45.0f,
                                           static_cast<float>(wz)*0.047f+static_cast<float>(y)*0.023f-12.0f);
                const float caveGate=0.63f+static_cast<float>(y)*0.0007f;
                const float cavern=Noise2D(static_cast<float>(wx)*0.031f+static_cast<float>(y)*0.014f+81.0f,
                                           static_cast<float>(wz)*0.031f-static_cast<float>(y)*0.016f-46.0f);
                const bool broadCavern=y>10&&cavern>0.57f&&caveB>-0.20f;
                const bool windingTunnel=y>6&&caveA>caveGate&&caveB>0.08f;
                if(broadCavern||windingTunnel) {
                    block=BlockId::Air;
                    const float caveClimate=Noise2D(static_cast<float>(wx)*0.013f+49.0f,
                                                     static_cast<float>(wz)*0.013f-83.0f);
                    if(y<38 && biome==Biome::Jungle && caveClimate>0.68f && (Hash(seed_^static_cast<std::uint32_t>(wx*31+wz*17+y*7))%17u)==0u)
                        block=BlockId::MossyCobblestone;
                    else if(y<30 && caveClimate>0.77f && cavern>0.62f && (Hash(seed_^static_cast<std::uint32_t>(wx*13+wz*29+y*11))%29u)==0u)
                        block=BlockId::Calcite;
                    else if(y<14 && caveClimate<-0.70f && cavern>0.60f && (Hash(seed_^static_cast<std::uint32_t>(wx*19+wz*23+y*5))%31u)==0u)
                        block=BlockId::Sculk;
                } else {
                    const float ore=Noise2D(static_cast<float>(wx)*0.29f+static_cast<float>(y)*0.13f,
                                            static_cast<float>(wz)*0.29f-static_cast<float>(y)*0.17f);
                    if (y < 17) {
                        // The deep layer is visually and mechanically distinct.
                        if(ore>0.91f) block=BlockId::DeepslateDiamondOre;
                        else if(ore<-0.90f) block=BlockId::DeepslateGoldOre;
                        else if(ore>0.82f) block=BlockId::DeepslateIronOre;
                        else if(ore<-0.78f) block=BlockId::DeepslateCoalOre;
                        else if(y<14 && ore>0.76f && caveB<-0.4f) block=BlockId::DeepslateRedstoneOre;
                        else if(ore<-0.83f && caveB>0.55f) block=BlockId::DeepslateLapisOre;
                        else if(ore>0.75f && caveB>0.55f) block=BlockId::DeepslateEmeraldOre;
                        else if(ore>-0.74f && ore<-0.70f && caveB<-0.25f) block=BlockId::DeepslateCopperOre;
                    } else {
                        if(y<18 && ore>0.91f)block=BlockId::DiamondOre;
                        else if(y<27 && ore<-0.90f)block=BlockId::GoldOre;
                        else if(y<42 && ore>0.82f)block=BlockId::IronOre;
                        else if(y<55 && ore<-0.78f)block=BlockId::CoalOre;
                        else if(y<31 && ore>0.76f && caveB<-0.4f)block=BlockId::RedstoneOre;
                        else if(y<39 && ore<-0.83f && caveB>0.55f)block=BlockId::LapisOre;
                        else if(y<58 && ore>0.69f && ore<0.73f)block=BlockId::CopperOre;
                    }
                }
            }
            chunk.blocks[Index(x,y,z)]=block;
        }
        // Oceans and low-lying wetlands fill to the common water table.
        if(height<kSeaLevel && (biome==Biome::Ocean || biome==Biome::Swamp || biome==Biome::MangroveSwamp || biome==Biome::River || biome==Biome::Beach)) {
            for(int y=height+1;y<=kSeaLevel;++y)chunk.blocks[Index(x,y,z)]=BlockId::Water;
        }
        const std::uint32_t cellHash=Hash(seed_^static_cast<std::uint32_t>((wx/7)*0x45d9f3bu)^static_cast<std::uint32_t>((wz/7)*0x119de1f3u));
        const bool treeCell=(cellHash%9u)==0u&&(x%7==3)&&(z%7==3);
        const bool forestBiome=biome==Biome::Forest||biome==Biome::Plains||biome==Biome::Swamp||
            biome==Biome::Taiga||biome==Biome::BirchForest||biome==Biome::DarkForest||
            biome==Biome::Jungle||biome==Biome::CherryGrove||biome==Biome::MangroveSwamp;
        if(treeCell&&forestBiome&&height+9<kWorldHeight) {
            const bool cherry=biome==Biome::CherryGrove;
            const bool mangrove=biome==Biome::MangroveSwamp;
            const int trunkHeight=(biome==Biome::Jungle?7:4)+static_cast<int>((cellHash>>9u)%3u);
            const BlockId trunk=cherry?BlockId::CherryLog:(mangrove?BlockId::MangroveLog:BlockId::Log);
            const BlockId leaves=cherry?BlockId::CherryLeaves:(mangrove?BlockId::MangroveLeaves:
                ((biome==Biome::Taiga||biome==Biome::DarkForest)?BlockId::Leaves2:BlockId::Leaves));
            for(int dy=1;dy<=trunkHeight;++dy)chunk.blocks[Index(x,height+dy,z)]=trunk;
            const int crownRadius=biome==Biome::Jungle?3:2;
            for(int dy=trunkHeight-1;dy<=trunkHeight+2;++dy)
                for(int dz=-crownRadius;dz<=crownRadius;++dz)for(int dx=-crownRadius;dx<=crownRadius;++dx) {
                    const int radius=std::abs(dx)+std::abs(dz)+(dy==trunkHeight+2?1:0);
                    if(radius>crownRadius*2)continue;
                    const int lx=x+dx,lz=z+dz;
                    if(lx>=0&&lx<kChunkSize&&lz>=0&&lz<kChunkSize&&
                       chunk.blocks[Index(lx,height+dy,lz)]==BlockId::Air)
                        chunk.blocks[Index(lx,height+dy,lz)]=leaves;
                }
            if(mangrove&&height>kSeaLevel-1) {
                for(int dx=-1;dx<=1;++dx)for(int dz=-1;dz<=1;++dz)
                    if((dx==0)!=(dz==0)) {
                        const int lx=x+dx,lz=z+dz;
                        if(lx>=0&&lx<kChunkSize&&lz>=0&&lz<kChunkSize)
                            chunk.blocks[Index(lx,height-1,lz)]=BlockId::MuddyMangroveRoots;
                    }
            }
        }
        // Small ground cover and mineral rubble diversify plains, taiga-like
        // clearings and rocky highlands without forcing expensive structure jobs.
        if((cellHash%31u)==0u&&(biome==Biome::Forest||biome==Biome::Plains||biome==Biome::BirchForest||biome==Biome::CherryGrove)&&
           height+1<kWorldHeight&&chunk.blocks[Index(x,height+1,z)]==BlockId::Air) {
            chunk.blocks[Index(x,height+1,z)]=(cellHash&0x100u)?BlockId::YellowFlower:BlockId::RedFlower;
        }
        if(biome==Biome::MushroomFields && height+1<kWorldHeight && (cellHash%23u)==0u &&
           chunk.blocks[Index(x,height+1,z)]==BlockId::Air)
            chunk.blocks[Index(x,height+1,z)]=(cellHash&0x200u)?BlockId::RedMushroom:BlockId::BrownMushroom;
        if(biome==Biome::IceSpikes && height+7<kWorldHeight && (cellHash%41u)==0u) {
            const int spike=3+static_cast<int>((cellHash>>10u)%5u);
            for(int dy=1;dy<=spike;++dy)
                chunk.blocks[Index(x,height+dy,z)]=(dy%3==0)?BlockId::BlueIce:BlockId::PackedIce;
        }
        if(biome==Biome::Badlands && height+4<kWorldHeight && (cellHash%39u)==0u) {
            for(int dy=1;dy<=3;++dy)chunk.blocks[Index(x,height+dy,z)]=BlockId::HardenedClay;
        }
        if((cellHash%127u)==0u&&biome==Biome::Mountains&&height+2<kWorldHeight) {
            chunk.blocks[Index(x,height+1,z)]=BlockId::Cobblestone;
            if(x+1<kChunkSize)chunk.blocks[Index(x+1,height+1,z)]=BlockId::Stone;
            if(z+1<kChunkSize)chunk.blocks[Index(x,height+1,z+1)]=BlockId::Cobblestone;
        }
        if((biome==Biome::Desert || biome==Biome::Savanna) && height+4<kWorldHeight && (cellHash%47u)==0u) {
            const int cactusHeight=2+static_cast<int>((cellHash>>8u)%3u);
            for(int dy=1;dy<=cactusHeight;++dy)chunk.blocks[Index(x,height+dy,z)]=BlockId::Cactus;
        }
    }

    // Deterministic surface ruins (phase 3). Each ruin is wholly contained in
    // its owning chunk, so loading chunks in a different order cannot leave
    // half-structures at borders. No existing block IDs or save formats change.
    const int ruinCenterX = chunkX * kChunkSize + kChunkSize / 2;
    const int ruinCenterZ = chunkZ * kChunkSize + kChunkSize / 2;
    const Biome ruinBiome = GetBiome(ruinCenterX, ruinCenterZ);
    const bool dryLand = ruinBiome != Biome::Ocean && ruinBiome != Biome::River &&
        ruinBiome != Biome::Beach && ruinBiome != Biome::Swamp &&
        ruinBiome != Biome::MangroveSwamp;
    const std::uint32_t ruinHash = Hash(seed_ ^
        (static_cast<std::uint32_t>(chunkX) * 0x9e3779b9u) ^
        (static_cast<std::uint32_t>(chunkZ) * 0x85ebca6bu) ^ 0x5255494eu);
    const int ruinBaseY = TerrainHeight(ruinCenterX, ruinCenterZ);
    if (dryLand && ruinBaseY > kSeaLevel + 2 && ruinHash % 9u == 0u &&
        ruinBaseY + 4 < kWorldHeight) {
        constexpr int kMin = 5;
        constexpr int kMax = 11;
        for (int z = kMin; z <= kMax; ++z) {
            for (int x = kMin; x <= kMax; ++x) {
                const bool northSouthWall = (z == kMin || z == kMax);
                const bool eastWestWall = (x == kMin || x == kMax);
                if (!northSouthWall && !eastWestWall) continue;
                const bool entrance = (northSouthWall && x == 8) ||
                                      (eastWestWall && z == 8);
                if (entrance) continue;

                const int worldX = chunkX * kChunkSize + x;
                const int worldZ = chunkZ * kChunkSize + z;
                const int surfaceY = TerrainHeight(worldX, worldZ);
                if (surfaceY <= kSeaLevel || surfaceY + 3 >= kWorldHeight) continue;
                const std::uint32_t wallHash = Hash(ruinHash ^
                    static_cast<std::uint32_t>(x * 31 + z * 17));
                const BlockId wall = (wallHash % 5u == 0u) ? BlockId::MossyCobblestone
                    : ((wallHash % 7u == 0u) ? BlockId::Stonebrick
                                              : BlockId::Stonebrick);
                chunk.blocks[Index(x, surfaceY + 1, z)] = wall;

                const bool corner = (x == kMin || x == kMax) &&
                                    (z == kMin || z == kMax);
                if (corner && (wallHash & 1u) == 0u)
                    chunk.blocks[Index(x, surfaceY + 2, z)] = BlockId::MossyCobblestone;
            }
        }

        // A small broken altar distinguishes the ruin from natural rubble.
        const int altarWorldX = ruinCenterX;
        const int altarWorldZ = ruinCenterZ;
        const int altarY = TerrainHeight(altarWorldX, altarWorldZ);
        if (altarY > kSeaLevel && altarY + 2 < kWorldHeight) {
            chunk.blocks[Index(8, altarY + 1, 8)] = BlockId::Stonebrick;
            chunk.blocks[Index(7, altarY + 1, 8)] = BlockId::MossyCobblestone;
            chunk.blocks[Index(9, altarY + 1, 8)] = BlockId::MossyCobblestone;
            if ((ruinHash & 2u) != 0u)
                chunk.blocks[Index(8, altarY + 1, 7)] = BlockId::Stonebrick;
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

void VoxelWorld::InvalidateChunkMesh(int chunkX, int chunkZ) {
    dirtyChunkMeshes_.insert({chunkX, chunkZ});
}

void VoxelWorld::InvalidateChunkAndNeighbors(int chunkX, int chunkZ) {
    InvalidateChunkMesh(chunkX, chunkZ);
    InvalidateChunkMesh(chunkX - 1, chunkZ);
    InvalidateChunkMesh(chunkX + 1, chunkZ);
    InvalidateChunkMesh(chunkX, chunkZ - 1);
    InvalidateChunkMesh(chunkX, chunkZ + 1);
}
void VoxelWorld::StartNextChunkJob() {
    // Thread creation is optional: on devices that temporarily cannot allocate
    // another worker, keep the world playable and let PumpChunkGeneration build
    // one missing chunk synchronously per frame instead.
    if (asyncGenerationDisabled_) return;
    while (pendingChunkJobs_.size() < kChunkWorkerCount && !generationQueue_.empty()) {
        const auto key = generationQueue_.front();
        generationQueue_.pop_front();
        const int chunkX = key.first;
        const int chunkZ = key.second;
        ChunkJob job;
        job.key = key;
        try {
            job.future = std::async(std::launch::async, [this, chunkX, chunkZ]() {
                return MakeChunk(chunkX, chunkZ);
            });
            pendingChunkJobs_.push_back(std::move(job));
        } catch (const std::system_error&) {
            asyncGenerationDisabled_ = true;
            generationQueue_.push_front(key);
            break;
        }
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
        if (job.future.valid() &&
            job.future.wait_for(std::chrono::seconds(0)) == std::future_status::ready) {
            const auto key = job.key;
            try {
                Chunk chunk = job.future.get();
                queuedChunks_.erase(key);
                const auto completedKey = std::make_pair(chunk.x, chunk.z);
                if (chunks_.find(completedKey) == chunks_.end()) {
                    chunks_.emplace(completedKey, std::move(chunk));
                    InvalidateChunkAndNeighbors(completedKey.first, completedKey.second);
                    addedChunk = true;
                }
            } catch (...) {
                // A worker failure should not permanently stop terrain streaming.
                asyncGenerationDisabled_ = true;
                queuedChunks_.erase(key);
                if (chunks_.find(key) == chunks_.end()) {
                    Chunk chunk = MakeChunk(key.first, key.second);
                    chunks_.emplace(key, std::move(chunk));
                    InvalidateChunkAndNeighbors(key.first, key.second);
                    addedChunk = true;
                }
            }
            pendingChunkJobs_.erase(pendingChunkJobs_.begin() + static_cast<std::ptrdiff_t>(i));
        } else {
            ++i;
        }
    }
    StartNextChunkJob();
    if (asyncGenerationDisabled_ && !generationQueue_.empty()) {
        const auto key = generationQueue_.front();
        generationQueue_.pop_front();
        queuedChunks_.erase(key);
        if (chunks_.find(key) == chunks_.end()) {
            Chunk chunk = MakeChunk(key.first, key.second);
            chunks_.emplace(key, std::move(chunk));
            InvalidateChunkAndNeighbors(key.first, key.second);
            addedChunk = true;
        }
    }
    return addedChunk;
}

void VoxelWorld::WaitForChunkGeneration() {
    while (!pendingChunkJobs_.empty() || !generationQueue_.empty()) {
        StartNextChunkJob();
        if (asyncGenerationDisabled_ && !generationQueue_.empty()) {
            const auto key = generationQueue_.front();
            generationQueue_.pop_front();
            queuedChunks_.erase(key);
            if (chunks_.find(key) == chunks_.end()) {
                Chunk chunk = MakeChunk(key.first, key.second);
                chunks_.emplace(key, std::move(chunk));
                InvalidateChunkAndNeighbors(key.first, key.second);
            }
            continue;
        }
        if (pendingChunkJobs_.empty()) continue;
        ChunkJob job = std::move(pendingChunkJobs_.front());
        pendingChunkJobs_.erase(pendingChunkJobs_.begin());
        if (job.future.valid()) {
            try {
                Chunk chunk = job.future.get();
                queuedChunks_.erase(job.key);
                const auto key = std::make_pair(chunk.x, chunk.z);
                if (chunks_.find(key) == chunks_.end()) {
                    chunks_.emplace(key, std::move(chunk));
                    InvalidateChunkAndNeighbors(key.first, key.second);
                }
            } catch (...) {
                asyncGenerationDisabled_ = true;
                queuedChunks_.erase(job.key);
                if (chunks_.find(job.key) == chunks_.end()) {
                    Chunk chunk = MakeChunk(job.key.first, job.key.second);
                    chunks_.emplace(job.key, std::move(chunk));
                    InvalidateChunkAndNeighbors(job.key.first, job.key.second);
                }
            }
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
            if(chunks_.find({x,z})==chunks_.end()) {
                chunks_.emplace(std::make_pair(x,z),MakeChunk(x,z));
                InvalidateChunkAndNeighbors(x,z);
            }
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
    const int localX = PositiveMod(x, kChunkSize);
    const int localZ = PositiveMod(z, kChunkSize);
    chunk->blocks[Index(localX, y, localZ)] = block;
    InvalidateChunkMesh(chunk->x, chunk->z);
    if (localX == 0) InvalidateChunkMesh(chunk->x - 1, chunk->z);
    if (localX == kChunkSize - 1) InvalidateChunkMesh(chunk->x + 1, chunk->z);
    if (localZ == 0) InvalidateChunkMesh(chunk->x, chunk->z - 1);
    if (localZ == kChunkSize - 1) InvalidateChunkMesh(chunk->x, chunk->z + 1);
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
bool VoxelWorld::PlaceRay(const Vec3& origin,const Vec3& direction,float maxDistance,BlockId block,
                          Vec3* placedBlock) {
    const BlockDefinition* definition=GetBlockDefinition(block);
    if(maxDistance<=0.0f||definition==nullptr||(definition->flags&BlockPlaceable)==0u)return false;
    const RayHit hit=TraceVoxelRay(*this,origin,direction,maxDistance);if(!hit.hit)return false;
    if(hit.previousY<0||hit.previousY>=kWorldHeight||IsSolid(hit.previousX,hit.previousY,hit.previousZ))return false;
    if(!SetBlock(hit.previousX,hit.previousY,hit.previousZ,block))return false;
    if(placedBlock!=nullptr)*placedBlock={static_cast<float>(hit.previousX),static_cast<float>(hit.previousY),static_cast<float>(hit.previousZ)};
    return true;
}

void VoxelWorld::BuildMesh(std::vector<WorldVertex>& output,int centerWorldX,int centerWorldZ,
                           int radiusInChunks,std::vector<WorldVertex>* transparentOutput,
                           std::vector<ChunkMeshRange>* chunkRanges,
                           std::size_t meshBuildBudget,
                           bool collectMergedMesh) const {
    output.clear();
    if (transparentOutput != nullptr) transparentOutput->clear();
    if (chunkRanges != nullptr) chunkRanges->clear();

    const int centerChunkX=FloorDiv(centerWorldX,kChunkSize);
    const int centerChunkZ=FloorDiv(centerWorldZ,kChunkSize);
    using ChunkMeshData = ChunkMeshCache;

    std::vector<const Chunk*> selected;
    selected.reserve(chunks_.size());
    for (const auto& entry : chunks_) {
        const int cx=entry.first.first, cz=entry.first.second;
        if (radiusInChunks>=0 &&
            (std::abs(cx-centerChunkX)>radiusInChunks ||
             std::abs(cz-centerChunkZ)>radiusInChunks)) continue;

        selected.push_back(&entry.second);
    }
    // Prioritize the chunks around the camera first. A per-frame budget can then
    // spread the remaining dirty work over several frames without leaving nearby
    // terrain missing while distant chunks are being meshed.
    std::sort(selected.begin(), selected.end(), [&](const Chunk* a, const Chunk* b) {
        const int distanceA = std::max(std::abs(a->x - centerChunkX), std::abs(a->z - centerChunkZ));
        const int distanceB = std::max(std::abs(b->x - centerChunkX), std::abs(b->z - centerChunkZ));
        if (distanceA != distanceB) return distanceA < distanceB;
        if (a->x != b->x) return a->x < b->x;
        return a->z < b->z;
    });

    // Neighbour queries outside the rendered radius must behave as air.
    // Otherwise already-generated off-screen chunks can hide boundary faces
    // belonging to the last visible ring and create holes at the view-distance edge.
    const auto getMeshBlock = [&](int x,int y,int z) {
        if (y<0 || y>=kWorldHeight) return BlockId::Air;
        const int cx=FloorDiv(x,kChunkSize);
        const int cz=FloorDiv(z,kChunkSize);
        if (radiusInChunks>=0 &&
            (std::abs(cx-centerChunkX)>radiusInChunks ||
             std::abs(cz-centerChunkZ)>radiusInChunks)) return BlockId::Air;
        return GetBlock(x,y,z);
    };

    std::vector<ChunkMeshData> built(selected.size());
    std::vector<bool> needsBuild(selected.size(), false);
    for (std::size_t i = 0; i < selected.size(); ++i) {
        const Chunk& chunk = *selected[i];
        const int distance = std::max(std::abs(chunk.x - centerChunkX),
                                      std::abs(chunk.z - centerChunkZ));
        const int expectedLod = distance <= 2 ? 0 : (distance == 3 ? 1 : 2);
        const bool outerBorder = radiusInChunks >= 0 && distance >= radiusInChunks;
        const auto key = std::make_pair(chunk.x, chunk.z);
        const auto cached = chunkMeshCache_.find(key);
        needsBuild[i] = cached == chunkMeshCache_.end() ||
            dirtyChunkMeshes_.find(key) != dirtyChunkMeshes_.end() ||
            cached->second.lod != expectedLod ||
            cached->second.outerBorder != outerBorder;
    }
    if (meshBuildBudget > 0u) {
        std::size_t scheduled = 0u;
        for (std::size_t i = 0; i < needsBuild.size(); ++i) {
            if (!needsBuild[i]) continue;
            if (scheduled < meshBuildBudget) {
                ++scheduled;
            } else {
                needsBuild[i] = false;
            }
        }
    }
    static constexpr int neighborOffsets[6][3] = {
        {1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}
    };
    static constexpr std::array<std::array<Vec3,4>,6> faces{{
        {{{1,0,0},{1,1,0},{1,1,1},{1,0,1}}},
        {{{0,0,1},{0,1,1},{0,1,0},{0,0,0}}},
        {{{0,1,1},{1,1,1},{1,1,0},{0,1,0}}},
        {{{0,0,0},{1,0,0},{1,0,1},{0,0,1}}},
        {{{1,0,1},{1,1,1},{0,1,1},{0,0,1}}},
        {{{0,0,0},{0,1,0},{1,1,0},{1,0,0}}}
    }};

    const auto buildOneChunk = [&](std::size_t index) {
        if (!needsBuild[index]) return;
        const Chunk& chunk=*selected[index];
        ChunkMeshData& result=built[index];
        const int baseX=chunk.x*kChunkSize, baseZ=chunk.z*kChunkSize;
        const int chunkDistance=std::max(std::abs(chunk.x-centerChunkX),
                                         std::abs(chunk.z-centerChunkZ));
        // Keep visible near-field edges at high detail. Only the outer visible
        // ring is allowed the lighter LOD1 silhouette; LOD2 remains for chunks
        // beyond the normal mobile render radius.
        result.lod = chunkDistance<=2 ? 0 : (chunkDistance==3 ? 1 : 2);
        result.outerBorder = radiusInChunks >= 0 && chunkDistance >= radiusInChunks;

        // Distant-Horizons-inspired far terrain path: store a compact height/material
        // view for distant chunks instead of retaining every hidden voxel face.
        if (result.lod >= 2) {
            constexpr int kGridSize = kChunkSize + 2;
            std::array<int, kGridSize * kGridSize> heights{};
            std::array<BlockId, kGridSize * kGridSize> surfaceBlocks{};
            heights.fill(-1);
            surfaceBlocks.fill(BlockId::Air);
            const auto gridIndex = [](int x, int z) {
                return static_cast<std::size_t>((z + 1) * kGridSize + (x + 1));
            };
            for (int z = -1; z <= kChunkSize; ++z) {
                for (int x = -1; x <= kChunkSize; ++x) {
                    for (int y = kWorldHeight - 1; y >= 0; --y) {
                        const BlockId candidate = getMeshBlock(baseX + x, y, baseZ + z);
                        if (candidate == BlockId::Air) continue;
                        const BlockDefinition* def = GetBlockDefinition(candidate);
                        if (def == nullptr || def->shape != BlockShape::Cube ||
                            (def->flags & (BlockOpaque | BlockFluid | BlockTransparent)) == 0u) continue;
                        heights[gridIndex(x, z)] = y;
                        surfaceBlocks[gridIndex(x, z)] = candidate;
                        break;
                    }
                }
            }
            for (int z = 0; z < kChunkSize; ++z) {
                for (int x = 0; x < kChunkSize; ++x) {
                    const std::size_t cell = gridIndex(x, z);
                    const int topY = heights[cell];
                    if (topY < 0) continue;
                    const BlockId topBlock = surfaceBlocks[cell];
                    const BlockDefinition* def = GetBlockDefinition(topBlock);
                    if (def == nullptr) continue;
                    const bool transparent = (def->flags & (BlockTransparent | BlockFluid)) != 0u;
                    std::vector<WorldVertex>& target = transparent ? result.transparent : result.opaque;
                    const float wx = static_cast<float>(baseX + x);
                    const float wz = static_cast<float>(baseZ + z);
                    const float top = static_cast<float>(topY + 1);
                    const std::array<Vec3,4> topPoints{{
                        {wx,top,wz+1.0f},{wx+1.0f,top,wz+1.0f},
                        {wx+1.0f,top,wz},{wx,top,wz}
                    }};
                    AddFace(target, topPoints, FaceShade(2), TileForFace(*def, 2));
                    for (int face : {0,1,4,5}) {
                        int nx=x, nz=z;
                        if (face==0) ++nx;
                        else if (face==1) --nx;
                        else if (face==4) ++nz;
                        else --nz;
                        const int neighborY = heights[gridIndex(nx,nz)];
                        if (neighborY >= topY) continue;
                        const float bottom = static_cast<float>(neighborY + 1);
                        std::array<Vec3,4> points{};
                        for (std::size_t v=0;v<4;++v) {
                            const Vec3& p=faces[static_cast<std::size_t>(face)][v];
                            points[v]={wx+p.x, bottom+p.y*(top-bottom), wz+p.z};
                        }
                        AddFace(target, points, FaceShade(face), TileForFace(*def, face));
                    }
                }
            }
            return;
        }

        struct FaceCell { std::uint16_t tile{0}; bool visible{false}; };
        const auto coordinates = [&](int face,int slice,int u,int v) {
            if (face<=1) return std::array<int,3>{{slice,u,v}};
            if (face<=3) return std::array<int,3>{{u,slice,v}};
            return std::array<int,3>{{v,u,slice}};
        };

        // Greedy meshing: all full opaque cube faces are merged into maximal
        // coplanar rectangles that share the same atlas tile and face lighting.
        // Local UVs repeat inside the atlas tile, so large surfaces keep their
        // pixel pattern instead of stretching one texel over an entire wall.
        for (int face=0;face<6;++face) {
            int sliceCount=0, maskU=0, maskV=0;
            switch(face) {
                case 0: case 1: sliceCount=kChunkSize; maskU=kWorldHeight; maskV=kChunkSize; break;
                case 2: case 3: sliceCount=kWorldHeight; maskU=kChunkSize; maskV=kChunkSize; break;
                default: sliceCount=kChunkSize; maskU=kWorldHeight; maskV=kChunkSize; break;
            }
            const std::size_t maskSize=static_cast<std::size_t>(maskU*maskV);
            std::vector<FaceCell> mask(maskSize);
            for (int slice=0;slice<sliceCount;++slice) {
                std::fill(mask.begin(),mask.end(),FaceCell{});
                for (int u=0;u<maskU;++u) {
                    for (int v=0;v<maskV;++v) {
                        const auto c=coordinates(face,slice,u,v);
                        const int lx=c[0], ly=c[1], lz=c[2];
                        const BlockId block=chunk.blocks[Index(lx,ly,lz)];
                        if (block==BlockId::Air) continue;
                        const BlockDefinition* def=GetBlockDefinition(block);
                        if (def==nullptr || def->shape!=BlockShape::Cube ||
                            (def->flags & BlockOpaque)==0u ||
                            (def->flags & (BlockTransparent|BlockFluid))!=0u) continue;
                        const int wx=baseX+lx, wz=baseZ+lz;
                        const BlockId neighbor=getMeshBlock(wx+neighborOffsets[face][0],
                            ly+neighborOffsets[face][1],wz+neighborOffsets[face][2]);
                        if (ShouldHideFace(block,neighbor)) continue;
                        FaceCell& cell=mask[static_cast<std::size_t>(u*maskV+v)];
                        cell.tile=TileForFace(*def,face);
                        cell.visible=true;
                    }
                }

                for (int u=0;u<maskU;++u) {
                    for (int v=0;v<maskV;++v) {
                        FaceCell& first=mask[static_cast<std::size_t>(u*maskV+v)];
                        if (!first.visible) continue;
                        const std::uint16_t tile=first.tile;
                        int width=1;
                        while (u+width<maskU) {
                            const FaceCell& next=mask[static_cast<std::size_t>((u+width)*maskV+v)];
                            if (!next.visible || next.tile!=tile) break;
                            ++width;
                        }
                        int height=1;
                        bool canGrow=true;
                        while (v+height<maskV && canGrow) {
                            for (int du=0;du<width;++du) {
                                const FaceCell& next=mask[static_cast<std::size_t>((u+du)*maskV+v+height)];
                                if (!next.visible || next.tile!=tile) { canGrow=false; break; }
                            }
                            if (canGrow) ++height;
                        }

                        const auto c=coordinates(face,slice,u,v);
                        Vec3 p0{};
                        Vec3 axisU{},axisV{};
                        switch(face) {
                            case 0:
                                p0={static_cast<float>(baseX+c[0]+1),static_cast<float>(c[1]),static_cast<float>(baseZ+c[2])};
                                axisU={0,1,0}; axisV={0,0,1}; break;
                            case 1:
                                p0={static_cast<float>(baseX+c[0]),static_cast<float>(c[1]),static_cast<float>(baseZ+c[2]+1)};
                                axisU={0,1,0}; axisV={0,0,-1}; break;
                            case 2:
                                p0={static_cast<float>(baseX+c[0]),static_cast<float>(c[1]+1),static_cast<float>(baseZ+c[2]+1)};
                                axisU={1,0,0}; axisV={0,0,-1}; break;
                            case 3:
                                p0={static_cast<float>(baseX+c[0]),static_cast<float>(c[1]),static_cast<float>(baseZ+c[2])};
                                axisU={1,0,0}; axisV={0,0,1}; break;
                            case 4:
                                p0={static_cast<float>(baseX+c[0]+1),static_cast<float>(c[1]),static_cast<float>(baseZ+c[2]+1)};
                                axisU={0,1,0}; axisV={-1,0,0}; break;
                            default:
                                p0={static_cast<float>(baseX+c[0]),static_cast<float>(c[1]),static_cast<float>(baseZ+c[2])};
                                axisU={0,1,0}; axisV={1,0,0}; break;
                        }
                        const Vec3 p1{p0.x+axisU.x*static_cast<float>(width),
                                      p0.y+axisU.y*static_cast<float>(width),
                                      p0.z+axisU.z*static_cast<float>(width)};
                        const Vec3 p3{p0.x+axisV.x*static_cast<float>(height),
                                      p0.y+axisV.y*static_cast<float>(height),
                                      p0.z+axisV.z*static_cast<float>(height)};
                        const Vec3 p2{p1.x+axisV.x*static_cast<float>(height),
                                      p1.y+axisV.y*static_cast<float>(height),
                                      p1.z+axisV.z*static_cast<float>(height)};
                        AddFace(result.opaque,{{p0,p1,p2,p3}},FaceShade(face),tile,
                                static_cast<float>(width),static_cast<float>(height));
                        for (int du=0;du<width;++du)
                            for (int dv=0;dv<height;++dv)
                                mask[static_cast<std::size_t>((u+du)*maskV+v+dv)].visible=false;
                    }
                }
            }
        }

        // Non-cube geometry and transparent cube surfaces retain their original
        // block shapes. Distant chunks use cheaper silhouettes: tiny plants/thin
        // details disappear at LOD2 and compound shapes collapse to one cube.
        for (int y=0;y<kWorldHeight;++y) {
            for (int z=0;z<kChunkSize;++z) {
                for (int x=0;x<kChunkSize;++x) {
                    const BlockId block=chunk.blocks[Index(x,y,z)];
                    if (block==BlockId::Air) continue;
                    const BlockDefinition* def=GetBlockDefinition(block);
                    if (def==nullptr || def->shape==BlockShape::None) continue;
                    const bool opaqueCube=def->shape==BlockShape::Cube &&
                        (def->flags&BlockOpaque)!=0u &&
                        (def->flags&(BlockTransparent|BlockFluid))==0u;
                    if (opaqueCube) continue;

                    if (result.lod>=1 && def->shape==BlockShape::CrossPlant) continue;
                    if (result.lod>=2 && def->shape==BlockShape::Thin) continue;

                    const int wx=baseX+x, wz=baseZ+z;
                    const bool transparent=(def->flags&(BlockTransparent|BlockFluid))!=0u;
                    std::vector<WorldVertex>& destination=transparent
                        ? result.transparent : result.opaque;
                    if (def->shape!=BlockShape::Cube) {
                        const bool compoundOpaque=result.lod>=2 && !transparent &&
                            def->shape!=BlockShape::Fluid;
                        if (compoundOpaque) {
                            AppendBox(destination,static_cast<float>(wx),static_cast<float>(y),
                                      static_cast<float>(wz),static_cast<float>(wx+1),
                                      static_cast<float>(y+1),static_cast<float>(wz+1),*def);
                        } else {
                            AppendBlockShape(destination,wx,y,wz,*def);
                        }
                        continue;
                    }

                    for (int face=0;face<6;++face) {
                        const BlockId neighbor=getMeshBlock(wx+neighborOffsets[face][0],
                            y+neighborOffsets[face][1],wz+neighborOffsets[face][2]);
                        if (ShouldHideFace(block,neighbor)) continue;
                        std::array<Vec3,4> points{};
                        for (std::size_t i=0;i<4;++i) {
                            const Vec3& p=faces[static_cast<std::size_t>(face)][i];
                            points[i]={static_cast<float>(wx)+p.x,static_cast<float>(y)+p.y,
                                       static_cast<float>(wz)+p.z};
                        }
                        AddFace(destination,points,FaceShade(face),TileForFace(*def,face));
                    }
                }
            }
        }
    };

    // Two independent CPU workers build disjoint chunk meshes. The result is
    // merged in stable chunk order so VBO ranges stay deterministic.
    const std::size_t dirtyMeshCount=static_cast<std::size_t>(
        std::count(needsBuild.begin(),needsBuild.end(),true));
    const std::size_t workerCount=std::min(kMeshWorkerCount,dirtyMeshCount);
    if (workerCount<=1u) {
        for (std::size_t i=0;i<selected.size();++i) buildOneChunk(i);
    } else {
        std::vector<std::future<void>> jobs;
        jobs.reserve(workerCount);
        for (std::size_t worker=0;worker<workerCount;++worker) {
            jobs.push_back(std::async(std::launch::async,[&,worker,workerCount] {
                for (std::size_t i=worker;i<selected.size();i+=workerCount) buildOneChunk(i);
            }));
        }
        for (auto& job:jobs) job.get();
    }

    // Commit only newly built or invalidated meshes to the persistent per-chunk cache.
    // The revision advances only when this chunk's geometry actually changes. The
    // renderer uses it to retain GPU buffers for every unaffected chunk.
    for (std::size_t i = 0; i < selected.size(); ++i) {
        const auto key = std::make_pair(selected[i]->x, selected[i]->z);
        if (!needsBuild[i]) continue;
        ChunkMeshData& fresh = built[i];
        fresh.minY = static_cast<float>(kWorldHeight);
        fresh.maxY = 0.0f;
        bool hasVertices = false;
        const auto includeBounds = [&](const std::vector<WorldVertex>& vertices) {
            for (const WorldVertex& vertex : vertices) {
                fresh.minY = std::min(fresh.minY, vertex.y);
                fresh.maxY = std::max(fresh.maxY, vertex.y);
                hasVertices = true;
            }
        };
        includeBounds(fresh.opaque);
        includeBounds(fresh.transparent);
        if (!hasVertices) fresh.minY = fresh.maxY = 0.0f;
        const auto previous = chunkMeshCache_.find(key);
        fresh.revision = previous == chunkMeshCache_.end() ? 1u : previous->second.revision + 1u;
        chunkMeshCache_[key] = std::move(fresh);
        dirtyChunkMeshes_.erase(key);
    }

    // Keep cached geometry bounded to the currently resident render radius.
    const auto pruneCachedMeshes = [&]() {
        if (radiusInChunks < 0) return;
        for (auto it = chunkMeshCache_.begin(); it != chunkMeshCache_.end();) {
            if (std::abs(it->first.first - centerChunkX) > radiusInChunks ||
                std::abs(it->first.second - centerChunkZ) > radiusInChunks) {
                it = chunkMeshCache_.erase(it);
            } else {
                ++it;
            }
        }
        for (auto it = dirtyChunkMeshes_.begin(); it != dirtyChunkMeshes_.end();) {
            if (std::abs(it->first - centerChunkX) > radiusInChunks ||
                std::abs(it->second - centerChunkZ) > radiusInChunks) {
                it = dirtyChunkMeshes_.erase(it);
            } else {
                ++it;
            }
        }
    };
    pruneCachedMeshes();
    if (!collectMergedMesh) return;

    const std::size_t reserveChunks=selected.size();
    output.reserve(reserveChunks*7000u);
    if (transparentOutput!=nullptr) transparentOutput->reserve(reserveChunks*1200u);
    for (std::size_t i=0;i<selected.size();++i) {
        const auto cacheIt = chunkMeshCache_.find(
            std::make_pair(selected[i]->x, selected[i]->z));
        if (cacheIt == chunkMeshCache_.end()) continue;
        const ChunkMeshData& data = cacheIt->second;
        ChunkMeshRange range;
        range.chunkX=selected[i]->x;
        range.chunkZ=selected[i]->z;
        range.lodLevel=data.lod;
        range.revision=data.revision;
        range.minY=data.minY;
        range.maxY=data.maxY;
        range.opaqueFirst=output.size();
        output.insert(output.end(),data.opaque.begin(),data.opaque.end());
        if (transparentOutput!=nullptr) {
            range.opaqueCount=output.size()-range.opaqueFirst;
            range.transparentFirst=transparentOutput->size();
            transparentOutput->insert(transparentOutput->end(),data.transparent.begin(),data.transparent.end());
            range.transparentCount=transparentOutput->size()-range.transparentFirst;
        } else {
            output.insert(output.end(),data.transparent.begin(),data.transparent.end());
            range.opaqueCount=output.size()-range.opaqueFirst;
        }
        if (chunkRanges!=nullptr) chunkRanges->push_back(range);
    }
}

void VoxelWorld::GetChunkMeshViews(int centerWorldX, int centerWorldZ, int radiusInChunks,
                                   std::vector<ChunkMeshView>& output) const {
    output.clear();
    const int centerChunkX = FloorDiv(centerWorldX, kChunkSize);
    const int centerChunkZ = FloorDiv(centerWorldZ, kChunkSize);
    if (radiusInChunks >= 0) {
        const std::size_t side = static_cast<std::size_t>(radiusInChunks * 2 + 1);
        output.reserve(std::min(chunkMeshCache_.size(), side * side));
    } else {
        output.reserve(chunkMeshCache_.size());
    }
    for (const auto& entry : chunkMeshCache_) {
        const int chunkX = entry.first.first;
        const int chunkZ = entry.first.second;
        if (radiusInChunks >= 0 &&
            (std::abs(chunkX - centerChunkX) > radiusInChunks ||
             std::abs(chunkZ - centerChunkZ) > radiusInChunks)) continue;
        const ChunkMeshCache& mesh = entry.second;
        output.push_back({chunkX, chunkZ, mesh.lod, mesh.revision,
                          mesh.minY, mesh.maxY, &mesh.opaque, &mesh.transparent});
    }
    std::sort(output.begin(), output.end(), [centerChunkX, centerChunkZ](
        const ChunkMeshView& a, const ChunkMeshView& b) {
        const int da = std::max(std::abs(a.chunkX - centerChunkX),
                                std::abs(a.chunkZ - centerChunkZ));
        const int db = std::max(std::abs(b.chunkX - centerChunkX),
                                std::abs(b.chunkZ - centerChunkZ));
        if (da != db) return da < db;
        if (a.chunkX != b.chunkX) return a.chunkX < b.chunkX;
        return a.chunkZ < b.chunkZ;
    });
}

std::size_t VoxelWorld::CountNonAirBlocks() const {
    std::size_t total=0;for(const auto& pair:chunks_)for(BlockId id:pair.second.blocks)if(id!=BlockId::Air)++total;return total;
}

bool VoxelWorld::Save(const std::string& filePath,const Vec3& playerPosition,
                      const std::array<std::uint32_t,36>& inventory) const {
    if(chunks_.size()>kMaxSavedChunks||!std::isfinite(playerPosition.x)||!std::isfinite(playerPosition.y)||!std::isfinite(playerPosition.z))return false;
    try {
        const std::filesystem::path path(filePath);if(path.has_parent_path())std::filesystem::create_directories(path.parent_path());
        const std::filesystem::path temp=path.string()+".tmp";
        std::ofstream file(temp,std::ios::binary|std::ios::trunc);if(!file)return false;
        constexpr char magic[8]={'M','F','V','O','X','0','0','3'};
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
                      std::array<std::uint32_t,36>& inventory) {
    WaitForChunkGeneration();
    generationQueue_.clear();
    queuedChunks_.clear();
    std::ifstream file(filePath,std::ios::binary);if(!file)return false;
    char magic[8]{};file.read(magic,sizeof(magic));if(!file)return false;
    const bool legacy=std::memcmp(magic,"MFVOX001",8)==0;
    const bool version2=std::memcmp(magic,"MFVOX002",8)==0;
    const bool version3=std::memcmp(magic,"MFVOX003",8)==0;
    if(!legacy&&!version2&&!version3)return false;
    std::uint32_t loadedSeed=0,count=0;Vec3 loadedPlayer{};std::array<std::uint32_t,36> loadedInventory{};
    file.read(reinterpret_cast<char*>(&loadedSeed),sizeof(loadedSeed));file.read(reinterpret_cast<char*>(&loadedPlayer),sizeof(loadedPlayer));
    if(legacy) {
        std::array<std::uint16_t,9> old{};file.read(reinterpret_cast<char*>(old.data()),static_cast<std::streamsize>(sizeof(old)));
        for(std::size_t i=0;i<old.size();++i){const std::uint16_t oldId=old[i]/1000u,amount=old[i]%1000u;loadedInventory[i]=static_cast<std::uint32_t>(RemapLegacyInventoryId(oldId))*1000u+amount;}
    } else if(version2) {
        std::array<std::uint32_t,9> old{};file.read(reinterpret_cast<char*>(old.data()),static_cast<std::streamsize>(sizeof(old)));
        std::copy(old.begin(),old.end(),loadedInventory.begin());
    } else {
        file.read(reinterpret_cast<char*>(loadedInventory.data()),static_cast<std::streamsize>(sizeof(loadedInventory)));
    }
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
    seed_=loadedSeed;chunks_=std::move(loadedChunks);chunkMeshCache_.clear();dirtyChunkMeshes_.clear();playerPosition=loadedPlayer;inventory=loadedInventory;return true;
}

bool VoxelWorld::HasPendingMeshBuild(int centerWorldX, int centerWorldZ,
                                     int radiusInChunks) const {
    const int centerChunkX = FloorDiv(centerWorldX, kChunkSize);
    const int centerChunkZ = FloorDiv(centerWorldZ, kChunkSize);
    for (const auto& entry : chunks_) {
        const int cx = entry.first.first;
        const int cz = entry.first.second;
        if (radiusInChunks >= 0 &&
            (std::abs(cx - centerChunkX) > radiusInChunks ||
             std::abs(cz - centerChunkZ) > radiusInChunks)) continue;
        const int distance = std::max(std::abs(cx - centerChunkX),
                                      std::abs(cz - centerChunkZ));
        const int expectedLod = distance <= 2 ? 0 : (distance == 3 ? 1 : 2);
        const bool outerBorder = radiusInChunks >= 0 && distance >= radiusInChunks;
        const auto key = std::make_pair(cx, cz);
        const auto cached = chunkMeshCache_.find(key);
        if (cached == chunkMeshCache_.end() ||
            dirtyChunkMeshes_.find(key) != dirtyChunkMeshes_.end() ||
            cached->second.lod != expectedLod ||
            cached->second.outerBorder != outerBorder) return true;
    }
    return false;
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

void AppendTexturedBlockCube(std::vector<WorldVertex>& output,BlockId block,
                             const Vec3& center,const Vec3& half) {
    const BlockDefinition* definition=GetBlockDefinition(block);
    if(definition==nullptr||definition->shape==BlockShape::None)return;
    const float x0=center.x-half.x,x1=center.x+half.x;
    const float y0=center.y-half.y,y1=center.y+half.y;
    const float z0=center.z-half.z,z1=center.z+half.z;
    if(definition->shape==BlockShape::CrossPlant) {
        AppendCrossPlant(output,x0,y0,z0,*definition);
        return;
    }
    // Item meshes use the exact top/side/bottom atlas cells of their block.
    // A compact cube keeps the held/dropped representation inexpensive.
    AppendBox(output,x0,y0,z0,x1,y1,z1,*definition);
}

void AppendAnimatedTexturedBlockCube(std::vector<WorldVertex>& output,BlockId block,
                                     const Vec3& center,const Vec3& half,
                                     float yawRadians,float verticalOffset) {
    std::vector<WorldVertex> local;
    local.reserve(36u);
    Vec3 animatedCenter=center;
    animatedCenter.y+=verticalOffset;
    AppendTexturedBlockCube(local,block,animatedCenter,half);
    const float cosine=std::cos(yawRadians);
    const float sine=std::sin(yawRadians);
    for(WorldVertex& vertex:local) {
        const float x=vertex.x-animatedCenter.x;
        const float z=vertex.z-animatedCenter.z;
        vertex.x=animatedCenter.x+x*cosine+z*sine;
        vertex.z=animatedCenter.z-x*sine+z*cosine;
        const float nx=vertex.nx, nz=vertex.nz;
        vertex.nx=nx*cosine+nz*sine;
        vertex.nz=-nx*sine+nz*cosine;
    }
    output.insert(output.end(),local.begin(),local.end());
}

} // namespace minefade
