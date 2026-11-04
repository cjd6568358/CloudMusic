#extension GL_OES_EGL_image_external : require
precision mediump float;

varying vec2 vTextureCoord;
uniform samplerExternalOES map_texture;
uniform vec2 uTextureScale;

void main() {
    vec4 frame_color = texture2D(map_texture, ((vTextureCoord*2.0-1.0)*uTextureScale+1.0)*0.5);
    gl_FragColor = frame_color;
}