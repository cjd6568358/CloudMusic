precision mediump float;

varying vec2 vTextureCoord;
uniform sampler2D  map_texture;

void main() {
    vec4 frame_color = texture2D( map_texture, vTextureCoord );
    gl_FragColor = frame_color;
}