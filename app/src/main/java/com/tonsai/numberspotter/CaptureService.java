package com.tonsai.numberspotter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class CaptureService extends Service {
    public static final String ACTION_START = "com.tonsai.numberspotter.START";
    public static final String ACTION_STOP = "com.tonsai.numberspotter.STOP";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_VIBRATE = "vibrate";

    private static final String CHANNEL_ID = "number_spotter_capture";
    private static final int NOTIFICATION_ID = 4051;

    // Grid measured directly from the supplied Vivo screenshot.
    // These are cell-center coordinates, not a guessed board rectangle.
    private static final float GRID_X0 = 0.12283550f;
    private static final float GRID_DX = 0.19250541f;
    private static final float GRID_Y0 = 0.33569336f;
    private static final float GRID_DY = 0.11541748f;

    private static final int CAPTURE_WIDTH = 540;
    private static final long FRAME_INTERVAL_MS = 30L;

    private static final int TEMPLATE_W = 32;
    private static final int TEMPLATE_H = 48;
    private static final int TEMPLATE_PIXELS = TEMPLATE_W * TEMPLATE_H;
    private static final int DARK_THRESHOLD = 165;

    // Search is intentionally strict: if the board is not visible yet (e.g. 1-2-3 countdown),
    // no circle appears. As soon as the board exists, matching starts immediately.
    private static final float FIND_MAX_DISTANCE = 0.50f;
    private static final float FIND_MIN_MARGIN = 0.035f;

    // Tap confirmation: the current glyph must actually become a different stable glyph.
    private static final int BASELINE_STABLE_FRAMES = 2;
    private static final int CHANGED_STABLE_FRAMES = 2;
    private static final float BASELINE_STABLE_DISTANCE = 0.12f;
    private static final float TAP_CHANGE_DISTANCE = 0.30f;
    private static final float CHANGED_STABLE_DISTANCE = 0.18f;
    private static final long ARM_DELAY_MS = 45L;

    private static final String[] TEMPLATE_HEX = new String[] {
            null,
            "000000000000000000000000000070000001F8000001F8000001F8000003F8000003F8000003F8000003F8000003F8000007F8000007F8000007F8000007F8000007F8000007F8000007F800000FF800000FF000000FF000000FF000000FF000000FF000000FF000001FF000001FE000001FE000001FE000001FE000001FE000001FE000001FE000001FE000001FC000001FC000003FC000003FC000003FC000003FC000003F8000003F8000003F8000001F0000000000000000000000000000",
            "00000000000000000000000000C0000003FE000007FFC00007FFE00007FFF00007FFF80003FFF80000FFFC00003FFE000007FE000000FE0000007E0000007E000000FE000000FE000000FE000001FC000001FC000001FC000003F8000007F800000FF000000FF000001FE000001FE000003FC000007F8000007F800001FF000001FE000003FC000007FC00000FF000000FF000001FFFFFF01FFFFFF81FFFFFF81FFFFFF80FFFFFF807FFFFF803FFFFF800FFFFE0000000000000000000000000",
            "00000000000000000000000000F8000001FF000001FFC00001FFF80001FFFE0000FFFF80007FFFC0000FFFE00001FFE00000FFE000001FE000000FE000001FE000001FE00000FFC00000FF800000FF000001FE000003FC000007F0000007E0000007E000000FF0000007FC000007FE000003FF000001FFC00000FFC000001FE000000FE0000007E0000007E0000007E000000FE000001FE000007FE0007FFFC003FFFF8003FFFF0007FFFC0007FFF00003FF8000000000000000000000000000000",
            "0000000000000000000000000000380000007C0003807E0007E0FE0007E0FE000FF0FE000FF0FF000FE0FE001FE0FE001FE0FE001FE0FE001FE0FE001FC0FF001FC0FE001FC0FE003FC0FE003FC0FE003FC0FE003FC0FF003FC0FF701FE0FFFC1FFFFFFC1FFFFFFC1FFFFFFC0FFFFFFC0FFFFFFC03FFFFF000FFFFC00000FF000000FE000000FE000000FE000000FE000000FE000000FE000000FE000000FE000000FE0000007E0000007E0000007E0000007E00000000000000000000000000",
            "00000000000000000000000000100000007C000000FE000000FE000001FE000001FE000001FE000001FC000003FC000003FFFE0003FFFFF003FFFFF807FFFFF807FFFFF807FFFFF80FF7FFF00FE000000FE000000FE800000FFF00000FFF80001FFFF8000FFFFC000FFFFE0003FFFF000007FF000000FF800000FF8000007F8000003FC000003FC000003FC000003FC000007FC00000FF80000FFF8003FFFF0007FFFE0007FFFC0007FFF80007FFE00007FF8000000000000000000000000000",
            "000000000000000000000000000080000003E0000007E0000007E000000FE000001FE000001FE000003FC000003FC000007F8000007F8000007F000000FF000000FF000001FE000001FE000003FC000003FC000003FC000007FFC00007FFFC0007FFFE0007FFFF800FFFFF800FFFFFC00FFFFFE00FFFFFF00FF01FF00FF00FF00FF00FF00FF007F00FF007F00FF007F00FF00FF00FF80FF007FC3FE007FFFFE003FFFFC003FFFFC001FFFF8000FFFE00007FF800000000000000000000000000",
            "00000000000000000000000000FC00000FFFFE001FFFFFE01FFFFFF01FFFFFF81FFFFFF81FFFFFF81FFFFFF8000007F8000003F8000003F8000003F8000003F8000003F8000003F8000007F8000007F0000007F0000007F0000007F000000FF000000FE000001FE000001FE000001FE000001FC000003FC000003FC000003FC000003F8000007F8000007F8000007F0000007F0000007F000000FF000000FE000000FE000000FE000000FC000000FC000000FC00000000000000000000000000",
            "0000000000000000000000000000C0000003FE000007FF80000FFFC0001FFFE0003FFFF0003FFFF0007FFFF0007F07F000FF03F000FE03F000FC07F001FC07F000FC07F000FC07E000FC0FE000FE1FC0007F3FC0007FFF80003FFF80001FFF00001FFC00003FFC00007FFC0000FFFE0001FFFF0003FE7F0007F83F8007F01F8007F01FC00FE00FC00FC00FC00F800FC00FC00FC00FC00FC00FC01FC00FFFFFC007FFFF8007FFFF8003FFFF0003FFFC0000FFF000000000000000000000000000",
            "0000000000000000000000000007F800001FFE00003FFF0000FFFF8001FFFF8003FFFFC003FFFFC007FFFFE007FC1FE00FF80FE00FF00FE00FF00FE00FF00FE00FF00FE00FF00FE00FF80FE007F80FE007FFFFE003FFFFE003FFFFE001FFFFE0007FFFE0001FFFE00007FFC000005FC000003FC000003F8000003F8000007F8000007F800000FF000000FF000000FE000001FE000001FC000003FC000003F8000007F8000007F0000007F0000007E0000007C000000000000000000000000000",
            "000000000000000000000000000000000000000000000000000000000000000001C0000003E0040003E01F8007E07FF007E0FFF007E1FFF007E3FFF807E3F9FC07C3F0FC07C7E07C07CFC07C07CFC07C07CF803C078F003C079F003C0F9F003C0F9F003C0F9F003C0F9F007C0FBF007C1F9F00FC1F9F00F81F9F01F81F1F01F01F1F03F03F0F03F03F0FCFF03F0FFFE03F07FFC03E03FF803E01FE001E00F8000000000000000000000000000000000000000000000000000000000000000000",
            "000000000000000000000000000F03E0001F03E0003F07E0003F07E0003F0FE0003F0FE0007F0FE0007F0FE0007F0FE0007F0FE0007F0FE0007F1FE000FF1FE000FF1FE000FF1FE000FF1FE000FF1FE000FF3FE000FF3FE001FF3FE001FF3FC001FE3FC001FE3FC001FE3FC001FE7FC001FE7F8003FE7F8003FC7F8003FC7F8003FC7F8003FC7F8003FC7F8003FC7F8003FC7F0003F87F0003F8FF0003F8FF0003F8FF0007F8FE0007F0FE0003F0FE0003F07C00000000000000000000000000",
            "00000000000000000000000000000000000000000000000000000000000000000187800003CFF00003EFF80007EFFE0007EFFF0007E3FF8007E0FFC007C00FC007C007C007C007C007C007C007C007C007800F8007801F800F801F000F803F000F807E000F80FE000F80FE001F89F8001F83F8001F07F0001F07E0003F0FE0003F0FC0003F1F80003F3FFFF83F3FFFFC3E1FFFFC3E1FFFFC3E0FFFFC1E03FFF00000000000000000000000000000000000000000000000000000000000000000",
            "00000000000000000000000000000000000000000000000001E1C00001F3F00003F7FC0003F7FF0003F3FFE003F1FFF003F0FFF803F007FC03F003FC03F000FC03E003FC07E007FC07E007F807E01FE007E03FE007C03FC00FC03F000FC07F000FC07F800FC07FC00FC03FF00FC03FF80FC00FF81F8003FC1F8001FC1F8000FC1F8000FC1F8000FC1F8003FC3F83FFF83F8FFFF03F8FFFE03F8FFF801F0FFF001F07F80000000000000000000000000000000000000000000000000000000000",
            "00000000000000000000000000000000000000000000000000000000000000000180000003C0030003E0070007EE0F8007FF0F8007FF0F8007FF0F8007DE0F8007DE0F8007FE0F8007FE0F8007BE0F8007BE0F800FBE0F800FBE0F800FBE0FFC0FBFFFFC0F9FFFFC1F9FFFFC1F87FFF01F81FFC01F000F801F000F803F000F803F000F803F000F803F000F803E000F803E000F803E0007803E000700000000000000000000000000000000000000000000000000000000000000000000000000",
            "000000000000000000000000000000000000000000000000000000000000800003E1C00003E3E00007E3E00007E3E00007E3E00007E7E00007E7FF8007E7FFFC07EFFFFC07EFFFFC07EFFFFC0FDF3FFC0FDF00700FDF00000FDFF0000FDFFC000FDFFF000F9FFF800F9FFF801F803FE01F801FE01F8007F01F0003F01F0003F01F0003F03F0003F03F0003F03F001FF03F07FFE03F0FFFC03F1FFF803F0FFE003E0FFC0000000000000000000000000000000000000000000000000000000000",
            "00000000000000000000000000000000000000000000000000E00E0001F01F0001F03F0003F03F0003F07F0003F0FE0003F0FE0003F1FC0003F1F80003F1F80003E3F80007E3F00007E3F00007E7E00007E7E00007EFF0000FCFFFC00FCFFFE00FCFFFF00FCFFFF00FCFFFFC0FCFC7FC0FCFD0FC1F8FC0FC1F8FC07C1F8FC0FC1F8FC0FC1F8FC0FC3F8FFFFC3F87FFF83F87FFF03F81FFE01F01FF801F003F0000000000000000000000000000000000000000000000000000000000",
            "000000000000000000000000000000000000000000000000000000000000000001C0000003E1E00003EFFFC007FFFFF807FFFFFC07FFFFFC07EFFFFC07E0007C07E0007C07E0007C07C0007C07C0007C07C000F807C000F8078000F8078001F80F8001F00F8001F00F8001F00F8001F00F8003F01F8003F01F8003E01F8007E01F0007C01F0007C03F0007C03F0007C03F0007C03F0007803E0007800E0007000000000000000000000000000000000000000000000000000000000000000000",
            "0000000000000000000000000000000000000000000000000000000000C0060001C01FC003E03FF003E0FFF803E0FFFC07E1FFFC07E1F87C07E1F07C07E3E07C07C3E07C07C3E07C07C3E0FC07C3F1F807C1FFF007C1FFF00FC1FFF00FC0FFC00FC0FF800FC1FFC00FC3FFE00FC7FFF01F8FE3F01F8FC1F01F9F81F01F9F01F01F9F00F81F9F00F81F9F01F01F1FFFF03F1FFFF03F0FFFF03F07FFC01F03FF800000000000000000000000000000000000000000000000000000000000000000",
            "0000000000000000000000000000000000000000000000000080060003C03F8003E0FFE003F1FFE003F7FFF803F7FFF803EFFFF803EFE1FC07FFC0FC07FF80FC07FF80FC07CF007C07CF007C07CF807C078F807C0787F0FC0F87FFFC0F87FFFC0F81FFFC0F80FFF80F801FF80F8000F81F8000F01F8001F01F8001F01F8003E01F8003E01F0007E01F0007E01F000FC03F000FC03F000F803F001F803E003F003E003F001E003E00000000000000000000000000000000000000000000000000",
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000040000000FC001C01FE007F00FF00FF803F81FFC00781F3C00383E3C00383C1C00787C1C0078781C00F0781C00F0781C01E0F01C03E0F01C07C0F03C0780F03C0F80707C1F0078781E0078F83FFF3FF03FFFBFE01FFF1FC007FE0F8000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
            "00000000000000000000000000000000000000000000000000000000000000000780003C07F0003C0FF8003C07FE007C07FF007C01FF80FC003FC0FC000FC0FC0007C0FC0007C0FC000FC0FC000FC0F8000F80F8001F80F8001F00F8007E00F8007E00F800FC00F001FC01F001F801F003F801F007E001F007E001F00FC003F01F8003F03FFFFFE03FFFFFE01FFFFFE01FFFFFE007FFFBC0007FE1C0000000000000000000000000000000000000000000000000000000000000000000000000",
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000040010001FC07F001FE07F800FF03FC003F80FE0007801E0007800E0007800E0007801E0007801E000F003C001F007C001E0078003C00F8007C01F000F803E000F003E001F807C003E0078003FFFFFFC3FFFFFFC1FFF7FFC07FE1FF800000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
            "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000006001C000F801E001FE03FC00FF83FF007F80FF8007C00FC003C003C003C007C007C00F8007C01F0007803E000F807C001F007C001E007E003E003F007C001F80780007C0F80003C1F00003C3F00007C3FFFDFF83FFFBFF01FFFFFE007FF3F00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000E0000401FC020E01FE071E007F079E001F871E00078F1E00078F1E00070F0E00070E0E000F0E0E000E0E0E001E0F0FC01C07FFC03C07FFC07801FE00F0000E01E0000E01E0000E03C0000E03FFF00E03FFF00E00FFE00E000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
            "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000E0018000F801C001FE03C000FF03C0003F83C0000783FF800387FFC00387FFC007873F8007070000070780000F07F0001E07FC001C03FE003C001E0078000E00F0000701E0000703E0000F03FFF03E03FFF3FE01FFF7F8003FE3E0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
    };

    private final byte[][] templates = new byte[26][];
    private final int[] positions = new int[26];

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread workerThread;
    private Handler worker;

    private WindowManager windowManager;
    private SpotOverlay overlay;
    private LinearLayout controlPanel;

    private int screenW;
    private int screenH;
    private int captureW;
    private int captureH;

    private volatile boolean paused = false;
    private boolean vibrateEnabled = true;

    private int target = 1;
    private int currentCell = -1;

    private byte[] baselineMask;
    private int baselineStableFrames = 0;
    private byte[] changedMask;
    private int changedStableFrames = 0;
    private long armedAt = 0L;
    private long lastFrameAt = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        Arrays.fill(positions, -1);
        decodeTemplates();
        createChannel();

        workerThread = new HandlerThread("NumberSpotterTemplateFast");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        startForegroundCompat(buildNotification("Fast Template Mode พร้อม"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopEverything();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
            Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            vibrateEnabled = intent.getBooleanExtra(EXTRA_VIBRATE, true);

            paused = false;
            resetGame();

            if (data != null) beginProjection(resultCode, data);
        }

        return START_NOT_STICKY;
    }

    private void decodeTemplates() {
        for (int number = 1; number <= 25; number++) {
            String hex = TEMPLATE_HEX[number];
            byte[] mask = new byte[TEMPLATE_PIXELS];
            int bitIndex = 0;

            for (int i = 0; i < hex.length(); i += 2) {
                int value = Integer.parseInt(hex.substring(i, i + 2), 16);
                for (int bit = 7; bit >= 0 && bitIndex < TEMPLATE_PIXELS; bit--) {
                    mask[bitIndex++] = (byte) ((value >> bit) & 1);
                }
            }
            templates[number] = mask;
        }
    }

    private void resetGame() {
        Arrays.fill(positions, -1);
        target = 1;
        currentCell = -1;
        clearTapState();
        lastFrameAt = 0L;

        if (overlay != null) overlay.showWaiting("หาเลข 1…");
        updateNotification("เริ่มใหม่ — หาเลข 1");
    }

    private void beginProjection(int resultCode, Intent data) {
        stopProjectionOnly();

        MediaProjectionManager mgr =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mgr.getMediaProjection(resultCode, data);
        if (projection == null) {
            stopSelf();
            return;
        }

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (worker != null) worker.post(CaptureService.this::stopEverything);
            }
        }, worker);

        WindowManager displayManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        android.view.Display display = displayManager.getDefaultDisplay();
        android.graphics.Point p = new android.graphics.Point();
        display.getRealSize(p);
        screenW = p.x;
        screenH = p.y;

        captureW = Math.min(screenW, CAPTURE_WIDTH);
        captureH = Math.max(1, Math.round(screenH * (captureW / (float) screenW)));

        addOverlay();
        addControlPanel();

        imageReader = ImageReader.newInstance(
                captureW,
                captureH,
                PixelFormat.RGBA_8888,
                3
        );

        virtualDisplay = projection.createVirtualDisplay(
                "NumberSpotterTemplateFast",
                captureW,
                captureH,
                getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                worker
        );

        imageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image == null) return;

            long now = SystemClock.uptimeMillis();
            if (now - lastFrameAt < FRAME_INTERVAL_MS) {
                image.close();
                return;
            }
            lastFrameAt = now;

            Bitmap frame = imageToBitmap(image);
            image.close();
            if (frame == null) return;

            try {
                if (!paused) processFrame(frame, now);
            } finally {
                frame.recycle();
            }
        }, worker);

        updateNotification("กำลังหาเลข 1");
    }

    private Bitmap imageToBitmap(Image image) {
        try {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();

            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * captureW;
            int paddedW = captureW + rowPadding / pixelStride;

            Bitmap padded = Bitmap.createBitmap(paddedW, captureH, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);

            Bitmap result = Bitmap.createBitmap(padded, 0, 0, captureW, captureH);
            if (result != padded) padded.recycle();
            return result;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void processFrame(Bitmap frame, long now) {
        if (target > 50) return;

        if (currentCell < 0) {
            if (target <= 25) {
                locateTarget(frame, target, now);
            } else {
                int source = target - 25;
                int cell = positions[source];
                if (cell >= 0) {
                    lockTarget(cell, now);
                } else {
                    if (overlay != null) overlay.showWaiting("หาเลข " + target + "…");
                }
            }
            return;
        }

        if (now < armedAt) return;

        byte[] mask = normalizeCell(frame, currentCell);
        if (mask == null) return;

        if (baselineMask == null) {
            baselineMask = mask;
            baselineStableFrames = 1;
            return;
        }

        if (baselineStableFrames < BASELINE_STABLE_FRAMES) {
            float d = maskDistance(baselineMask, mask);
            if (d <= BASELINE_STABLE_DISTANCE) {
                baselineStableFrames++;
            } else {
                baselineMask = mask;
                baselineStableFrames = 1;
            }
            changedMask = null;
            changedStableFrames = 0;
            return;
        }

        float fromBaseline = maskDistance(baselineMask, mask);

        if (fromBaseline >= TAP_CHANGE_DISTANCE) {
            if (changedMask == null) {
                changedMask = mask;
                changedStableFrames = 1;
            } else {
                float settle = maskDistance(changedMask, mask);
                if (settle <= CHANGED_STABLE_DISTANCE) {
                    changedStableFrames++;
                } else {
                    changedMask = mask;
                    changedStableFrames = 1;
                }
            }

            if (changedStableFrames >= CHANGED_STABLE_FRAMES) {
                advance(frame, now);
            }
        } else {
            changedMask = null;
            changedStableFrames = 0;
        }
    }

    private void locateTarget(Bitmap frame, int number, long now) {
        byte[] wanted = templates[number];
        float best = Float.MAX_VALUE;
        float second = Float.MAX_VALUE;
        int bestCell = -1;

        for (int cell = 0; cell < 25; cell++) {
            byte[] candidate = normalizeCell(frame, cell);
            if (candidate == null || foreground(candidate) < 20) continue;

            float d = maskDistance(wanted, candidate);
            if (d < best) {
                second = best;
                best = d;
                bestCell = cell;
            } else if (d < second) {
                second = d;
            }
        }

        boolean confident =
                bestCell >= 0 &&
                best <= FIND_MAX_DISTANCE &&
                (second == Float.MAX_VALUE || (second - best) >= FIND_MIN_MARGIN || best <= 0.28f);

        if (!confident) {
            if (overlay != null) overlay.showWaiting("หาเลข " + number + "…");
            return;
        }

        positions[number] = bestCell;
        lockTarget(bestCell, now);
    }

    private void lockTarget(int cell, long now) {
        currentCell = cell;
        clearTapState();
        armedAt = now + ARM_DELAY_MS;

        if (overlay != null) overlay.showTarget(target, cell);
        updateNotification("ต่อไป: " + target);
    }

    private void advance(Bitmap frame, long now) {
        target++;
        currentCell = -1;
        clearTapState();
        vibrateOnce();

        if (target > 50) {
            if (overlay != null) overlay.showFinished();
            updateNotification("ครบ 1–50 แล้ว");
            return;
        }

        // Resolve the next circle from the same frame, so there is almost no visible gap.
        if (target <= 25) {
            locateTarget(frame, target, now);
        } else {
            int cell = positions[target - 25];
            if (cell >= 0) lockTarget(cell, now);
            else if (overlay != null) overlay.showWaiting("หาเลข " + target + "…");
        }
    }

    private void clearTapState() {
        baselineMask = null;
        baselineStableFrames = 0;
        changedMask = null;
        changedStableFrames = 0;
    }

    private byte[] normalizeCell(Bitmap frame, int cell) {
        int col = cell % 5;
        int row = cell / 5;

        float cx = (GRID_X0 + col * GRID_DX) * frame.getWidth();
        float cy = (GRID_Y0 + row * GRID_DY) * frame.getHeight();

        float halfW = GRID_DX * frame.getWidth() * 0.34f;
        float halfH = GRID_DY * frame.getHeight() * 0.26f;

        int left = clamp(Math.round(cx - halfW), 0, frame.getWidth() - 1);
        int right = clamp(Math.round(cx + halfW), left + 1, frame.getWidth());
        int top = clamp(Math.round(cy - halfH), 0, frame.getHeight() - 1);
        int bottom = clamp(Math.round(cy + halfH), top + 1, frame.getHeight());

        int minX = right;
        int minY = bottom;
        int maxX = left - 1;
        int maxY = top - 1;
        int darkCount = 0;

        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                if (isDark(frame.getPixel(x, y))) {
                    darkCount++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }

        byte[] out = new byte[TEMPLATE_PIXELS];
        if (darkCount < 6 || maxX < minX || maxY < minY) return out;

        int glyphW = maxX - minX + 1;
        int glyphH = maxY - minY + 1;

        float scale = Math.min(28f / glyphW, 42f / glyphH);
        int outW = Math.max(1, Math.round(glyphW * scale));
        int outH = Math.max(1, Math.round(glyphH * scale));
        int offsetX = (TEMPLATE_W - outW) / 2;
        int offsetY = (TEMPLATE_H - outH) / 2;

        for (int oy = 0; oy < outH; oy++) {
            int sy = minY + Math.min(glyphH - 1, (int) ((oy + 0.5f) * glyphH / outH));
            for (int ox = 0; ox < outW; ox++) {
                int sx = minX + Math.min(glyphW - 1, (int) ((ox + 0.5f) * glyphW / outW));
                if (isDark(frame.getPixel(sx, sy))) {
                    out[(offsetY + oy) * TEMPLATE_W + offsetX + ox] = 1;
                }
            }
        }

        return out;
    }

    private boolean isDark(int color) {
        int gray = (Color.red(color) * 30
                + Color.green(color) * 59
                + Color.blue(color) * 11) / 100;
        return gray < DARK_THRESHOLD;
    }

    private int foreground(byte[] mask) {
        int n = 0;
        for (byte b : mask) if (b != 0) n++;
        return n;
    }

    // Jaccard/XOR distance. 0 = identical, 1 = completely different.
    private float maskDistance(byte[] a, byte[] b) {
        int union = 0;
        int xor = 0;

        for (int i = 0; i < TEMPLATE_PIXELS; i++) {
            boolean aa = a[i] != 0;
            boolean bb = b[i] != 0;
            if (aa || bb) union++;
            if (aa != bb) xor++;
        }

        if (union == 0) return 0f;
        return xor / (float) union;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void addOverlay() {
        if (!Settings.canDrawOverlays(this) || overlay != null) return;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlay = new SpotOverlay(this);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_SECURE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;

        windowManager.addView(overlay, lp);
        overlay.showWaiting("หาเลข 1…");
    }

    private void addControlPanel() {
        if (!Settings.canDrawOverlays(this) || controlPanel != null) return;
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }

        controlPanel = new LinearLayout(this);
        controlPanel.setOrientation(LinearLayout.HORIZONTAL);
        controlPanel.setGravity(Gravity.CENTER);
        controlPanel.setPadding(dpInt(4), dpInt(3), dpInt(4), dpInt(3));
        controlPanel.setBackgroundColor(Color.argb(210, 25, 25, 25));

        Button play = controlButton("▶");
        Button pause = controlButton("■");
        Button restart = controlButton("↻");
        Button close = controlButton("✕");

        play.setContentDescription("เล่นต่อ");
        pause.setContentDescription("หยุดชั่วคราว");
        restart.setContentDescription("เริ่มใหม่");
        close.setContentDescription("ปิดหน้าต่างลอย");

        play.setOnClickListener(v -> worker.post(this::resumeDetection));
        pause.setOnClickListener(v -> worker.post(this::pauseDetection));
        restart.setOnClickListener(v -> worker.post(this::restartDetection));
        close.setOnClickListener(v -> worker.post(this::stopEverything));

        controlPanel.addView(play);
        controlPanel.addView(pause);
        controlPanel.addView(restart);
        controlPanel.addView(close);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dpInt(8);
        lp.y = dpInt(135);

        windowManager.addView(controlPanel, lp);
    }

    private Button controlButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(17);
        b.setAllCaps(false);
        b.setMinWidth(dpInt(46));
        b.setMinimumWidth(dpInt(46));
        b.setMinHeight(dpInt(44));
        b.setMinimumHeight(dpInt(44));
        b.setPadding(dpInt(4), 0, dpInt(4), 0);

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(dpInt(48), dpInt(46));
        lp.setMargins(dpInt(2), 0, dpInt(2), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void pauseDetection() {
        paused = true;
        clearTapState();

        if (overlay != null) overlay.showPaused(target, currentCell);
        updateNotification("หยุดชั่วคราว — เลข " + target);
    }

    private void resumeDetection() {
        paused = false;
        clearTapState();
        armedAt = SystemClock.uptimeMillis() + ARM_DELAY_MS;

        if (overlay != null) {
            if (currentCell >= 0) overlay.showTarget(target, currentCell);
            else overlay.showWaiting("หาเลข " + target + "…");
        }
        updateNotification("เล่นต่อ — เลข " + target);
    }

    private void restartDetection() {
        paused = false;
        resetGame();
    }

    private int dpInt(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void vibrateOnce() {
        if (!vibrateEnabled) return;

        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;

        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(
                    VibrationEffect.createOneShot(24, VibrationEffect.DEFAULT_AMPLITUDE)
            );
        } else {
            vibrator.vibrate(24);
        }
    }

    private void stopProjectionOnly() {
        try {
            if (imageReader != null) {
                imageReader.setOnImageAvailableListener(null, null);
                imageReader.close();
            }
        } catch (Throwable ignored) {}
        imageReader = null;

        try {
            if (virtualDisplay != null) virtualDisplay.release();
        } catch (Throwable ignored) {}
        virtualDisplay = null;

        try {
            if (projection != null) projection.stop();
        } catch (Throwable ignored) {}
        projection = null;
    }

    private void stopEverything() {
        stopProjectionOnly();

        if (windowManager != null && overlay != null) {
            try { windowManager.removeView(overlay); } catch (Throwable ignored) {}
        }
        overlay = null;

        if (windowManager != null && controlPanel != null) {
            try { windowManager.removeView(controlPanel); } catch (Throwable ignored) {}
        }
        controlPanel = null;

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopProjectionOnly();

        if (windowManager != null && overlay != null) {
            try { windowManager.removeView(overlay); } catch (Throwable ignored) {}
        }
        overlay = null;

        if (windowManager != null && controlPanel != null) {
            try { windowManager.removeView(controlPanel); } catch (Throwable ignored) {}
        }
        controlPanel = null;

        if (workerThread != null) workerThread.quitSafely();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL_ID,
                    "Number Spotter",
                    NotificationManager.IMPORTANCE_LOW
            );
            c.setDescription("สถานะตรวจเลขบนหน้าจอ");
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Number Spotter 1–50")
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    NOTIFICATION_ID,
                    n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private class SpotOverlay extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelText = new Paint(Paint.ANTI_ALIAS_FLAG);

        private int targetCell = -1;
        private String message = "หาเลข 1…";
        private boolean finished = false;

        SpotOverlay(Context context) {
            super(context);

            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(5));
            ring.setColor(Color.rgb(255, 72, 35));

            labelBg.setColor(Color.argb(210, 25, 25, 25));
            labelText.setColor(Color.WHITE);
            labelText.setTextSize(dp(16));
            labelText.setFakeBoldText(true);
        }

        void showTarget(int number, int cell) {
            post(() -> {
                finished = false;
                targetCell = cell;
                message = "ต่อไป: " + number;
                invalidate();
            });
        }

        void showWaiting(String msg) {
            post(() -> {
                finished = false;
                targetCell = -1;
                message = msg;
                invalidate();
            });
        }

        void showPaused(int number, int cell) {
            post(() -> {
                finished = false;
                targetCell = cell;
                message = "หยุด • เลข " + number;
                invalidate();
            });
        }

        void showFinished() {
            post(() -> {
                finished = true;
                targetCell = -1;
                message = "ครบ 1–50 ✓";
                invalidate();
            });
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float chipLeft = dp(12);
            float chipTop = dp(78);
            float chipRight = Math.min(getWidth() - dp(12), chipLeft + dp(185));
            float chipBottom = chipTop + dp(40);

            canvas.drawRoundRect(
                    chipLeft, chipTop, chipRight, chipBottom,
                    dp(16), dp(16), labelBg
            );
            canvas.drawText(
                    message,
                    chipLeft + dp(12),
                    chipTop + dp(27),
                    labelText
            );

            if (finished || targetCell < 0) return;

            int col = targetCell % 5;
            int row = targetCell / 5;

            float cx = (GRID_X0 + col * GRID_DX) * getWidth();
            float cy = (GRID_Y0 + row * GRID_DY) * getHeight();

            float rx = GRID_DX * getWidth() * 0.43f;
            float ry = GRID_DY * getHeight() * 0.40f;

            RectF oval = new RectF(cx - rx, cy - ry, cx + rx, cy + ry);
            canvas.drawOval(oval, ring);
        }

        private float dp(int value) {
            return value * getResources().getDisplayMetrics().density;
        }
    }
}
