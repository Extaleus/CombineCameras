/*
 * Copyright 2013 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.grafika.combine;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.EGL14;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.RequiresApi;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.android.grafika.R;
import com.android.grafika.combine.gles.FullFrameRect;
import com.android.grafika.combine.gles.Texture2dProgram;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Shows the camera preview on screen while simultaneously recording it to a .mp4 file.
 * <p>
 * Every time we receive a frame from the camera, we need to:
 * <ul>
 * <li>Render the frame to the SurfaceView, on GLSurfaceView's renderer thread.
 * <li>Render the frame to the mediacodec's input surface, on the encoder thread, if
 *     recording is enabled.
 * </ul>
 * <p>
 * At any given time there are four things in motion:
 * <ol>
 * <li>The UI thread, embodied by this Activity.  We must respect -- or work around -- the
 *     app lifecycle changes.  In particular, we need to release and reacquire the Camera
 *     so that, if the user switches away from us, we're not preventing another app from
 *     using the camera.
 * <li>The Camera, which will busily generate preview frames once we hand it a
 *     SurfaceTexture.  We'll get notifications on the main UI thread unless we define a
 *     Looper on the thread where the SurfaceTexture is created (the GLSurfaceView renderer
 *     thread).
 * <li>The video encoder thread, embodied by TextureMovieEncoder.  This needs to share
 *     the Camera preview external texture with the GLSurfaceView renderer, which means the
 *     EGLContext in this thread must be created with a reference to the renderer thread's
 *     context in hand.
 * <li>The GLSurfaceView renderer thread, embodied by CameraSurfaceRenderer.  The thread
 *     is created for us by GLSurfaceView.  We don't get callbacks for pause/resume or
 *     thread startup/shutdown, though we could generate messages from the Activity for most
 *     of these things.  The EGLContext created on this thread must be shared with the
 *     video encoder, and must be used to create a SurfaceTexture that is used by the
 *     Camera.  As the creator of the SurfaceTexture, it must also be the one to call
 *     updateTexImage().  The renderer thread is thus at the center of a multi-thread nexus,
 *     which is a bit awkward since it's the thread we have the least control over.
 * </ol>
 * <p>
 * GLSurfaceView is fairly painful here.  Ideally we'd create the video encoder, create
 * an EGLContext for it, and pass that into GLSurfaceView to share.  The API doesn't allow
 * this, so we have to do it the other way around.  When GLSurfaceView gets torn down
 * (say, because we rotated the device), the EGLContext gets tossed, which means that when
 * it comes back we have to re-create the EGLContext used by the video encoder.  (And, no,
 * the "preserve EGLContext on pause" feature doesn't help.)
 * <p>
 * We could simplify this quite a bit by using TextureView  instead of GLSurfaceView, but that
 * comes with a performance hit.  We could also have the renderer thread drive the video
 * encoder directly, allowing them to work from a single EGLContext, but it's useful to
 * decouple the operations, and it's generally unwise to perform disk I/O on the thread that
 * renders your UI.
 * <p>
 * We want to access Camera from the UI thread (setup, teardown) and the renderer thread
 * (configure SurfaceTexture, start preview), but the API says you can only access the object
 * from a single thread.  So we need to pick one thread to own it, and the other thread has to
 * access it remotely.  Some things are simpler if we let the renderer thread manage it,
 * but we'd really like to be sure that Camera is released before we leave onPause(), which
 * means we need to make a synchronous call from the UI thread into the renderer thread, which
 * we don't really have full control over.  It's less scary to have the UI thread own Camera
 * and have the renderer call back into the UI thread through the standard Handler mechanism.
 * <p>
 * (The <a href="http://developer.android.com/training/camera/cameradirect.html#TaskOpenCamera">
 * camera docs</a> recommend accessing the camera from a non-UI thread to avoid bogging the
 * UI thread down.  Since the GLSurfaceView-managed renderer thread isn't a great choice,
 * we might want to create a dedicated camera thread.  Not doing that here.)
 * <p>
 * With three threads working simultaneously (plus Camera causing periodic events as frames
 * arrive) we have to be very careful when communicating state changes.  In general we want
 * to send a message to the thread, rather than directly accessing state in the object.
 * <p>
 * &nbsp;
 * <p>
 * To exercise the API a bit, the video encoder is required to survive Activity restarts.  In the
 * current implementation it stops recording but doesn't stop time from advancing, so you'll
 * see a pause in the video.  (We could adjust the timer to make it seamless, or output a
 * "paused" message and hold on that in the recording, or leave the Camera running so it
 * continues to generate preview frames while the Activity is paused.)  The video encoder object
 * is managed as a static property of the Activity.
 */
public class CameraCaptureActivity extends AppCompatActivity
        implements SurfaceTexture.OnFrameAvailableListener
//        , OnItemSelectedListener
        {
    private static final String TAG = MainActivity.TAG;


    private static final boolean VERBOSE = false;

//    static final int FILTER_NONE = 0;

    private GLSurfaceView mGLSurfaceView;
    private GLSurfaceView mGLSurfaceView2;
    private CameraSurfaceRenderer mRenderer;
    private CameraSurfaceRenderer mRenderer2;
    private Camera mCamera;
    private Camera mCamera2;
    private CameraHandler mCameraHandler;
    private CameraHandler2 mCameraHandler2;
    private boolean mRecordingEnabled;      // controls button state

    private int mCameraPreviewWidth, mCameraPreviewHeight;
    private int mCameraPreviewWidth2, mCameraPreviewHeight2;

    // this is static so it survives activity restarts
    private static final TextureMovieEncoder sVideoEncoder = new TextureMovieEncoder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate started: " + this);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_capture);

        File outputFile = new File(getFilesDir(), "camera-test.mp4");
//        TextView fileText = findViewById(R.id.cameraOutputFile_text);
//        fileText.setText(outputFile.toString());

        // Define a handler that receives camera-control messages from other threads.  All calls
        // to Camera must be made on the same thread.  Note we create this before the renderer
        // thread, so we know the fully-constructed object will be visible.
        mCameraHandler = new CameraHandler(this);
        mCameraHandler2 = new CameraHandler2(this);

        mRecordingEnabled = sVideoEncoder.isRecording();

        // Configure the GLSurfaceView.  This will start the Renderer thread, with an
        // appropriate EGL context.
        mGLSurfaceView = findViewById(R.id.cameraPreview_surfaceView);
        mGLSurfaceView2 = findViewById(R.id.cameraPreview_surfaceView2);
        mGLSurfaceView.setEGLContextClientVersion(2);     // select GLES 2.0
        mGLSurfaceView2.setEGLContextClientVersion(2);     // select GLES 2.0
        mRenderer = new CameraSurfaceRenderer(mCameraHandler, mCameraHandler2, sVideoEncoder, outputFile);
        mRenderer2 = new CameraSurfaceRenderer(mCameraHandler, mCameraHandler2, sVideoEncoder, outputFile);
        mGLSurfaceView.setRenderer(mRenderer);
        mGLSurfaceView2.setRenderer(mRenderer2);
        mGLSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        mGLSurfaceView2.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        Log.d(TAG, "onCreate complete: " + this);
    }

    @Override
    protected void onResume() {
        Log.d(TAG, "onResume started: " + this);
        super.onResume();
        updateControls();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (mCamera == null) {
                openCamera(1280, 720);      // updates mCameraPreviewWidth/Height
            }else{
                Toast.makeText(this, "need camera permission", Toast.LENGTH_LONG).show();
                finish();
            }
            if (mCamera2 == null) {
                openCamera2(1280, 720);      // updates mCameraPreviewWidth/Height
            }else{
                Toast.makeText(this, "need camera permission", Toast.LENGTH_LONG).show();
                finish();
            }

        }

        mGLSurfaceView.onResume();
        mGLSurfaceView2.onResume();
        mGLSurfaceView.queueEvent(new Runnable() {
            @Override
            public void run() {
                mRenderer.setCameraPreviewSize(mCameraPreviewWidth, mCameraPreviewHeight);
            }
        });
        mGLSurfaceView2.queueEvent(new Runnable() {
            @Override
            public void run() {
                mRenderer2.setCameraPreviewSize(mCameraPreviewWidth2, mCameraPreviewHeight2);
            }
        });

        Log.d(TAG, "onResume complete: " + this);
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause started: " + this);
        super.onPause();

        Log.d(TAG, "onPause: Releasing camera");
        releaseCamera();
        releaseCamera2();

        mGLSurfaceView.queueEvent(new Runnable() {
            @Override
            public void run() {
                // Tell the renderer that it's about to be paused so it can clean up.
                mRenderer.notifyPausing();
            }
        });
        mGLSurfaceView2.queueEvent(new Runnable() {
            @Override
            public void run() {
                // Tell the renderer that it's about to be paused so it can clean up.
                mRenderer2.notifyPausing();
            }
        });
        mGLSurfaceView2.onPause();

        Log.d(TAG, "onPause complete: " + this);
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
        mCameraHandler.invalidateHandler();     // paranoia
        mCameraHandler2.invalidateHandler();     // paranoia
    }

/*
    // spinner selected
    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
        Spinner spinner = (Spinner) parent;
        final int filterNum = spinner.getSelectedItemPosition();

        Log.d(TAG, "onItemSelected: " + filterNum);
        mGLSurfaceView.queueEvent(new Runnable() {
            @Override
            public void run() {
                // notify the renderer that we want to change the encoder's state
                mRenderer.changeFilterMode(filterNum);
            }
        });
    }
    @Override
    public void onNothingSelected(AdapterView<?> parent) {
    }
*/

            /**
     * Opens a camera, and attempts to establish preview mode at the specified width and height.
     * <p>
     * Sets mCameraPreviewWidth and mCameraPreviewHeight to the actual width/height of the preview.
     */
    private void openCamera(int desiredWidth, int desiredHeight) {
        Log.d(TAG, "openCamera started: " + this);

        if (mCamera != null) {
            throw new RuntimeException("camera already initialized");
        }

        Camera.CameraInfo info = new Camera.CameraInfo();

        // Try to find a front-facing camera (e.g. for videoconferencing).
        int numCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numCameras; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                mCamera = Camera.open(i);
                break;
            }
        }
        if (mCamera == null) {
            Log.d(TAG, "No back camera found; opening default");
            mCamera = Camera.open();    // opens first back-facing camera
        }
        if (mCamera == null) {
            throw new RuntimeException("Unable to open camera");
        }

        Camera.Parameters parms = mCamera.getParameters();

        Log.d(TAG, "choosePreviewSize started: " + this);
        CameraUtils.choosePreviewSize(parms, desiredWidth, desiredHeight);

        // Give the camera a hint that we're recording video.  This can have a big
        // impact on frame rate.
        parms.setRecordingHint(true);

        // leave the frame rate set to default
        mCamera.setParameters(parms);

        int[] fpsRange = new int[2];
        Camera.Size mCameraPreviewSize = parms.getPreviewSize();
        parms.getPreviewFpsRange(fpsRange);
        String previewFacts = mCameraPreviewSize.width + "x" + mCameraPreviewSize.height;
        if (fpsRange[0] == fpsRange[1]) {
            previewFacts += " @" + (fpsRange[0] / 1000.0) + "fps";
        } else {
            previewFacts += " @[" + (fpsRange[0] / 1000.0) +
                    " - " + (fpsRange[1] / 1000.0) + "] fps";
        }
        TextView text = findViewById(R.id.cameraParams_text);
        TextView text2 = findViewById(R.id.cameraParamsFront_text);
        text.setText(String.format("Back:%s", previewFacts));

        mCameraPreviewWidth = mCameraPreviewSize.width;
        mCameraPreviewHeight = mCameraPreviewSize.height;

        AspectFrameLayout layout = findViewById(R.id.cameraPreview_afl);

        Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();

        if (display.getRotation() == Surface.ROTATION_0) {
            mCamera.setDisplayOrientation(90);
            layout.setAspectRatio((double) mCameraPreviewHeight / mCameraPreviewWidth);
        } else if (display.getRotation() == Surface.ROTATION_270) {
            layout.setAspectRatio((double) mCameraPreviewHeight / mCameraPreviewWidth);
            mCamera.setDisplayOrientation(180);
        } else {
            // Set the preview aspect ratio.
            layout.setAspectRatio((double) mCameraPreviewWidth / mCameraPreviewHeight);
        }

        Log.d(TAG, "openCamera complete: " + this);
    }

    private void openCamera2(int desiredWidth, int desiredHeight) {
        Log.d(TAG, "openCamera2 started: " + this);

        if (mCamera2 != null) {
            throw new RuntimeException("camera already initialized");
        }

        Camera.CameraInfo info = new Camera.CameraInfo();

        // Try to find a front-facing camera (e.g. for videoconferencing).
        int numCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numCameras; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                mCamera2 = Camera.open(i);
                break;
            }
        }
        if (mCamera2 == null) {
            Log.d(TAG, "No front camera found; opening default");
            mCamera = Camera.open();    // opens first back-facing camera
        }
        if (mCamera2 == null) {
            throw new RuntimeException("Unable to open camera");
        }

        Camera.Parameters parms2 = mCamera2.getParameters();

        Log.d(TAG, "choosePreviewSize started: " + this);
        CameraUtils.choosePreviewSize(parms2, desiredWidth, desiredHeight);

        // Give the camera a hint that we're recording video.  This can have a big
        // impact on frame rate.
        parms2.setRecordingHint(true);

        // leave the frame rate set to default
        mCamera2.setParameters(parms2);

        int[] fpsRange = new int[2];
        Camera.Size mCameraPreviewSize2 = parms2.getPreviewSize();
        parms2.getPreviewFpsRange(fpsRange);
        String previewFacts2 = mCameraPreviewSize2.width + "x" + mCameraPreviewSize2.height;
        if (fpsRange[0] == fpsRange[1]) {
            previewFacts2 += " @" + (fpsRange[0] / 1000.0) + "fps";
        } else {
            previewFacts2 += " @[" + (fpsRange[0] / 1000.0) +
                    " - " + (fpsRange[1] / 1000.0) + "] fps";
        }
        TextView text2 = findViewById(R.id.cameraParamsFront_text);
        text2.setText(String.format("Front:%s", previewFacts2));

        mCameraPreviewWidth = mCameraPreviewSize2.width;
        mCameraPreviewHeight = mCameraPreviewSize2.height;

        AspectFrameLayout layout = findViewById(R.id.cameraPreview_afl);

        Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();

        if (display.getRotation() == Surface.ROTATION_0) {
            mCamera.setDisplayOrientation(90);
            layout.setAspectRatio((double) mCameraPreviewHeight / mCameraPreviewWidth);
        } else if (display.getRotation() == Surface.ROTATION_270) {
            layout.setAspectRatio((double) mCameraPreviewHeight / mCameraPreviewWidth);
            mCamera.setDisplayOrientation(180);
        } else {
            // Set the preview aspect ratio.
            layout.setAspectRatio((double) mCameraPreviewWidth / mCameraPreviewHeight);
        }

        Log.d(TAG, "openCamera complete: " + this);
    }

    /**
     * Stops camera preview, and releases the camera to the system.
     */
    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
            Log.d(TAG, "releaseCamera done");
        }
    }
    private void releaseCamera2() {
        if (mCamera2 != null) {
            mCamera2.stopPreview();
            mCamera2.release();
            mCamera2 = null;
            Log.d(TAG, "releaseCamera done");
        }
    }

    /**
     * onClick handler for "record" button.
     */
    public void clickToggleRecording(@SuppressWarnings("unused") View unused) {
        mRecordingEnabled = !mRecordingEnabled;
        mGLSurfaceView.queueEvent(new Runnable() {
            @Override
            public void run() {
                // notify the renderer that we want to change the encoder's state
                mRenderer.changeRecordingState(mRecordingEnabled);
            }
        });
        updateControls();
    }

//    /**
//     * onClick handler for "rebind" checkbox.
//     */
//    public void clickRebindCheckbox(View unused) {
//        CheckBox cb = (CheckBox) findViewById(R.id.rebindHack_checkbox);
//        TextureRender.sWorkAroundContextProblem = cb.isChecked();
//    }

    /**
     * Updates the on-screen controls to reflect the current state of the app.
     */
    private void updateControls() {
        Button toggleRelease = findViewById(R.id.toggleRecording_button);
        int id = mRecordingEnabled ?
                R.string.toggleRecordingOff : R.string.toggleRecordingOn;
        toggleRelease.setText(id);
    }

    /**
     * Connects the SurfaceTexture to the Camera preview output, and starts the preview.
     */
    private void handleSetSurfaceTexture(SurfaceTexture st) {
        st.setOnFrameAvailableListener(this);
        try {
            mCamera.setPreviewTexture(st);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        mCamera.startPreview();
    }

    private void handleSetSurfaceTexture2(SurfaceTexture st) {
        st.setOnFrameAvailableListener(this);
        try {
            mCamera2.setPreviewTexture(st);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        mCamera2.startPreview();
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        /* The SurfaceTexture uses this to signal the availability of a new frame.  The
         thread that "owns" the external texture associated with the SurfaceTexture (which,
         by virtue of the context being shared, *should* be either one) needs to call
         updateTexImage() to latch the buffer.

         Once the buffer is latched, the GLSurfaceView thread can signal the encoder thread.
         This feels backward -- we want recording to be prioritized over rendering -- but
         since recording is only enabled some of the time it's easier to do it this way.

         Since GLSurfaceView doesn't establish a Looper, this will *probably* execute on
         the main UI thread.  Fortunately, requestRender() can be called from any thread,
         so it doesn't really matter.
        */
        if (VERBOSE) Log.d(TAG, "ST onFrameAvailable");
        mGLSurfaceView.requestRender();
//        mGLSurfaceView2.requestRender();
    }

    /**
     * Handles camera operation requests from other threads.  Necessary because the Camera
     * must only be accessed from one thread.
     * <p>
     * The object is created on the UI thread, and all handlers run there.  Messages are
     * sent from other threads, using sendMessage().
     */
    static class CameraHandler extends Handler {
        // Статическая константа, представляющая тип сообщения
        // для установки текстуры поверхности
        public static final int MSG_SET_SURFACE_TEXTURE = 0;

        // Weak reference to the Activity; only access this from the UI thread.
        // Слабая ссылка на активность CameraCaptureActivity,
        // которая используется для предотвращения утечек памяти.
        private final WeakReference<CameraCaptureActivity> mWeakActivity;

        // Конструктор класса, который принимает активность
        // CameraCaptureActivity и сохраняет на неё слабую ссылку.
        public CameraHandler(CameraCaptureActivity activity) {
            mWeakActivity = new WeakReference<CameraCaptureActivity>(activity);
        }

        /**
         * Drop the reference to the activity.  Useful as a paranoid measure to ensure that
         * attempts to access a stale Activity through a handler are caught.
         */
        // Очищает слабую ссылку на активность.
        public void invalidateHandler() {
            mWeakActivity.clear();
        }

        // Переопределяет метод handleMessage() родительского класса Handler.
        // Этот метод вызывается, когда в очередь сообщений поступает новое сообщение.
        @Override  // runs on UI thread
        public void handleMessage(Message inputMessage) {
            int what = inputMessage.what;
            Log.d(TAG, "CameraHandler [" + this + "]: what=" + what);

            // Получение активноси CameraCaptureActivity из слабой ссылки.
            CameraCaptureActivity activity = mWeakActivity.get();
            if (activity == null) {
                Log.w(TAG, "CameraHandler.handleMessage: activity is null");
                return;
            }

            // Если тип сообщения MSG_SET_SURFACE_TEXTURE, вызывает метод handleSetSurfaceTexture()
            // активности CameraCaptureActivity с текстурой поверхности из объекта Message.
            if (what == MSG_SET_SURFACE_TEXTURE) {
                activity.handleSetSurfaceTexture((SurfaceTexture) inputMessage.obj);
            } else {
                throw new RuntimeException("unknown msg " + what);
            }
        }
    }
    static class CameraHandler2 extends Handler {
        // Статическая константа, представляющая тип сообщения
        // для установки текстуры поверхности
        public static final int MSG_SET_SURFACE_TEXTURE = 0;

        // Weak reference to the Activity; only access this from the UI thread.
        // Слабая ссылка на активность CameraCaptureActivity,
        // которая используется для предотвращения утечек памяти.
        private final WeakReference<CameraCaptureActivity> mWeakActivity;

        // Конструктор класса, который принимает активность
        // CameraCaptureActivity и сохраняет на неё слабую ссылку.
        public CameraHandler2(CameraCaptureActivity activity) {
            mWeakActivity = new WeakReference<CameraCaptureActivity>(activity);
        }

        /**
         * Drop the reference to the activity.  Useful as a paranoid measure to ensure that
         * attempts to access a stale Activity through a handler are caught.
         */
        // Очищает слабую ссылку на активность.
        public void invalidateHandler() {
            mWeakActivity.clear();
        }

        // Переопределяет метод handleMessage() родительского класса Handler.
        // Этот метод вызывается, когда в очередь сообщений поступает новое сообщение.
        @Override  // runs on UI thread
        public void handleMessage(Message inputMessage) {
            int what = inputMessage.what;
            Log.d(TAG, "CameraHandler2 [" + this + "]: what=" + what);

            // Получение активноси CameraCaptureActivity из слабой ссылки.
            CameraCaptureActivity activity = mWeakActivity.get();
            if (activity == null) {
                Log.w(TAG, "CameraHandler2.handleMessage: activity is null");
                return;
            }

            // Если тип сообщения MSG_SET_SURFACE_TEXTURE, вызывает метод handleSetSurfaceTexture()
            // активности CameraCaptureActivity с текстурой поверхности из объекта Message.
            if (what == MSG_SET_SURFACE_TEXTURE) {
                activity.handleSetSurfaceTexture2((SurfaceTexture) inputMessage.obj);
            } else {
                throw new RuntimeException("unknown msg " + what);
            }
        }
    }
}

/*
  Renderer object for our GLSurfaceView.
  <p>
  Do not call any methods here directly from another thread -- use the
  GLSurfaceView#queueEvent() call.
 */

// Класс CameraSurfaceRenderer реализует интерфейс GLSurfaceView.Renderer,
// который используется для отрисовки графики на поверхности OpenGL.
// Этот класс отвечает за отображение предварительного просмотра камеры и запись видео.

/**
 * Описание работы класса:
 * При создании поверхности OpenGL создается объект SurfaceTexture и передается в обработчик сообщений для установки в предварительный просмотр камеры.
 * При изменении размера поверхности OpenGL ничего не происходит.
 * При отрисовке кадра обновляется текстура SurfaceTexture, и в зависимости от состояния записи видео либо отображается на экране, либо передается кодеру видео.
 * Если включен фильтр, то он применяется к кадру перед отображением.
 * Если идет запись видео, то в углу экрана рисуется красный прямоугольник.
 */
class CameraSurfaceRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = MainActivity.TAG;
    private static final boolean VERBOSE = false;
    private static final int RECORDING_OFF = 0;
    private static final int RECORDING_ON = 1;
    private static final int RECORDING_RESUMED = 2;

    // Обработчик сообщений для взаимодействия с главным потоком.
    private final CameraCaptureActivity.CameraHandler mCameraHandler;
    private final CameraCaptureActivity.CameraHandler2 mCameraHandler2;
    // Объект для кодирования видео.
    private final TextureMovieEncoder mVideoEncoder;
    // Файл для сохранения закодированного видео.
    private final File mOutputFile;

    // Объект для отрисовки полноэкранного прямоугольника.
    private FullFrameRect mFullScreen;

    // Матрица преобразования для текстуры.
    private final float[] mSTMatrix = new float[16];
    // Идентификатор текстуры.
    private int mTextureId;

    // ?
    private int mTextureId2;

    // Объект SurfaceTexture для получения кадров с камеры.
    private SurfaceTexture mSurfaceTexture;
    // ?
    private SurfaceTexture mSurfaceTexture2;

    // Флаг, указывающий, включена ли запись видео.
    private boolean mRecordingEnabled;
    // состояние записи видео.
    private int mRecordingStatus;
    // счетчик кадров.
    private int mFrameCount;

    // width/height of the incoming camera preview frames
    // флаг, указывающий, что размер входящих кадров был обновлен.
    private boolean mIncomingSizeUpdated;
    // ширина входящих кадров.
    private int mIncomingWidth;
    // высота входящих кадров.
    private int mIncomingHeight;

    // текущий фильтр, применяемый к кадрам.
//    private int mCurrentFilter;
    // новый фильтр, который будет применен к кадрам.
//    private final int mNewFilter;

    /**
     * Constructs CameraSurfaceRenderer.
     * <p>
     *
     * @param cameraHandler Handler for communicating with UI thread
     * @param movieEncoder  video encoder object
     * @param outputFile    output file for encoded video; forwarded to movieEncoder
     */
    public CameraSurfaceRenderer(CameraCaptureActivity.CameraHandler cameraHandler, CameraCaptureActivity.CameraHandler2 cameraHandler2,
                                 TextureMovieEncoder movieEncoder, File outputFile) {
        mCameraHandler = cameraHandler;
        mCameraHandler2 = cameraHandler2;
        mVideoEncoder = movieEncoder;
        mOutputFile = outputFile;

        mTextureId = -1;
        // ?
        mTextureId2 = -1;

        mRecordingStatus = -1;
        mRecordingEnabled = false;
        mFrameCount = -1;

        mIncomingSizeUpdated = false;
        mIncomingWidth = mIncomingHeight = -1;

        // We could preserve the old filter mode, but currently not bothering.
//        mCurrentFilter = -1;
//        mNewFilter = CameraCaptureActivity.FILTER_NONE;
    }

    /**
     * Notifies the renderer thread that the activity is pausing.
     * <p>
     * For best results, call this *after* disabling Camera preview.
     */
    // уведомляет рендерер о том, что активность приостанавливается.
    public void notifyPausing() {
        if (mSurfaceTexture != null) {
            Log.d(TAG, "renderer pausing -- releasing SurfaceTexture");
            mSurfaceTexture.release();
            mSurfaceTexture = null;
        }
        if (mFullScreen != null) {
            mFullScreen.release(false);     // assume the GLSurfaceView EGL context is about
            mFullScreen = null;             //  to be destroyed
        }
        mIncomingWidth = mIncomingHeight = -1;
    }

    /**
     * Notifies the renderer that we want to stop or start recording.
     */
    // изменяет состояние записи видео.
    public void changeRecordingState(boolean isRecording) {
        Log.d(TAG, "changeRecordingState: was " + mRecordingEnabled + " now " + isRecording);
        mRecordingEnabled = isRecording;
    }

    /*
      Changes the filter that we're applying to the camera preview.
     */
    // изменяет режим фильтра, применяемого к кадрам.
//    public void changeFilterMode(int filter) {
//        mNewFilter = filter;
//    }

    // обновляет программу фильтра.
//    public void updateFilter() {
//        Texture2dProgram.ProgramType programType;
//        float[] kernel = null;
//        float colorAdj = 0.0f;
//
//        Log.d(TAG, "Updating filter to " + mNewFilter);
//        if (mNewFilter == CameraCaptureActivity.FILTER_NONE) {
//            programType = Texture2dProgram.ProgramType.TEXTURE_EXT;
//            /*
//            case CameraCaptureActivity.FILTER_BLACK_WHITE:
//                // (In a previous version the TEXTURE_EXT_BW variant was enabled by a flag called
//                // ROSE_COLORED_GLASSES, because the shader set the red channel to the B&W color
//                // and green/blue to zero.)
//
//                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_BW;
//                break;
//            case CameraCaptureActivity.FILTER_BLUR:
//                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
//                kernel = new float[]{
//                        1f / 16f, 2f / 16f, 1f / 16f,
//                        2f / 16f, 4f / 16f, 2f / 16f,
//                        1f / 16f, 2f / 16f, 1f / 16f};
//                break;
//            case CameraCaptureActivity.FILTER_SHARPEN:
//                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
//                kernel = new float[]{
//                        0f, -1f, 0f,
//                        -1f, 5f, -1f,
//                        0f, -1f, 0f};
//                break;
//            case CameraCaptureActivity.FILTER_EDGE_DETECT:
//                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
//                kernel = new float[]{
//                        -1f, -1f, -1f,
//                        -1f, 8f, -1f,
//                        -1f, -1f, -1f};
//                break;
//            case CameraCaptureActivity.FILTER_EMBOSS:
//                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
//                kernel = new float[]{
//                        -1f, 2f, 0f,
//                        0f, -1f, 0f,
//                        0f, 0f, -1f};
//                colorAdj = 0.5f;
//                break;
//*/
//        } else {
//            throw new RuntimeException("Unknown filter mode " + mNewFilter);
//        }
//
//        // Do we need a whole new program?  (We want to avoid doing this if we don't have
//        // too -- compiling a program could be expensive.)
//        if (programType != mFullScreen.getProgram().getProgramType()) {
//            mFullScreen.changeProgram(new Texture2dProgram(programType));
//            // If we created a new program, we need to initialize the texture width/height.
//            mIncomingSizeUpdated = true;
//        }
//
//        // Update the filter kernel (if any).
//        if (kernel != null) {
//            mFullScreen.getProgram().setKernel(kernel, colorAdj);
//        }
//
//        mCurrentFilter = mNewFilter;
//    }

    /**
     * Records the size of the incoming camera preview frames.
     * <p>
     * It's not clear whether this is guaranteed to execute before or after onSurfaceCreated(),
     * so we assume it could go either way.  (Fortunately they both run on the same thread,
     * so we at least know that they won't execute concurrently.)
     */
    // устанавливает размер входящих кадров с камеры.
    public void setCameraPreviewSize(int width, int height) {
        Log.d(TAG, "setCameraPreviewSize");
        mIncomingWidth = width;
        mIncomingHeight = height;
        mIncomingSizeUpdated = true;
    }

    // вызывается при создании поверхности OpenGL.
    @RequiresApi(api = Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1)
    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        Log.d(TAG, "onSurfaceCreated");

        // We're starting up or coming back.  Either way we've got a new EGLContext that will
        // need to be shared with the video encoder, so figure out if a recording is already
        // in progress.
        mRecordingEnabled = mVideoEncoder.isRecording();
        if (mRecordingEnabled) {
            mRecordingStatus = RECORDING_RESUMED;
        } else {
            mRecordingStatus = RECORDING_OFF;
        }

        // Set up the texture blitter that will be used for on-screen display.  This
        // is *not* applied to the recording, because that uses a separate shader.
        mFullScreen = new FullFrameRect(
                new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT));

        mTextureId = mFullScreen.createTextureObject();

        // Create a SurfaceTexture, with an external texture, in this EGL context.  We don't
        // have a Looper in this thread -- GLSurfaceView doesn't create one -- so the frame
        // available messages will arrive on the main thread.
        mSurfaceTexture = new SurfaceTexture(mTextureId);

        // ?
        mTextureId2 = mFullScreen.createTextureObject();
        mSurfaceTexture2 = new SurfaceTexture(mTextureId2);

        // Tell the UI thread to enable the camera preview.
        mCameraHandler.sendMessage(mCameraHandler.obtainMessage(
                CameraCaptureActivity.CameraHandler.MSG_SET_SURFACE_TEXTURE, mSurfaceTexture)
        );
        // ?
        mCameraHandler2.sendMessage(mCameraHandler2.obtainMessage(
                CameraCaptureActivity.CameraHandler2.MSG_SET_SURFACE_TEXTURE, mSurfaceTexture2));
    }

    // вызывается при изменении размера поверхности OpenGL.
    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        Log.d(TAG, "onSurfaceChanged " + width + "x" + height);
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public void onDrawFrame(GL10 unused) {
        if (VERBOSE) Log.d(TAG, "onDrawFrame tex=" + mTextureId);
        boolean showBox = false;

        // Latch the latest frame.  If there isn't anything new, we'll just re-use whatever
        // was there before.
        mSurfaceTexture.updateTexImage();

        // ?
        mSurfaceTexture2.updateTexImage();

        // If the recording state is changing, take care of it here.  Ideally we wouldn't
        // be doing all this in onDrawFrame(), but the EGLContext sharing with GLSurfaceView
        // makes it hard to do elsewhere.
        if (mRecordingEnabled) {
            switch (mRecordingStatus) {
                case RECORDING_OFF:
                    Log.d(TAG, "START recording");
                    // start recording
                    mVideoEncoder.startRecording(new TextureMovieEncoder.EncoderConfig(
                            mOutputFile, 640, 480, 1000000, EGL14.eglGetCurrentContext()));
                    mRecordingStatus = RECORDING_ON;
                    break;
                case RECORDING_RESUMED:
                    Log.d(TAG, "RESUME recording");
                    mVideoEncoder.updateSharedContext(EGL14.eglGetCurrentContext());
                    mRecordingStatus = RECORDING_ON;
                    break;
                case RECORDING_ON:
                    // yay
                    break;
                default:
                    throw new RuntimeException("unknown status " + mRecordingStatus);
            }
        } else {
            switch (mRecordingStatus) {
                case RECORDING_ON:
                case RECORDING_RESUMED:
                    // stop recording
                    Log.d(TAG, "STOP recording");
                    mVideoEncoder.stopRecording();
                    mRecordingStatus = RECORDING_OFF;
                    break;
                case RECORDING_OFF:
                    // yay
                    break;
                default:
                    throw new RuntimeException("unknown status " + mRecordingStatus);
            }
        }

        // Set the video encoder's texture name.  We only need to do this once, but in the
        // current implementation it has to happen after the video encoder is started, so
        // we just do it here.
        //
        // TODO: be less lame.
        mVideoEncoder.setTextureId(mTextureId);

        // Tell the video encoder thread that a new frame is available.
        // This will be ignored if we're not actually recording.
        mVideoEncoder.frameAvailable(mSurfaceTexture);

        if (mIncomingWidth <= 0 || mIncomingHeight <= 0) {
            // Texture size isn't set yet.  This is only used for the filters, but to be
            // safe we can just skip drawing while we wait for the various races to resolve.
            // (This seems to happen if you toggle the screen off/on with power button.)
            Log.i(TAG, "Drawing before incoming texture size set; skipping");
            return;
        }

        // Update the filter, if necessary.
//        if (mCurrentFilter != mNewFilter) {
//            updateFilter();
//        }

        if (mIncomingSizeUpdated) {
            mFullScreen.getProgram().setTexSize(mIncomingWidth, mIncomingHeight);
            mIncomingSizeUpdated = false;
        }

        // Draw the video frame.
        mSurfaceTexture.getTransformMatrix(mSTMatrix);

        mSurfaceTexture2.getTransformMatrix(mSTMatrix);
        // ?
//        Bitmap bitmap = BitmapFactory.decodeResource(Resources.getSystem(), R.drawable.ic_launcher);
////        mFullScreen.drawFrame(mTextureId, mSTMatrix);
//        // ?
//        mTextureId2

        mFullScreen.drawFrame(mTextureId, mTextureId2, mSTMatrix);

        // Draw a flashing box if we're recording.  This only appears on screen.
        showBox = (mRecordingStatus == RECORDING_ON);
        if (showBox && (++mFrameCount & 0x04) == 0) {
            drawBox();
        }
    }

    /**
     * Draws a red box in the corner.
     */
    // вызывается при отрисовке каждого кадра.
    private void drawBox() {
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        GLES20.glScissor(0, 0, 100, 100);
        GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    }
}