precision highp float;
varying vec2 vTextureCoord;
uniform sampler2D uTexture;
uniform vec2 uStep;
void main() {
  vec2 res = 1.0/uStep;
  float minlen = min(res.x, res.y);
  float targetLen = minlen*25.0/720.0;
  vec2 uv = abs(vTextureCoord-0.5)*2.0*res;
  vec2 offset = uv - res + targetLen;
  vec2 offsetNew = offset + targetLen;
  gl_FragColor = texture2D(uTexture, vTextureCoord);
  if(offset.x > 0.0 || offset.y > 0.0)
  {
      if(offset.x > 0.0 && offset.y > 0.0 && length(offset)>targetLen)
      {
          gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0);
      }
      else
      {
          gl_FragColor = vec4(1.0, 1.0, 1.0, 1.0);
      }
  }
  else if(offsetNew.x > 0.0 && offsetNew.y > 0.0 && length(offsetNew)>targetLen)
  {
      gl_FragColor = vec4(1.0, 1.0, 1.0, 1.0);
  }
}
