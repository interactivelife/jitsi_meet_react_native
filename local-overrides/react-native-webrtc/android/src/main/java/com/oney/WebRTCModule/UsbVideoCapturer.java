package com.oney.WebRTCModule;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.app.PendingIntent;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import org.webrtc.CapturerObserver;
import org.webrtc.NV21Buffer;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * USB UVC -> WebRTC capturer bridge using UVCCameraHelper API.
 * Uses reflection so react-native-webrtc has no compile-time dependency.
 */
public class UsbVideoCapturer implements VideoCapturer {
    private static final String TAG = UsbVideoCapturer.class.getSimpleName();
    private static final String USB_CAMERA_PREFS_NAME = "CIPHER_USB_CAMERA";
    private static final String USB_CAMERA_DEVICE_NAME_KEY = "usb_device_name";

    private final Context appContext;
    private Activity activity;

    private CapturerObserver capturerObserver;
    private HandlerThread captureThread;
    private Handler captureHandler;

    private volatile boolean started;
    private volatile int frameWidth = 640;
    private volatile int frameHeight = 480;
    private volatile boolean capturerStartedNotified;

    private Object cameraHelper;
    private Class<?> cameraHelperClass;
    private Object previewFrameListenerProxy;
    private Object deviceListenerProxy;
    private Object cameraViewProxy;
    private SurfaceTexture headlessSurfaceTexture;
    private Surface headlessSurface;
    private volatile int currentZoom = 0;
    private volatile int maxZoom = 0;

    public UsbVideoCapturer(Context context) {
        this.appContext = context.getApplicationContext();
        if (context instanceof Activity) {
            this.activity = (Activity) context;
        }
    }

    static boolean isSupported(Context context) {
        try {
            Class.forName("com.jiangdg.usbcamera.UVCCameraHelper");
            Class.forName("com.jiangdg.usbcamera.UVCCameraHelper$OnMyDevConnectListener");
            Class.forName("com.serenegiant.usb.widget.CameraViewInterface");
            Class.forName("com.serenegiant.usb.common.AbstractUVCCameraHandler$OnPreViewResultListener");
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "USB UVC helper is not supported on this runtime", error);
            return false;
        }
    }

    @Override
    public void initialize(
        SurfaceTextureHelper surfaceTextureHelper,
        Context context,
        CapturerObserver capturerObserver
    ) {
        this.capturerObserver = capturerObserver;
        if (context instanceof Activity) {
            this.activity = (Activity) context;
        }
    }

    @Override
    public void startCapture(int width, int height, int framerate) {
        frameWidth = width;
        frameHeight = height;

        if (started) {
            return;
        }
        started = true;
        capturerStartedNotified = false;

        captureThread = new HandlerThread("UsbVideoCapturerThread");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        captureHandler.post(this::startCaptureInternal);
    }

    @Override
    public void stopCapture() throws InterruptedException {
        if (!started) {
            return;
        }
        started = false;

        CountDownLatch latch = new CountDownLatch(1);
        Handler handler = captureHandler;
        if (handler != null) {
            handler.post(() -> {
                try {
                    stopCaptureInternal();
                } finally {
                    latch.countDown();
                }
            });
            latch.await(2, TimeUnit.SECONDS);
        }

        HandlerThread thread = captureThread;
        if (thread != null) {
            thread.quitSafely();
            thread.join(1000);
        }

        captureHandler = null;
        captureThread = null;
        cameraHelper = null;
        cameraHelperClass = null;
        previewFrameListenerProxy = null;
        deviceListenerProxy = null;
        cameraViewProxy = null;
    }

    @Override
    public void changeCaptureFormat(int width, int height, int framerate) {
        frameWidth = width;
        frameHeight = height;
    }

    @Override
    public void dispose() {
        try {
            stopCapture();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isScreencast() {
        return false;
    }

    private void startCaptureInternal() {
        boolean initialized = false;
        try {
            if (activity == null) {
                Log.w(TAG, "Cannot start USB UVC capture: Activity context is required");
                notifyStarted(false);
                return;
            }

            cameraHelperClass = Class.forName("com.jiangdg.usbcamera.UVCCameraHelper");
            Method getInstanceMethod = cameraHelperClass.getMethod("getInstance");
            cameraHelper = getInstanceMethod.invoke(null);
            if (cameraHelper == null) {
                Log.w(TAG, "UVCCameraHelper.getInstance() returned null");
                notifyStarted(false);
                return;
            }

            invokeOptional(cameraHelperClass, cameraHelper, "setDefaultPreviewSize", frameWidth, frameHeight);

            cameraViewProxy = createHeadlessCameraViewProxy();
            previewFrameListenerProxy = createPreviewFrameListenerProxy();
            deviceListenerProxy = createDeviceConnectListenerProxy();

            Class<?> cameraViewInterfaceClass = Class.forName("com.serenegiant.usb.widget.CameraViewInterface");
            Class<?> devConnectListenerClass =
                Class.forName("com.jiangdg.usbcamera.UVCCameraHelper$OnMyDevConnectListener");
            Class<?> previewResultListenerClass =
                Class.forName("com.serenegiant.usb.common.AbstractUVCCameraHandler$OnPreViewResultListener");

            Method initUSBMonitorMethod = cameraHelperClass.getMethod(
                "initUSBMonitor",
                Activity.class,
                cameraViewInterfaceClass,
                devConnectListenerClass
            );
            initUSBMonitorMethod.invoke(cameraHelper, activity, cameraViewProxy, deviceListenerProxy);

            Method setPreviewListenerMethod =
                cameraHelperClass.getMethod("setOnPreviewFrameListener", previewResultListenerClass);
            setPreviewListenerMethod.invoke(cameraHelper, previewFrameListenerProxy);

            // Do not call UVCCameraHelper.registerUSB() because older USBMonitor
            // inside androidusbcamera uses PendingIntent flags that crash on S+.
            // Instead, prime USBMonitor with a safe PendingIntent so requestPermission
            // can proceed and immediately processConnect when permission is already granted.
            prepareUsbMonitorForPermissionFlow();

            requestPreferredDevicePermission();
            initialized = true;
            Log.d(TAG, "USB UVC helper initialized, waiting for device connection/frames");
        } catch (Throwable error) {
            Log.e(TAG, "Failed to start USB UVC capture", error);
            notifyStarted(false);
        } finally {
            if (!initialized) {
                stopCaptureInternal();
            }
        }
    }

    private void requestPreferredDevicePermission() {
        if (cameraHelper == null || cameraHelperClass == null) {
            return;
        }

        try {
            Method getUsbDeviceList = cameraHelperClass.getMethod("getUsbDeviceList");
            Object listObj = getUsbDeviceList.invoke(cameraHelper);
            if (!(listObj instanceof List)) {
                Log.w(TAG, "UVCCameraHelper did not return a USB device list");
                notifyStarted(false);
                return;
            }

            @SuppressWarnings("unchecked")
            List<Object> devices = (List<Object>) listObj;
            if (devices.isEmpty()) {
                Log.w(TAG, "No USB UVC devices available");
                notifyStarted(false);
                return;
            }

            int preferredIndex = resolvePreferredDeviceIndex(devices);
            Method requestPermission = cameraHelperClass.getMethod("requestPermission", int.class);
            requestPermission.invoke(cameraHelper, preferredIndex);
            Log.d(TAG, "Requested USB permission for index=" + preferredIndex + " size=" + devices.size());
        } catch (Throwable error) {
            Log.e(TAG, "Failed requesting USB permission", error);
            notifyStarted(false);
        }
    }

    private int resolvePreferredDeviceIndex(List<Object> devices) {
        String preferredDeviceName = readPreferredDeviceName();
        if (preferredDeviceName == null || preferredDeviceName.isEmpty()) {
            return 0;
        }

        try {
            Method getDeviceName = UsbDevice.class.getMethod("getDeviceName");
            for (int i = 0; i < devices.size(); i++) {
                Object name = getDeviceName.invoke(devices.get(i));
                if (preferredDeviceName.equals(name)) {
                    return i;
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "Unable to match preferred USB device name", error);
        }
        return 0;
    }

    private void prepareUsbMonitorForPermissionFlow() {
        if (cameraHelper == null || cameraHelperClass == null) {
            return;
        }
        try {
            Method getMonitorMethod = cameraHelperClass.getMethod("getUSBMonitor");
            Object usbMonitor = getMonitorMethod.invoke(cameraHelper);
            if (usbMonitor == null) {
                return;
            }

            Class<?> monitorClass = usbMonitor.getClass();
            java.lang.reflect.Field permissionIntentField = monitorClass.getDeclaredField("mPermissionIntent");
            permissionIntentField.setAccessible(true);
            if (permissionIntentField.get(usbMonitor) != null) {
                return;
            }

            String action = appContext.getPackageName() + ".USB_PERMISSION";
            try {
                java.lang.reflect.Field actionField = monitorClass.getDeclaredField("ACTION_USB_PERMISSION");
                actionField.setAccessible(true);
                Object actionHolder = Modifier.isStatic(actionField.getModifiers()) ? null : usbMonitor;
                Object actionValue = actionField.get(actionHolder);
                if (actionValue instanceof String && !((String) actionValue).isEmpty()) {
                    action = (String) actionValue;
                }
            } catch (Throwable ignored) {
                // fallback action is fine
            }

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }

            PendingIntent pi = PendingIntent.getBroadcast(
                appContext,
                0,
                new android.content.Intent(action).setPackage(appContext.getPackageName()),
                flags
            );
            permissionIntentField.set(usbMonitor, pi);
            Log.d(TAG, "Prepared USBMonitor permission flow with safe PendingIntent flags");
        } catch (Throwable error) {
            Log.w(TAG, "Failed preparing USBMonitor permission flow", error);
        }
    }

    private Object createDeviceConnectListenerProxy() throws Exception {
        Class<?> listenerInterface = Class.forName("com.jiangdg.usbcamera.UVCCameraHelper$OnMyDevConnectListener");
        InvocationHandler handler = (proxy, method, args) -> {
            if (!started || method == null) {
                return null;
            }

            String methodName = method.getName();
            if ("onAttachDev".equals(methodName)) {
                requestPreferredDevicePermission();
            } else if ("onConnectDev".equals(methodName)) {
                boolean connected = false;
                if (args != null && args.length > 1 && args[1] instanceof Boolean) {
                    connected = (Boolean) args[1];
                }
                if (connected) {
                    updatePreviewSizeFromHelper();
                    updateZoomBoundsFromHelper();
                    notifyStarted(true);
                    Log.d(TAG, "USB device connected for capture");
                }
            } else if ("onDisConnectDev".equals(methodName) || "onDettachDev".equals(methodName)) {
                Log.d(TAG, "USB device disconnected");
            }
            return null;
        };

        return Proxy.newProxyInstance(
            listenerInterface.getClassLoader(),
            new Class<?>[] { listenerInterface },
            handler
        );
    }

    private Object createPreviewFrameListenerProxy() throws Exception {
        Class<?> previewListener =
            Class.forName("com.serenegiant.usb.common.AbstractUVCCameraHandler$OnPreViewResultListener");

        InvocationHandler handler = (proxy, method, args) -> {
            if (!started || capturerObserver == null || method == null) {
                return null;
            }
            if (!"onPreviewResult".equals(method.getName()) || args == null || args.length == 0) {
                return null;
            }

            Object frameObj = args[0];
            if (!(frameObj instanceof byte[])) {
                return null;
            }

            byte[] frame = (byte[]) frameObj;
            int expected = frameWidth * frameHeight * 3 / 2;
            if (expected <= 0 || frame.length < expected) {
                return null;
            }

            if (!capturerStartedNotified) {
                notifyStarted(true);
            }

            VideoFrame.Buffer buffer = new NV21Buffer(frame, frameWidth, frameHeight, null);
            VideoFrame videoFrame = new VideoFrame(buffer, 0, System.nanoTime());
            capturerObserver.onFrameCaptured(videoFrame);
            videoFrame.release();
            return null;
        };

        return Proxy.newProxyInstance(
            previewListener.getClassLoader(),
            new Class<?>[] { previewListener },
            handler
        );
    }

    private Object createHeadlessCameraViewProxy() throws Exception {
        headlessSurfaceTexture = new SurfaceTexture(10);
        headlessSurfaceTexture.setDefaultBufferSize(frameWidth, frameHeight);
        headlessSurface = new Surface(headlessSurfaceTexture);

        Class<?> cameraViewInterface = Class.forName("com.serenegiant.usb.widget.CameraViewInterface");
        InvocationHandler handler = (proxy, method, args) -> {
            if (method == null) {
                return null;
            }

            String name = method.getName();
            switch (name) {
                case "onPause":
                case "onResume":
                case "setCallback":
                case "setVideoEncoder":
                case "setAspectRatio":
                    return null;
                case "getSurfaceTexture":
                    return headlessSurfaceTexture;
                case "getSurface":
                    return headlessSurface;
                case "hasSurface":
                    return true;
                case "captureStillImage":
                    return null;
                case "getAspectRatio":
                    return frameHeight == 0 ? 1.0d : (double) frameWidth / (double) frameHeight;
                default:
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    if (method.getReturnType() == double.class) {
                        return 0.0d;
                    }
                    return null;
            }
        };

        return Proxy.newProxyInstance(
            cameraViewInterface.getClassLoader(),
            new Class<?>[] { cameraViewInterface },
            handler
        );
    }

    private void updatePreviewSizeFromHelper() {
        try {
            if (cameraHelper == null || cameraHelperClass == null) {
                return;
            }
            Method widthMethod = cameraHelperClass.getMethod("getPreviewWidth");
            Method heightMethod = cameraHelperClass.getMethod("getPreviewHeight");
            Object widthObj = widthMethod.invoke(cameraHelper);
            Object heightObj = heightMethod.invoke(cameraHelper);
            if (widthObj instanceof Integer && heightObj instanceof Integer) {
                int width = (Integer) widthObj;
                int height = (Integer) heightObj;
                if (width > 0 && height > 0) {
                    frameWidth = width;
                    frameHeight = height;
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "Unable to read UVC preview size", error);
        }
    }

    public boolean adjustZoom(float zoomDelta) {
        if (cameraHelper == null || cameraHelperClass == null) {
            Log.d(TAG, "Zoom ignored: USB camera helper not initialized");
            return false;
        }

        int localMaxZoom = maxZoom;
        if (localMaxZoom <= 0) {
            updateZoomBoundsFromHelper();
            localMaxZoom = maxZoom;
        }
        if (localMaxZoom <= 0) {
            Log.d(TAG, "USB zoom not supported by current helper implementation");
            return false;
        }

        int targetZoom = Math.max(0, Math.min(localMaxZoom, currentZoom + Math.round(zoomDelta * localMaxZoom)));
        if (targetZoom == currentZoom) {
            return true;
        }

        if (setZoomViaHelper(targetZoom)) {
            currentZoom = targetZoom;
            Log.d(TAG, "Applied USB camera zoom current=" + currentZoom + " max=" + localMaxZoom);
            return true;
        }

        return false;
    }

    private void updateZoomBoundsFromHelper() {
        int discoveredMaxZoom = 0;
        int discoveredCurrentZoom = currentZoom;

        if (cameraHelper == null || cameraHelperClass == null) {
            return;
        }

        try {
            Method getMaxZoom = cameraHelperClass.getMethod("getMaxZoom");
            Object maxObj = getMaxZoom.invoke(cameraHelper);
            if (maxObj instanceof Integer) {
                discoveredMaxZoom = (Integer) maxObj;
            }
        } catch (Throwable ignored) {
            // best-effort helper compatibility
        }

        try {
            Method getZoom = cameraHelperClass.getMethod("getZoom");
            Object zoomObj = getZoom.invoke(cameraHelper);
            if (zoomObj instanceof Integer) {
                discoveredCurrentZoom = (Integer) zoomObj;
            }
        } catch (Throwable ignored) {
            // best-effort helper compatibility
        }

        maxZoom = Math.max(discoveredMaxZoom, 0);
        currentZoom = Math.max(Math.min(discoveredCurrentZoom, maxZoom), 0);
    }

    private boolean setZoomViaHelper(int targetZoom) {
        if (cameraHelper == null || cameraHelperClass == null) {
            return false;
        }

        try {
            Method setZoom = cameraHelperClass.getMethod("setZoom", int.class);
            setZoom.invoke(cameraHelper, targetZoom);
            return true;
        } catch (Throwable ignored) {
            // best-effort helper compatibility
        }

        // Common UVC helper fallback API shape:
        // setModelValue(flag, value)
        try {
            Method setModelValue = cameraHelperClass.getMethod("setModelValue", int.class, int.class);
            final int PU_ZOOM_ABS = 11;
            setModelValue.invoke(cameraHelper, PU_ZOOM_ABS, targetZoom);
            return true;
        } catch (Throwable ignored) {
            // best-effort helper compatibility
        }

        return false;
    }

    private void stopCaptureInternal() {
        if (cameraHelper != null && cameraHelperClass != null) {
            invokeOptional(cameraHelperClass, cameraHelper, "stopPreview");
            invokeOptional(cameraHelperClass, cameraHelper, "closeCamera");
            invokeOptional(cameraHelperClass, cameraHelper, "unregisterUSB");
            invokeOptional(cameraHelperClass, cameraHelper, "release");
        }

        if (headlessSurface != null) {
            headlessSurface.release();
            headlessSurface = null;
        }
        if (headlessSurfaceTexture != null) {
            headlessSurfaceTexture.release();
            headlessSurfaceTexture = null;
        }

        notifyStopped();
        currentZoom = 0;
        maxZoom = 0;
        Log.d(TAG, "USB UVC capture stopped");
    }

    private String readPreferredDeviceName() {
        try {
            SharedPreferences prefs =
                appContext.getSharedPreferences(USB_CAMERA_PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getString(USB_CAMERA_DEVICE_NAME_KEY, null);
        } catch (Throwable error) {
            Log.w(TAG, "Failed reading preferred USB device name", error);
            return null;
        }
    }

    private void invokeOptional(Class<?> cls, Object target, String methodName, Object... args) {
        try {
            Method method;
            if (args == null || args.length == 0) {
                method = cls.getMethod(methodName);
                method.invoke(target);
                return;
            }

            Class<?>[] parameterTypes = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg instanceof Integer) {
                    parameterTypes[i] = int.class;
                } else if (arg instanceof Boolean) {
                    parameterTypes[i] = boolean.class;
                } else {
                    parameterTypes[i] = arg.getClass();
                }
            }

            method = cls.getMethod(methodName, parameterTypes);
            method.invoke(target, args);
        } catch (Throwable ignored) {
            // best-effort invocation/cleanup
        }
    }

    private void notifyStarted(boolean success) {
        if (capturerObserver != null) {
            capturerObserver.onCapturerStarted(success);
            if (success) {
                capturerStartedNotified = true;
            }
        }
    }

    private void notifyStopped() {
        if (capturerObserver != null) {
            capturerObserver.onCapturerStopped();
        }
    }
}
