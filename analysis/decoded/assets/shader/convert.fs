precision mediump float;

varying lowp vec2 vTextureCoord;
uniform sampler2D uTexture;
uniform sampler2D uTexture2;

void main()
{
    float r, g, b, y, u, v;

    //We had put the Y values of each pixel to the R,G,B components by
    //GL_LUMINANCE, that's why we're pulling it from the R component,
    //we could also use G or B
    y = texture2D(uTexture, vTextureCoord).r;

    //this is for NV12 if NV21 then we have to replace the v / u
    //We had put the U and V values of each pixel to the A and R,G,B
    //components of the texture respectively using GL_LUMINANCE_ALPHA.
    //Since U,V bytes are interspread in the texture, this is probably
    //the fastest way to use them in the shader
    v = texture2D(uTexture2, vTextureCoord).a - 0.5;
    u = texture2D(uTexture2, vTextureCoord).r - 0.5;

    //The numbers are just YUV to RGB conversion constants
    r = y + 1.13983*v;
    g = y - 0.39465*u - 0.58060*v;
    b = y + 2.03211*u;

    gl_FragColor = vec4(r,g,b,1.0);
}