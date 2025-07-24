struct Params {
  map_width: u32,
  map_height: u32,
}

struct TerrainType {
  height: f32,
  color: vec4<f32>,
}

@group(0) @binding(0) var<uniform> params: Params;
@group(0) @binding(1) var<storage, read_write> noise_map: array<f32>;
@group(0) @binding(2) var<storage, read_write> color_map: array<vec4f>;
@group(0) @binding(3) var<storage, read_write> moisture_map: array<f32>;

const terrainTypes: array<TerrainType, 8> = array<TerrainType, 8>(
  TerrainType(0.1, vec4<f32>(0.0, 0.0, 0.4, 1.0)), // 0 Deep Water
  TerrainType(0.2, vec4<f32>(0.0, 0.0, 0.8, 1.0)),   // 1 Shallow Water
  TerrainType(0.25, vec4<f32>(0.85, 0.54, 0.27, 1.0)), // 2 Sand
  TerrainType(0.4, vec4<f32>(0.1, 0.45, 0.1, 1.0)), // 3 Grassland
  TerrainType(0.5, vec4<f32>(0.0, 0.3, 0.0, 1.0)),   // 4 Forest
  TerrainType(0.6, vec4<f32>(0.4, 0.4, 0.4, 1.0)), // 5 Rock
  TerrainType(0.8, vec4<f32>(0.3, 0.3, 0.3, 1.0)), // 6 Dark Rock
  TerrainType(1.0, vec4<f32>(0.9, 0.9, 0.9, 1.0))    // 7 Snow
);

fn getColor(height: f32, moisture: f32) -> vec4<f32> {
  if (height <= terrainTypes[1].height) { // Water line
    if (height <= terrainTypes[0].height) {
      return terrainTypes[0].color; // Deep Water
    }
    return terrainTypes[1].color; // Shallow Water
  }

  if (height <= terrainTypes[2].height) { // Coastal
    return terrainTypes[2].color; // Sand
  }

  if (height <= terrainTypes[4].height) { // Lowlands
    if (moisture < 0.15) {
      return terrainTypes[2].color; // Desert Sand
    } else if (moisture < 0.66) {
      return terrainTypes[3].color; // Grassland
    } else {
      return terrainTypes[4].color; // Forest
    }
  }

  if (height <= terrainTypes[6].height) { // Highlands
    if (moisture < 0.5) {
      return terrainTypes[6].color; // Dark Rock
    } else {
      return terrainTypes[5].color; // Rock with some vegetation/lichen
    }
  }

  // Mountain tops
  return terrainTypes[7].color; // Snow
}

@compute @workgroup_size(8, 8, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let x: u32 = gid.x;
  let y: u32 = gid.y;

  if (x >= params.map_width || y >= params.map_height) {
    return;
  }

  let index: u32 = y * params.map_width + x;
  let heightValue: f32 = noise_map[index];
  let moistureValue: f32 = moisture_map[index];
  let colorVec: vec4<f32> = getColor(heightValue, moistureValue);
  color_map[index] = colorVec;
}