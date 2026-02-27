package com.oney.WebRTCModule;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.hardware.Camera;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.util.Pair;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.ReadableMap;

import org.webrtc.Camera1Capturer;
import org.webrtc.Camera1Helper;
import org.webrtc.Camera2Capturer;
import org.webrtc.Camera2Helper;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.Size;
import org.webrtc.VideoCapturer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class CameraCaptureController extends AbstractVideoCaptureController {
    /**
     * The {@link Log} tag with which {@code CameraCaptureController} is to log.
     */
    private static final String TAG = CameraCaptureController.class.getSimpleName();
    private static final String USB_CAMERA_PREFS_NAME = "CIPHER_USB_CAMERA";
    private static final String USB_CAMERA_ENABLED_KEY = "prefer_usb_camera";
    private static final float ZOOM_STEP = 0.1f;
    private static final AtomicReference<CameraCaptureController> ACTIVE_CONTROLLER = new AtomicReference<>();

    private boolean isFrontFacing;

    private final Context context;
    private final CameraEnumerator cameraEnumerator;
    private final ReadableMap constraints;
    private float camera2ZoomRatio = 1.0f;

    /**
     * The {@link CameraEventsHandler} used with
     * {@link CameraEnumerator#createCapturer}. Cached because the
     * implementation does not do anything but logging unspecific to the camera
     * device's name anyway.
     */
    private final CameraEventsHandler cameraEventsHandler = new CameraEventsHandler();

    public CameraCaptureController(Context context, CameraEnumerator cameraEnumerator, ReadableMap constraints) {
        super(constraints.getInt("width"), constraints.getInt("height"), constraints.getInt("frameRate"));

        this.context = context;
        this.cameraEnumerator = cameraEnumerator;
        this.constraints = constraints;
    }

    public static boolean adjustActiveCameraZoomByStep(float stepDelta) {
        CameraCaptureController active = ACTIVE_CONTROLLER.get();
        if (active == null) {
            Log.d(TAG, "Zoom ignored: no active camera capture controller");
            return false;
        }

        return active.adjustZoom(stepDelta);
    }

    public static boolean zoomInActiveCamera() {
        return adjustActiveCameraZoomByStep(ZOOM_STEP);
    }

    public static boolean zoomOutActiveCamera() {
        return adjustActiveCameraZoomByStep(-ZOOM_STEP);
    }

    public void switchCamera() {
        if (videoCapturer instanceof CameraVideoCapturer) {
            CameraVideoCapturer capturer = (CameraVideoCapturer) videoCapturer;
            String[] deviceNames = cameraEnumerator.getDeviceNames();
            int deviceCount = deviceNames.length;

            // Nothing to switch to.
            if (deviceCount < 2) {
                return;
            }

            // The usual case.
            if (deviceCount == 2) {
                capturer.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
                    @Override
                    public void onCameraSwitchDone(boolean b) {
                        isFrontFacing = b;
                    }

                    @Override
                    public void onCameraSwitchError(String s) {
                        Log.e(TAG, "Error switching camera: " + s);
                    }
                });
                return;
            }

            // If we are here the device has more than 2 cameras. Cycle through them
            // and switch to the first one of the desired facing mode.
            switchCamera(!isFrontFacing, deviceCount);
        }
    }

    @Override
    protected VideoCapturer createVideoCapturer() {
        String deviceId = ReactBridgeUtil.getMapStrValue(this.constraints, "deviceId");
        String facingMode = ReactBridgeUtil.getMapStrValue(this.constraints, "facingMode");

        Pair<String, VideoCapturer> result = createVideoCapturer(deviceId, facingMode);
        if(result == null) {
            return null;
        }

        String cameraName = result.first;
        VideoCapturer videoCapturer = result.second;

        // Find actual capture format.
        Size actualSize = null;
        if (videoCapturer instanceof Camera1Capturer) {
            int cameraId = Camera1Helper.getCameraId(cameraName);
            actualSize = Camera1Helper.findClosestCaptureFormat(cameraId, targetWidth, targetHeight);
        } else if (videoCapturer instanceof Camera2Capturer) {
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            actualSize = Camera2Helper.findClosestCaptureFormat(cameraManager, cameraName, targetWidth, targetHeight);
        }

        if (actualSize != null) {
            actualWidth = actualSize.width;
            actualHeight = actualSize.height;
        }

        ACTIVE_CONTROLLER.set(this);
        return videoCapturer;
    }

    @Override
    public void startCapture() {
        ACTIVE_CONTROLLER.set(this);
        super.startCapture();
    }

    @Override
    public boolean stopCapture() {
        return super.stopCapture();
    }

    @Override
    public void dispose() {
        super.dispose();
        if (ACTIVE_CONTROLLER.compareAndSet(this, null)) {
            Log.d(TAG, "Cleared active camera capture controller");
        }
    }

    /**
     * Helper function which tries to switch cameras until the desired facing mode is found.
     *
     * @param desiredFrontFacing - The desired front facing value.
     * @param tries - How many times to try switching.
     */
    private void switchCamera(boolean desiredFrontFacing, int tries) {
        CameraVideoCapturer capturer = (CameraVideoCapturer) videoCapturer;

        capturer.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
            @Override
            public void onCameraSwitchDone(boolean b) {
                if (b != desiredFrontFacing) {
                    int newTries = tries - 1;
                    if (newTries > 0) {
                        switchCamera(desiredFrontFacing, newTries);
                    }
                } else {
                    isFrontFacing = desiredFrontFacing;
                }
            }

            @Override
            public void onCameraSwitchError(String s) {
                Log.e(TAG, "Error switching camera: " + s);
            }
        });
    }

    /**
     * Constructs a new {@code VideoCapturer} instance attempting to satisfy
     * specific constraints.
     *
     * @param deviceId the ID of the requested video device. If not
     * {@code null} and a {@code VideoCapturer} can be created for it, then
     * {@code facingMode} is ignored.
     * @param facingMode the facing of the requested video source such as
     * {@code user} and {@code environment}. If {@code null}, "user" is
     * presumed.
     * @return a pair containing the deviceId and {@code VideoCapturer} satisfying the {@code facingMode} or
     * {@code deviceId} constraint, or null.
     */
    @Nullable
    private Pair<String, VideoCapturer> createVideoCapturer(String deviceId, String facingMode) {
        String[] deviceNames = cameraEnumerator.getDeviceNames();
        List<String> failedDevices = new ArrayList<>();

        String cameraName = null;
        try {
            int index = Integer.parseInt(deviceId);
            cameraName = deviceNames[index];
        } catch (Exception e) {
            Log.d(TAG, "failed to find device with id: " + deviceId);
        }

        // If deviceId is specified, then it takes precedence over facingMode.
        if (cameraName != null) {
            VideoCapturer videoCapturer = cameraEnumerator.createCapturer(cameraName, cameraEventsHandler);
            String message = "Create user-specified camera " + cameraName;
            if (videoCapturer != null) {
                Log.d(TAG, message + " succeeded");
                this.isFrontFacing = cameraEnumerator.isFrontFacing(cameraName);
                return new Pair(cameraName, videoCapturer);
            } else {
                // fallback to facingMode
                Log.d(TAG, message + " failed");
                failedDevices.add(cameraName);
            }
        }

        if (shouldPreferUsbCamera()) {
            Pair<String, VideoCapturer> usbResult = tryCreateUsbCameraCapturer();
            if (usbResult != null) {
                return usbResult;
            }

            Pair<String, VideoCapturer> externalResult = tryCreateExternalCamera(deviceNames, failedDevices);
            if (externalResult != null) {
                return externalResult;
            }
        }

        // Otherwise, use facingMode (defaulting to front/user facing).
        final boolean isFrontFacing = facingMode == null || !facingMode.equals("environment");
        for (String name : deviceNames) {
            if (failedDevices.contains(name)) {
                continue;
            }
            if (cameraEnumerator.isFrontFacing(name) != isFrontFacing) {
                continue;
            }
            VideoCapturer videoCapturer = cameraEnumerator.createCapturer(name, cameraEventsHandler);
            String message = "Create camera " + name;
            if (videoCapturer != null) {
                Log.d(TAG, message + " succeeded");
                this.isFrontFacing = cameraEnumerator.isFrontFacing(name);
                return new Pair(name, videoCapturer);
            } else {
                Log.d(TAG, message + " failed");
                failedDevices.add(name);
            }
        }

        // Fallback to any available camera.
        for (String name : deviceNames) {
            if (!failedDevices.contains(name)) {
                VideoCapturer videoCapturer = cameraEnumerator.createCapturer(name, cameraEventsHandler);
                String message = "Create fallback camera " + name;
                if (videoCapturer != null) {
                    Log.d(TAG, message + " succeeded");
                    this.isFrontFacing = cameraEnumerator.isFrontFacing(name);
                    return new Pair(name, videoCapturer);
                } else {
                    Log.d(TAG, message + " failed");
                    failedDevices.add(name);
                }
            }
        }

        Log.w(TAG, "Unable to identify a suitable camera.");

        return null;
    }

    private boolean shouldPreferUsbCamera() {
        try {
            SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(USB_CAMERA_PREFS_NAME, Context.MODE_PRIVATE);
            boolean enabled = prefs.getBoolean(USB_CAMERA_ENABLED_KEY, false);
            Log.d(TAG, "USB camera preference enabled=" + enabled);
            return enabled;
        } catch (Exception error) {
            Log.w(TAG, "Failed to read USB camera preference", error);
            return false;
        }
    }

    @Nullable
    private Pair<String, VideoCapturer> tryCreateExternalCamera(
        String[] deviceNames,
        List<String> failedDevices
    ) {
        for (String name : deviceNames) {
            if (failedDevices.contains(name)) {
                continue;
            }

            boolean front = cameraEnumerator.isFrontFacing(name);
            boolean back = cameraEnumerator.isBackFacing(name);
            boolean external = !front && !back;
            if (!external) {
                continue;
            }

            VideoCapturer videoCapturer = cameraEnumerator.createCapturer(name, cameraEventsHandler);
            String message = "Create external camera " + name;
            if (videoCapturer != null) {
                Log.d(TAG, message + " succeeded");
                this.isFrontFacing = false;
                return new Pair<>(name, videoCapturer);
            }

            Log.d(TAG, message + " failed");
            failedDevices.add(name);
        }

        Log.d(TAG, "No usable external camera capturer was found");
        return null;
    }

    @Nullable
    private Pair<String, VideoCapturer> tryCreateUsbCameraCapturer() {
        try {
            if (!UsbVideoCapturer.isSupported(context)) {
                Log.d(TAG, "USB UVC capturer prerequisites are missing; skipping reflective USB capturer");
                return null;
            }
            VideoCapturer capturer = new UsbVideoCapturer(context);
            Log.d(TAG, "Create reflective USB UVC capturer succeeded");
            this.isFrontFacing = false;
            return new Pair<>("USB-UVC", capturer);
        } catch (Throwable error) {
            Log.w(TAG, "Failed creating reflective USB UVC capturer", error);
            return null;
        }
    }

    private boolean adjustZoom(float zoomDelta) {
        VideoCapturer capturer = videoCapturer;
        if (capturer == null) {
            Log.d(TAG, "Zoom ignored: video capturer not initialized");
            return false;
        }

        try {
            if (capturer instanceof UsbVideoCapturer) {
                return ((UsbVideoCapturer) capturer).adjustZoom(zoomDelta);
            }
        } catch (Throwable error) {
            Log.w(TAG, "Failed applying zoom on USB capturer", error);
            return false;
        }

        if (!(capturer instanceof CameraVideoCapturer)) {
            Log.d(TAG, "Zoom ignored: capturer does not support camera zoom API");
            return false;
        }

        // Jitsi WebRTC 124 camera capturers don't expose public zoom APIs.
        // Apply zoom against active Camera1/Camera2 session internals instead.
        return applyZoomViaCameraSession(capturer, zoomDelta);
    }

    private boolean applyZoomViaCameraSession(VideoCapturer capturer, float zoomDelta) {
        try {
            Class<?> cameraCapturerClass = Class.forName("org.webrtc.CameraCapturer");
            if (!cameraCapturerClass.isInstance(capturer)) {
                return false;
            }

            java.lang.reflect.Field cameraThreadHandlerField = cameraCapturerClass.getDeclaredField("cameraThreadHandler");
            cameraThreadHandlerField.setAccessible(true);
            Handler cameraThreadHandler = (Handler) cameraThreadHandlerField.get(capturer);
            if (cameraThreadHandler == null) {
                Log.d(TAG, "Zoom ignored: camera thread handler unavailable");
                return false;
            }

            java.lang.reflect.Field currentSessionField = cameraCapturerClass.getDeclaredField("currentSession");
            currentSessionField.setAccessible(true);
            Object session = currentSessionField.get(capturer);
            if (session == null) {
                Log.d(TAG, "Zoom ignored: no active camera session");
                return false;
            }

            final boolean[] success = new boolean[] { false };
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            cameraThreadHandler.post(() -> {
                try {
                    String sessionClass = session.getClass().getName();
                    if (sessionClass.endsWith("Camera1Session")) {
                        success[0] = applyCamera1SessionZoom(session, zoomDelta);
                    } else if (sessionClass.endsWith("Camera2Session")) {
                        success[0] = applyCamera2SessionZoom(session, zoomDelta);
                    } else {
                        Log.d(TAG, "Zoom ignored: unknown camera session " + sessionClass);
                    }
                } catch (Throwable error) {
                    Log.w(TAG, "Failed applying session zoom", error);
                } finally {
                    latch.countDown();
                }
            });

            latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            return success[0];
        } catch (Throwable error) {
            Log.w(TAG, "Failed applying zoom via camera session", error);
            return false;
        }
    }

    private boolean applyCamera1SessionZoom(Object session, float zoomDelta) {
        try {
            java.lang.reflect.Field cameraField = session.getClass().getDeclaredField("camera");
            cameraField.setAccessible(true);
            Camera camera = (Camera) cameraField.get(session);
            if (camera == null) {
                return false;
            }

            Camera.Parameters params = camera.getParameters();
            if (params == null || !params.isZoomSupported()) {
                return false;
            }

            int maxZoom = params.getMaxZoom();
            int currentZoom = params.getZoom();
            int targetZoom = Math.max(0, Math.min(maxZoom, currentZoom + Math.round(zoomDelta * maxZoom)));
            if (targetZoom == currentZoom) {
                return true;
            }

            params.setZoom(targetZoom);
            camera.setParameters(params);
            Log.d(TAG, "Applied Camera1 zoom current=" + currentZoom + " target=" + targetZoom + " max=" + maxZoom);
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "Failed applying Camera1 zoom", error);
            return false;
        }
    }

    private boolean applyCamera2SessionZoom(Object session, float zoomDelta) {
        try {
            java.lang.reflect.Field characteristicsField = session.getClass().getDeclaredField("cameraCharacteristics");
            characteristicsField.setAccessible(true);
            CameraCharacteristics characteristics = (CameraCharacteristics) characteristicsField.get(session);
            if (characteristics == null) {
                return false;
            }

            Float maxZoomObj = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            Rect activeRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (maxZoomObj == null || maxZoomObj <= 1.0f || activeRect == null) {
                return false;
            }

            float maxZoom = maxZoomObj;
            float targetZoom = Math.max(1.0f, Math.min(maxZoom, camera2ZoomRatio + (zoomDelta * (maxZoom - 1.0f))));
            if (Math.abs(targetZoom - camera2ZoomRatio) < 0.001f) {
                return true;
            }

            java.lang.reflect.Field cameraDeviceField = session.getClass().getDeclaredField("cameraDevice");
            cameraDeviceField.setAccessible(true);
            CameraDevice cameraDevice = (CameraDevice) cameraDeviceField.get(session);

            java.lang.reflect.Field captureSessionField = session.getClass().getDeclaredField("captureSession");
            captureSessionField.setAccessible(true);
            CameraCaptureSession captureSession = (CameraCaptureSession) captureSessionField.get(session);

            java.lang.reflect.Field surfaceField = session.getClass().getDeclaredField("surface");
            surfaceField.setAccessible(true);
            android.view.Surface surface = (android.view.Surface) surfaceField.get(session);

            if (cameraDevice == null || captureSession == null || surface == null) {
                return false;
            }

            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(surface);
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Prefer native zoom ratio when available.
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, targetZoom);
            } else {
                builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegionForZoom(activeRect, targetZoom));
            }

            captureSession.setRepeatingRequest(builder.build(), null, null);
            camera2ZoomRatio = targetZoom;
            Log.d(TAG, "Applied Camera2 zoom ratio=" + targetZoom + " max=" + maxZoom);
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "Failed applying Camera2 zoom", error);
            return false;
        }
    }

    private Rect cropRegionForZoom(Rect activeRect, float zoomRatio) {
        int centerX = activeRect.centerX();
        int centerY = activeRect.centerY();
        int deltaX = (int) (0.5f * activeRect.width() / zoomRatio);
        int deltaY = (int) (0.5f * activeRect.height() / zoomRatio);
        return new Rect(centerX - deltaX, centerY - deltaY, centerX + deltaX, centerY + deltaY);
    }
}
