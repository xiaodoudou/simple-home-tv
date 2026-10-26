package com.simplehometv;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.HardwareRenderer;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;

/**
 * A soft blur computed once, so animating towards it is only a crossfade (re-blurring every frame is too heavy
 * for TV GPUs). A real Gaussian blur on the GPU where available, else a shrink-and-stretch approximation.
 */
final class Blur {

    private static final int SCALE = 4;         // blur a quarter-size copy: cheaper, and the result is soft anyway
    private static final float RADIUS = 9f;     // in the quarter-size copy

    private Blur() { }

    static Bitmap of(Bitmap frame) {
        Bitmap small = Bitmap.createScaledBitmap(frame, Math.max(1, frame.getWidth() / SCALE),
                Math.max(1, frame.getHeight() / SCALE), true);
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                Bitmap out = gpu(small);
                if (out != null) return out;
            } catch (Exception ignored) {
            }
        }
        Bitmap tiny = Bitmap.createScaledBitmap(small, Math.max(1, small.getWidth() / 6), Math.max(1, small.getHeight() / 6), true);
        return Bitmap.createScaledBitmap(tiny, small.getWidth(), small.getHeight(), true);
    }

    @android.annotation.TargetApi(31)
    private static Bitmap gpu(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
        HardwareRenderer renderer = new HardwareRenderer();
        try {
            RenderNode node = new RenderNode("blur");
            node.setPosition(0, 0, w, h);
            node.setRenderEffect(RenderEffect.createBlurEffect(RADIUS, RADIUS, Shader.TileMode.CLAMP));
            RecordingCanvas c = node.beginRecording();
            c.drawBitmap(src, 0, 0, null);
            node.endRecording();
            renderer.setSurface(reader.getSurface());
            renderer.setContentRoot(node);
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
            Image image = reader.acquireNextImage();
            if (image == null) return null;
            try (HardwareBuffer buffer = image.getHardwareBuffer()) {
                Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB));
                return hw == null ? null : hw.copy(Bitmap.Config.ARGB_8888, false);
            } finally {
                image.close();
            }
        } finally {
            renderer.destroy();
            reader.close();
        }
    }
}
