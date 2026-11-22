attribute vec4 vPosition;
attribute vec2 vPositionSticker;
varying vec2 vTextureCoord;

void main() {
    gl_Position = vPosition;
    vTextureCoord = vPositionSticker;
}