package com.android.grafika;

import android.gesture.GestureOverlayView;

import com.android.grafika.gles.Drawable2d;
import com.android.grafika.gles.FullFrameRect;
import com.android.grafika.gles.GlUtil;
import com.android.grafika.gles.Texture2dProgram;

public class DualTextureFullFrameRect extends FullFrameRect {
    private final Drawable2d mRectDrawable = new Drawable2d(Drawable2d.Prefab.FULL_RECTANGLE);
    private Texture2dProgram mProgram;

    public DualTextureFullFrameRect(Texture2dProgram program) {
        super(program);
    }

    public void drawFrame(int textureId1, int textureId2, float[] texMatrix) {
        // Use the identity matrix for MVP so our 2x2 FULL_RECTANGLE covers the viewport.
        mProgram.draw(GlUtil.IDENTITY_MATRIX, mRectDrawable.getVertexArray(), 0,
                mRectDrawable.getVertexCount(), mRectDrawable.getCoordsPerVertex(),
                mRectDrawable.getVertexStride(),
                texMatrix, mRectDrawable.getTexCoordArray(), textureId1,
                mRectDrawable.getTexCoordStride(), textureId2);
    }
}
