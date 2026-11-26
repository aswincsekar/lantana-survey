package org.atree.survey;

import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.view.Surface;
import com.google.ar.core.*;
import java.nio.*;

/** Draw the AR camera texture in the same upright CPU-image coordinates as the saved overview. */
final class ArPreview implements AutoCloseable {
  EGLDisplay display = EGL14.EGL_NO_DISPLAY;
  EGLContext context = EGL14.EGL_NO_CONTEXT;
  EGLSurface window = EGL14.EGL_NO_SURFACE;
  Surface output;
  int texture, program, position, uv;
  final FloatBuffer vertices = buffer(), texcoords = buffer();
  final float[] imagePixels = new float[8], transformed = new float[8];

  static FloatBuffer buffer() {
    return ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
  }

  void open(SurfaceTexture target) {
    display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
    int[] count = new int[1];
    if (!EGL14.eglInitialize(display, new int[1], 0, new int[1], 0))
      throw new IllegalStateException("EGL unavailable");
    EGLConfig[] config = new EGLConfig[1];
    int[] attrs = {
      EGL14.EGL_RENDERABLE_TYPE,
      4,
      EGL14.EGL_SURFACE_TYPE,
      EGL14.EGL_WINDOW_BIT,
      EGL14.EGL_RED_SIZE,
      8,
      EGL14.EGL_GREEN_SIZE,
      8,
      EGL14.EGL_BLUE_SIZE,
      8,
      EGL14.EGL_NONE
    };
    if (!EGL14.eglChooseConfig(display, attrs, 0, config, 0, 1, count, 0) || count[0] == 0)
      throw new IllegalStateException("No preview configuration");
    context =
        EGL14.eglCreateContext(
            display,
            config[0],
            EGL14.EGL_NO_CONTEXT,
            new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE},
            0);
    output = new Surface(target);
    window =
        EGL14.eglCreateWindowSurface(display, config[0], output, new int[] {EGL14.EGL_NONE}, 0);
    if (!EGL14.eglMakeCurrent(display, window, window, context))
      throw new IllegalStateException("No preview surface");
    int[] names = new int[1];
    GLES20.glGenTextures(1, names, 0);
    texture = names[0];
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
    for (int filter : new int[] {GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER})
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, filter, GLES20.GL_LINEAR);
    for (int wrap : new int[] {GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T})
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, wrap, GLES20.GL_CLAMP_TO_EDGE);
    program = GLES20.glCreateProgram();
    shader(
        GLES20.GL_VERTEX_SHADER,
        "attribute vec2 pos, uv; varying vec2 coord; void"
            + " main(){coord=uv;gl_Position=vec4(pos,0.,1.);}");
    shader(
        GLES20.GL_FRAGMENT_SHADER,
        "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float; uniform samplerExternalOES camera; varying vec2 coord; void"
            + " main(){gl_FragColor=texture2D(camera,coord);}");
    GLES20.glLinkProgram(program);
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, names, 0);
    if (names[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
    position = GLES20.glGetAttribLocation(program, "pos");
    uv = GLES20.glGetAttribLocation(program, "uv");
    vertices.put(new float[] {-1, 1, -1, -1, 1, 1, 1, -1}).position(0);
  }

  void shader(int type, String source) {
    int shader = GLES20.glCreateShader(type);
    GLES20.glShaderSource(shader, source);
    GLES20.glCompileShader(shader);
    GLES20.glAttachShader(program, shader);
    GLES20.glDeleteShader(shader);
  }

  int width, height;

  void configure(int w, int h, int rotation) {
    width = (rotation % 180 == 0) ? w : h;
    height = (rotation % 180 == 0) ? h : w;
    for (int i = 0; i < 4; i++) {
      int x = i / 2, y = i % 2;
      imagePixels[2 * i] =
          w * (rotation == 90 ? y : rotation == 180 ? 1 - x : rotation == 270 ? 1 - y : x);
      imagePixels[2 * i + 1] =
          h * (rotation == 90 ? 1 - x : rotation == 180 ? 1 - y : rotation == 270 ? x : y);
    }
  }

  final int[] surfaceWidth = new int[1], surfaceHeight = new int[1];

  void draw(Frame frame) {
    frame.transformCoordinates2d(
        Coordinates2d.IMAGE_PIXELS, imagePixels, Coordinates2d.TEXTURE_NORMALIZED, transformed);
    texcoords.position(0);
    texcoords.put(transformed).position(0);
    EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, surfaceWidth, 0);
    EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, surfaceHeight, 0);
    GLES20.glViewport(0, 0, surfaceWidth[0], surfaceHeight[0]);
    GLES20.glUseProgram(program);
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
    GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices);
    GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, texcoords);
    GLES20.glEnableVertexAttribArray(position);
    GLES20.glEnableVertexAttribArray(uv);
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    if (!EGL14.eglSwapBuffers(display, window))
      throw new IllegalStateException("Preview surface lost");
  }

  public void close() {
    if (display != EGL14.EGL_NO_DISPLAY) {
      EGL14.eglMakeCurrent(
          display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
      if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window);
      if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
      EGL14.eglTerminate(display);
    }
    if (output != null) output.release();
  }
}
