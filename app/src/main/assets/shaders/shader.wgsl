struct Camera {
    pitch: f32,
    yaw: f32,
    aspectRatio: f32,
    cameraPosX: f32,
    cameraPosY: f32,
    cameraPosZ: f32,
    mapWidth: f32,
    mapHeight: f32,
}

struct VertexOutput {
  @builtin(position) position: vec4f,
  @location(0) color: vec4f,
  @location(1) normal: vec3f,
  @location(2) world_pos: vec3f,
}

@group(0) @binding(0) var<uniform> camera: Camera;
@group(0) @binding(1) var<storage, read> noise_map: array<f32>;
@group(0) @binding(2) var<storage, read> color_map: array<vec4f>;

const scale = 1.0f;

fn perspectiveMatrix(fov: f32, aspect: f32, near: f32, far: f32) -> mat4x4f {
    let f = 1.0 / tan(fov / 2.0);
    return mat4x4f(
        f / aspect, 0.0, 0.0, 0.0,
        0.0, f, 0.0, 0.0,
        0.0, 0.0, (far + near) / (near - far), -1.0,
        0.0, 0.0, (2.0 * far * near) / (near - far), 0.0
    );
}

fn lookAt(camera_pos: vec3f, camera_target: vec3f, camera_up: vec3f) -> mat4x4f {
    let z_axis = normalize(camera_pos - camera_target);
    let x_axis = normalize(cross(camera_up, z_axis));
    let y_axis = cross(z_axis, x_axis);

    return mat4x4f(
        x_axis.x, y_axis.x, z_axis.x, 0.0,
        x_axis.y, y_axis.y, z_axis.y, 0.0,
        x_axis.z, y_axis.z, z_axis.z, 0.0,
        -dot(x_axis, camera_pos), -dot(y_axis, camera_pos), -dot(z_axis, camera_pos), 1.0
    );
}

// Maybe change this.
fn heightCurve(t: f32) -> f32 {
    return pow(t, 3.0f);
}

fn get_vertex(vertex_idx: u32, map_width: u32, map_height: u32) -> vec3f {
    let x = f32(vertex_idx % map_width);
    let z = f32(vertex_idx / map_width);
    return vec3f(x * scale - f32(map_width - 1u) * scale / 2.0, 0.0, z * scale - f32(map_height - 1u) * scale / 2.0);
}

@vertex
fn vs(@builtin(vertex_index) vertex_idx: u32) -> VertexOutput {
    let map_width: u32 = u32(camera.mapWidth);
    let map_height: u32 = u32(camera.mapHeight);

    // Determine which quad this vertex belongs to
    let quad_idx = vertex_idx / 6u;
    let vertex_in_quad = vertex_idx % 6u;

    let quad_x = quad_idx % (map_width - 1u);
    let quad_z = quad_idx / (map_width - 1u);

    // Determine the indices of the four corners of the quad
    let top_left = quad_z * map_width + quad_x;
    let top_right = top_left + 1u;
    let bottom_left = top_left + map_width;
    let bottom_right = bottom_left + 1u;

    var current_vertex_index: u32;
    // Triangle 1: top-left, bottom-left, top-right
    if (vertex_in_quad == 0u) { current_vertex_index = top_left; }
    else if (vertex_in_quad == 1u) { current_vertex_index = bottom_left; }
    else if (vertex_in_quad == 2u) { current_vertex_index = top_right; }
    // Triangle 2: top-right, bottom-left, bottom-right
    else if (vertex_in_quad == 3u) { current_vertex_index = top_right; }
    else if (vertex_in_quad == 4u) { current_vertex_index = bottom_left; }
    else { current_vertex_index = bottom_right; }

    var position = get_vertex(current_vertex_index, map_width, map_height);

    let height_multiplier = 150.0f;
    let height = heightCurve(noise_map[current_vertex_index]) * height_multiplier;
    position.y = height;

    // Calculate Normals by sampling neighbors
    var normal: vec3f;
    let x = current_vertex_index % map_width;
    let z = current_vertex_index / map_width;

    if (x > 0u && x < map_width - 1u && z > 0u && z < map_height - 1u) {
        let height_left = heightCurve(noise_map[current_vertex_index - 1u]) * height_multiplier; // Removed floor()
        let height_right = heightCurve(noise_map[current_vertex_index + 1u]) * height_multiplier; // Removed floor()
        let height_up = heightCurve(noise_map[current_vertex_index - map_width]) * height_multiplier; // Removed floor()
        let height_down = heightCurve(noise_map[current_vertex_index + map_width]) * height_multiplier; // Removed floor()

        let normal_x = height_left - height_right;
        let normal_z = height_up - height_down;
        normal = normalize(vec3f(normal_x, 2.0 * scale, normal_z));
    } else {
        normal = vec3f(0.0, 1.0, 0.0); // Fallback for edges
    }

    // --- Camera setup ---
    let proj: mat4x4f = perspectiveMatrix(1.0472, camera.aspectRatio, 0.1, 5000.0); // Increased far plane
    let camera_pos = vec3f(camera.cameraPosX, camera.cameraPosY, camera.cameraPosZ);
    var forward_direction = normalize(vec3f(
        cos(camera.pitch) * sin(camera.yaw),
        sin(camera.pitch),
        -cos(camera.pitch) * cos(camera.yaw)
    ));
    let camera_target = camera_pos + forward_direction;
    let camera_up = vec3f(0.0, 1.0, 0.0);
    let view = lookAt(camera_pos, camera_target, camera_up);

    var vsOutput: VertexOutput;
    vsOutput.position = proj * view * vec4f(position, 1.0);
    vsOutput.color = color_map[current_vertex_index];
    vsOutput.normal = normal;
    vsOutput.world_pos = position;

    return vsOutput;
}

@fragment
fn fs(fsInput: VertexOutput) -> @location(0) vec4f {

  // --- Lighting ---
  let light_dir = normalize(vec3f(0.5, 1.0, 0.5));
  let normal = normalize(fsInput.normal);
  let diffuse_intensity = max(dot(normal, light_dir), 0.0);
  let ambient_intensity = 0.2;
  let final_intensity = ambient_intensity + diffuse_intensity;

  let original_color = fsInput.color;
  let lit_rgb = original_color.rgb * vec3f(final_intensity);
  // --- End Lighting ---

  // --- Fog ---
  let fog_color = vec3f(0.529, 0.808, 0.922); // Match clear color
  let fog_density = 0.0005;

  let distance_to_camera = distance(fsInput.world_pos, vec3f(camera.cameraPosX, camera.cameraPosY, camera.cameraPosZ));
  let fog_factor = 1.0 - exp(-pow(distance_to_camera * fog_density, 2.0));

  let final_rgb = mix(lit_rgb, fog_color, clamp(fog_factor, 0.0, 1.0));
  // --- End Fog ---

  return vec4f(final_rgb, original_color.a);
}