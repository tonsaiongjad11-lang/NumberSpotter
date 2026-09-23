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
import android.graphics.Rect;
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

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public class CaptureService extends Service {
    public static final String ACTION_START = "com.tonsai.numberspotter.START";
    public static final String ACTION_STOP = "com.tonsai.numberspotter.STOP";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_VIBRATE = "vibrate";

    private static final String CHANNEL_ID = "number_spotter_capture";
    private static final int NOTIFICATION_ID = 4051;

    // Calibrated from the supplied Vivo V50 Lite screenshot.
    private static final float BOARD_LEFT = 0.035f;
    private static final float BOARD_TOP = 0.288f;
    private static final float BOARD_RIGHT = 0.965f;
    private static final float BOARD_BOTTOM = 0.865f;

    // Fast mode: capture at reduced resolution, around 20+ frames/sec.
    private static final int MAX_CAPTURE_WIDTH = 540;
    private static final long FRAME_INTERVAL_MS = 42;
    private static final long OCR_RETRY_MS = 95;
    private static final int CHANGE_CONFIRM_FRAMES = 2;
    private static final int BASELINE_STABLE_FRAMES = 2;
    private static final long TARGET_ARM_DELAY_MS = 70;
    // signatureDifference() returns percentage of meaningfully changed samples (0..100).
    private static final float CHANGE_THRESHOLD = 7.0f;
    private static final float BASELINE_STABLE_THRESHOLD = 2.5f;
    private static final int CHANGED_STABLE_FRAMES = 2;
    private static final float CHANGED_STABLE_THRESHOLD = 3.5f;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread workerThread;
    private Handler worker;
    private TextRecognizer recognizer;

    private WindowManager windowManager;
    private SpotOverlay overlay;
    private LinearLayout controlPanel;
    private volatile boolean paused = false;

    private int screenW;
    private int screenH;
    private int captureW;
    private int captureH;

    private boolean vibrateEnabled = true;
    private final AtomicBoolean ocrBusy = new AtomicBoolean(false);
    private long lastFrameAt = 0L;
    private long nextOcrAt = 0L;

    // Initial 1-25 board map. Index = number, value = cell 0..24.
    private final int[] initialPos = new int[26];
    private int mapCount = 0;

    private boolean boardStarted = false;
    private int target = 1;
    private int currentCell = -1;
    private int changedFrames = 0;
    private int baselineStableFrames = 0;
    private int[] baselineSignature = null;
    private int[] changedCandidateSignature = null;
    private int changedCandidateStableFrames = 0;
    private long armedAt = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        workerThread = new HandlerThread("NumberSpotterFast");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        startForegroundCompat(buildNotification("กำลังเตรียม Fast Mode"));
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
            resetGameState();

            if (data != null) beginProjection(resultCode, data);
        }

        return START_NOT_STICKY;
    }

    private void resetGameState() {
        Arrays.fill(initialPos, -1);
        mapCount = 0;
        boardStarted = false;
        target = 1;
        currentCell = -1;
        changedFrames = 0;
        baselineStableFrames = 0;
        baselineSignature = null;
        changedCandidateSignature = null;
        changedCandidateStableFrames = 0;
        armedAt = 0L;
        nextOcrAt = 0L;
        if (overlay != null) overlay.showWaiting("รอตาราง…");
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
                worker.post(CaptureService.this::stopEverything);
            }
        }, worker);

        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        android.view.Display display = wm.getDefaultDisplay();
        android.graphics.Point p = new android.graphics.Point();
        display.getRealSize(p);
        screenW = p.x;
        screenH = p.y;

        captureW = Math.min(screenW, MAX_CAPTURE_WIDTH);
        captureH = Math.max(1, Math.round(screenH * (captureW / (float) screenW)));

        addOverlay();
        addControlPanel();

        imageReader = ImageReader.newInstance(
                captureW, captureH, PixelFormat.RGBA_8888, 3);

        virtualDisplay = projection.createVirtualDisplay(
                "NumberSpotterFastCapture",
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

            if (paused) {
                frame.recycle();
                return;
            }

            // Once a target is known, advancing uses cheap pixel comparison instead of OCR.
            if (boardStarted && currentCell >= 0) {
                handleFastTargetFrame(frame, now);
            }

            boolean needOcr =
                    !boardStarted ||
                    mapCount < 25 ||
                    (target <= 25 && currentCell < 0);

            if (needOcr && now >= nextOcrAt && ocrBusy.compareAndSet(false, true)) {
                nextOcrAt = now + OCR_RETRY_MS;
                Bitmap board = cropBoard(frame);
                if (board != null) {
                    runInitialBoardOcr(board);
                } else {
                    ocrBusy.set(false);
                }
            }

            frame.recycle();
        }, worker);

        updateNotification("Fast Mode: รอตารางเกม");
    }

    private Bitmap imageToBitmap(Image image) {
        try {
            Image.Plane plane = image.getPlanes()[0];
            java.nio.ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * captureW;
            int paddedW = captureW + rowPadding / pixelStride;

            Bitmap padded = Bitmap.createBitmap(paddedW, captureH, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, captureW, captureH);
            if (cropped != padded) padded.recycle();
            return cropped;
        } catch (Throwable t) {
            return null;
        }
    }

    private Bitmap cropBoard(Bitmap frame) {
        try {
            RectF b = boardRect(frame.getWidth(), frame.getHeight());
            int l = Math.max(0, Math.round(b.left));
            int t = Math.max(0, Math.round(b.top));
            int r = Math.min(frame.getWidth(), Math.round(b.right));
            int bottom = Math.min(frame.getHeight(), Math.round(b.bottom));
            if (r <= l || bottom <= t) return null;
            return Bitmap.createBitmap(frame, l, t, r - l, bottom - t);
        } catch (Throwable t) {
            return null;
        }
    }

    private void runInitialBoardOcr(Bitmap boardBitmap) {
        int oneByShape = findOneByShape(boardBitmap);
        InputImage input = InputImage.fromBitmap(boardBitmap, 0);
        Task<Text> task = recognizer.process(input);

        task.addOnSuccessListener(result -> {
            Scan scan = makeBoardScan(result, boardBitmap.getWidth(), boardBitmap.getHeight());
            worker.post(() -> {
                try {
                    applyInitialScan(scan, oneByShape);
                } finally {
                    boardBitmap.recycle();
                    ocrBusy.set(false);
                }
            });
        }).addOnFailureListener(e -> worker.post(() -> {
            boardBitmap.recycle();
            ocrBusy.set(false);
        }));
    }

    private Scan makeBoardScan(Text text, int width, int height) {
        Scan scan = new Scan();

        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                for (Text.Element element : line.getElements()) {
                    String raw = element.getText().trim();
                    if (!raw.matches("\\d{1,2}")) continue;

                    int value;
                    try {
                        value = Integer.parseInt(raw);
                    } catch (NumberFormatException e) {
                        continue;
                    }

                    // Initial screen contains 1..25. Ignore later values while building the map.
                    if (value < 1 || value > 25) continue;

                    Rect box = element.getBoundingBox();
                    if (box == null) continue;
                    int cell = pointToCell(box.exactCenterX(), box.exactCenterY(), width, height);
                    if (cell < 0) continue;

                    if (scan.cellValues[cell] == -1) {
                        scan.cellValues[cell] = value;
                    }
                }
            }
        }
        return scan;
    }

    private void applyInitialScan(Scan scan, int oneByShape) {
        if (paused) return;
        for (int cell = 0; cell < 25; cell++) {
            int value = scan.cellValues[cell];
            if (value >= 1 && value <= 25 && initialPos[value] < 0) {
                initialPos[value] = cell;
            }
        }

        // For this font, 1 can be confused with 7. The shape detector is used as the authority for 1.
        if (oneByShape >= 0) {
            initialPos[1] = oneByShape;
        }

        recalcMapCount();
        inferSingleMissingPosition();

        // Start immediately from the first useful board read. No 3-frame waiting.
        if (!boardStarted && initialPos[1] >= 0 && mapCount >= 8) {
            boardStarted = true;
            target = 1;
            setCurrentCell(initialPos[1], null);
            vibrateOnce();
            updateNotification("Fast Mode: เลข 1");
            return;
        }

        if (!boardStarted) {
            if (overlay != null) overlay.showWaiting("กำลังอ่านตาราง…");
            return;
        }

        if (currentCell < 0) {
            resolveCurrentCell();
        }
    }

    private void recalcMapCount() {
        int count = 0;
        for (int i = 1; i <= 25; i++) if (initialPos[i] >= 0) count++;
        mapCount = count;
    }

    private void inferSingleMissingPosition() {
        if (mapCount != 24) return;

        int missingValue = -1;
        boolean[] usedCell = new boolean[25];
        for (int value = 1; value <= 25; value++) {
            int cell = initialPos[value];
            if (cell >= 0 && cell < 25) {
                usedCell[cell] = true;
            } else {
                missingValue = value;
            }
        }

        if (missingValue < 1) return;

        int missingCell = -1;
        for (int cell = 0; cell < 25; cell++) {
            if (!usedCell[cell]) {
                missingCell = cell;
                break;
            }
        }

        if (missingCell >= 0) {
            initialPos[missingValue] = missingCell;
            recalcMapCount();
        }
    }

    private void handleFastTargetFrame(Bitmap frame, long now) {
        int[] sig = cellSignature(frame, currentCell);
        if (sig == null) return;

        if (now < armedAt) {
            return;
        }

        if (baselineSignature == null) {
            baselineSignature = sig;
            baselineStableFrames = 1;
            changedFrames = 0;
            changedCandidateSignature = null;
            changedCandidateStableFrames = 0;
            return;
        }

        if (baselineStableFrames < BASELINE_STABLE_FRAMES) {
            float settleDiff = signatureDifference(baselineSignature, sig);
            if (settleDiff <= BASELINE_STABLE_THRESHOLD) {
                baselineStableFrames++;
            } else {
                baselineSignature = sig;
                baselineStableFrames = 1;
            }
            changedFrames = 0;
            changedCandidateSignature = null;
            changedCandidateStableFrames = 0;
            return;
        }

        float diff = signatureDifference(baselineSignature, sig);
        if (diff >= CHANGE_THRESHOLD) {
            changedFrames++;

            if (changedCandidateSignature == null) {
                changedCandidateSignature = sig;
                changedCandidateStableFrames = 1;
            } else {
                float changedSettle = signatureDifference(changedCandidateSignature, sig);
                if (changedSettle <= CHANGED_STABLE_THRESHOLD) {
                    changedCandidateStableFrames++;
                } else {
                    changedCandidateSignature = sig;
                    changedCandidateStableFrames = 1;
                }
            }
        } else {
            changedFrames = 0;
            changedCandidateSignature = null;
            changedCandidateStableFrames = 0;
        }

        // Advance only after the glyph in the target cell changed AND the new glyph
        // stayed stable for several frames. Short animations/flicker cannot skip a number.
        if (changedFrames >= CHANGE_CONFIRM_FRAMES &&
                changedCandidateStableFrames >= CHANGED_STABLE_FRAMES) {
            advanceTarget();
        }
    }

    private void advanceTarget() {
        target++;
        currentCell = -1;
        baselineSignature = null;
        baselineStableFrames = 0;
        changedFrames = 0;
        changedCandidateSignature = null;
        changedCandidateStableFrames = 0;
        vibrateOnce();

        if (target > 50) {
            if (overlay != null) overlay.showFinished();
            updateNotification("ครบ 1–50 แล้ว");
            return;
        }

        resolveCurrentCell();

        // Important: do NOT baseline from the previous frame. The marker is drawn first,
        // then a fresh baseline is collected so the overlay itself cannot trigger a false advance.
        baselineSignature = null;
        baselineStableFrames = 0;
        changedCandidateSignature = null;
        changedCandidateStableFrames = 0;
        armedAt = SystemClock.uptimeMillis() + TARGET_ARM_DELAY_MS;

        updateNotification("Fast Mode: เลข " + target);
    }

    private void resolveCurrentCell() {
        int cell = -1;

        if (target >= 1 && target <= 25) {
            cell = initialPos[target];
        } else if (target >= 26 && target <= 50) {
            // In this game, 26..50 appear in the same cells that previously held 1..25.
            cell = initialPos[target - 25];
        }

        if (cell >= 0) {
            setCurrentCell(cell, null);
        } else {
            currentCell = -1;
            baselineSignature = null;
            baselineStableFrames = 0;
            changedFrames = 0;
            changedCandidateSignature = null;
            changedCandidateStableFrames = 0;
            if (overlay != null) overlay.showWaiting("กำลังหาเลข " + target);
            nextOcrAt = 0L;
        }
    }

    private void setCurrentCell(int cell, @Nullable Bitmap frame) {
        currentCell = cell;
        changedFrames = 0;
        baselineStableFrames = 0;
        baselineSignature = null;
        changedCandidateSignature = null;
        changedCandidateStableFrames = 0;
        armedAt = SystemClock.uptimeMillis() + TARGET_ARM_DELAY_MS;
        if (overlay != null) overlay.showTarget(target, cell);
    }

    private int[] cellSignature(Bitmap frame, int cell) {
        if (cell < 0 || cell >= 25) return null;

        RectF b = boardRect(frame.getWidth(), frame.getHeight());
        float cw = b.width() / 5f;
        float ch = b.height() / 5f;
        int col = cell % 5;
        int row = cell / 5;

        float left = b.left + col * cw;
        float top = b.top + row * ch;

        // Dense sampling over the glyph area. The old 8x10 grid missed too much of thin digits
        // such as 1 and sometimes never noticed that a clicked tile had changed.
        int cols = 18;
        int rows = 22;
        int[] out = new int[cols * rows];
        int k = 0;

        for (int yy = 0; yy < rows; yy++) {
            float fy = (yy + 0.5f) / rows;
            int y = clamp(
                    Math.round(top + ch * (0.16f + fy * 0.68f)),
                    0,
                    frame.getHeight() - 1
            );

            for (int xx = 0; xx < cols; xx++) {
                float fx = (xx + 0.5f) / cols;
                int x = clamp(
                        Math.round(left + cw * (0.14f + fx * 0.72f)),
                        0,
                        frame.getWidth() - 1
                );

                int color = frame.getPixel(x, y);
                out[k++] = (Color.red(color) * 30
                        + Color.green(color) * 59
                        + Color.blue(color) * 11) / 100;
            }
        }

        return out;
    }

    private float signatureDifference(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) return 100f;

        int meaningfulChanges = 0;
        for (int i = 0; i < a.length; i++) {
            int ga = a[i];
            int gb = b[i];
            boolean darkA = ga < 145;
            boolean darkB = gb < 145;

            // Count either a foreground/background transition or a strong luminance change.
            if (darkA != darkB || Math.abs(ga - gb) >= 38) {
                meaningfulChanges++;
            }
        }

        return meaningfulChanges * 100f / a.length;
    }

    // Fallback for the handwritten-looking "1": narrow vertical mark and no wide top bar.
    private int findOneByShape(Bitmap board) {
        int bestCell = -1;
        float bestScore = Float.MAX_VALUE;
        float cw = board.getWidth() / 5f;
        float ch = board.getHeight() / 5f;

        for (int cell = 0; cell < 25; cell++) {
            int col = cell % 5;
            int row = cell / 5;

            int l = clamp(Math.round(col * cw + cw * 0.22f), 0, board.getWidth() - 1);
            int r = clamp(Math.round((col + 1) * cw - cw * 0.22f), l + 1, board.getWidth());
            int t = clamp(Math.round(row * ch + ch * 0.20f), 0, board.getHeight() - 1);
            int bot = clamp(Math.round((row + 1) * ch - ch * 0.18f), t + 1, board.getHeight());

            int minX = r, maxX = l, minY = bot, maxY = t, dark = 0;
            for (int y = t; y < bot; y += 2) {
                for (int x = l; x < r; x += 2) {
                    int c = board.getPixel(x, y);
                    int gray = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100;
                    if (gray < 110) {
                        dark++;
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }

            if (dark < 10 || maxY <= minY || maxX <= minX) continue;

            float width = maxX - minX + 1f;
            float height = maxY - minY + 1f;
            float ratio = width / height;
            if (ratio > 0.42f) continue;

            int topLimit = minY + Math.max(2, Math.round(height * 0.24f));
            int topMinX = r, topMaxX = l, topDark = 0;
            for (int y = minY; y <= topLimit && y < bot; y += 2) {
                for (int x = l; x < r; x += 2) {
                    int c = board.getPixel(x, y);
                    int gray = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100;
                    if (gray < 110) {
                        topDark++;
                        if (x < topMinX) topMinX = x;
                        if (x > topMaxX) topMaxX = x;
                    }
                }
            }

            float topSpan = topDark == 0 ? 0f : (topMaxX - topMinX + 1f) / width;
            float score = ratio + topSpan * 0.28f;

            if (topSpan < 0.78f && score < bestScore) {
                bestScore = score;
                bestCell = cell;
            }
        }

        return bestCell;
    }

    private RectF boardRect(int width, int height) {
        return new RectF(
                width * BOARD_LEFT,
                height * BOARD_TOP,
                width * BOARD_RIGHT,
                height * BOARD_BOTTOM
        );
    }

    private int pointToCell(float x, float y, int width, int height) {
        float cw = width / 5f;
        float ch = height / 5f;
        int col = (int) (x / cw);
        int row = (int) (y / ch);
        if (col < 0 || col > 4 || row < 0 || row > 4) return -1;
        return row * 5 + col;
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
        overlay.showWaiting("รอตาราง…");
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
        controlPanel.setBackgroundColor(Color.argb(205, 28, 28, 28));

        Button play = controlButton("▶");
        Button stop = controlButton("■");
        Button restart = controlButton("↻");
        Button close = controlButton("✕");

        play.setContentDescription("เล่นต่อ");
        stop.setContentDescription("หยุดชั่วคราว");
        restart.setContentDescription("เริ่มใหม่");
        close.setContentDescription("ปิดหน้าต่างลอย");

        play.setOnClickListener(v -> worker.post(this::resumeDetection));
        stop.setOnClickListener(v -> worker.post(this::pauseDetection));
        restart.setOnClickListener(v -> worker.post(this::restartDetection));
        close.setOnClickListener(v -> worker.post(this::stopEverything));

        controlPanel.addView(play);
        controlPanel.addView(stop);
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
        b.setPadding(dpInt(5), 0, dpInt(5), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dpInt(48), dpInt(46));
        lp.setMargins(dpInt(2), 0, dpInt(2), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void pauseDetection() {
        paused = true;
        changedFrames = 0;
        changedCandidateSignature = null;
        changedCandidateStableFrames = 0;
        if (overlay != null) overlay.showPaused(target, currentCell);
        updateNotification("หยุดชั่วคราว — เลข " + target);
    }

    private void resumeDetection() {
        paused = false;
        changedFrames = 0;
        baselineStableFrames = 0;
        baselineSignature = null;
        changedCandidateSignature = null;
        changedCandidateStableFrames = 0;
        armedAt = SystemClock.uptimeMillis() + TARGET_ARM_DELAY_MS;

        if (overlay != null) {
            if (currentCell >= 0) overlay.showTarget(target, currentCell);
            else overlay.showWaiting("กำลังหาเลข " + target);
        }
        updateNotification("เล่นต่อ — เลข " + target);
    }

    private void restartDetection() {
        paused = false;
        resetGameState();
        if (overlay != null) overlay.showWaiting("เริ่มใหม่ — กำลังหาเลข 1");
        updateNotification("เริ่มใหม่ — รอตาราง");
    }

    private int dpInt(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void vibrateOnce() {
        if (!vibrateEnabled) return;
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;

        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(28, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(28);
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

        if (recognizer != null) recognizer.close();
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
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private static class Scan {
        final int[] cellValues = new int[25];

        Scan() {
            Arrays.fill(cellValues, -1);
        }
    }

    private class SpotOverlay extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelText = new Paint(Paint.ANTI_ALIAS_FLAG);

        private int targetCell = -1;
        private String message = "รอตาราง…";
        private boolean finished = false;

        SpotOverlay(Context context) {
            super(context);

            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(5));
            ring.setColor(Color.rgb(255, 88, 40));

            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Color.TRANSPARENT);

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
                targetCell = -1;
                finished = false;
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
                targetCell = -1;
                finished = true;
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
            canvas.drawRoundRect(chipLeft, chipTop, chipRight, chipBottom, dp(16), dp(16), labelBg);
            canvas.drawText(message, chipLeft + dp(12), chipTop + dp(27), labelText);

            if (finished || targetCell < 0) return;

            RectF board = boardRect(getWidth(), getHeight());
            float cw = board.width() / 5f;
            float ch = board.height() / 5f;
            int col = targetCell % 5;
            int row = targetCell / 5;

            float cx = board.left + (col + 0.5f) * cw;
            float cy = board.top + (row + 0.5f) * ch;
            float rx = cw * 0.43f;
            float ry = ch * 0.40f;

            RectF oval = new RectF(cx - rx, cy - ry, cx + rx, cy + ry);
            canvas.drawOval(oval, ring);
        }

        private float dp(int value) {
            return value * getResources().getDisplayMetrics().density;
        }
    }
}
