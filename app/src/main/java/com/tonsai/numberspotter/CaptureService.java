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

    // Calibrated from the supplied Vivo screenshot. Values are proportions of the full captured screen.
    private static final float BOARD_LEFT = 0.035f;
    private static final float BOARD_TOP = 0.288f;
    private static final float BOARD_RIGHT = 0.965f;
    private static final float BOARD_BOTTOM = 0.865f;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread workerThread;
    private Handler worker;
    private TextRecognizer recognizer;

    private WindowManager windowManager;
    private SpotOverlay overlay;

    private int screenW;
    private int screenH;
    private boolean vibrateEnabled = true;
    private final AtomicBoolean ocrBusy = new AtomicBoolean(false);
    private long lastFrameAt = 0L;

    private int target = 1;
    private int currentCell = -1;
    private int boardReadyFrames = 0;
    private int changedFrames = 0;
    private boolean boardStarted = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        workerThread = new HandlerThread("NumberSpotterOCR");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        startForegroundCompat(buildNotification("กำลังเตรียมระบบตรวจเลข"));
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
            resetGameState();
            if (data != null) {
                beginProjection(resultCode, data);
            }
        }
        return START_NOT_STICKY;
    }

    private void resetGameState() {
        target = 1;
        currentCell = -1;
        boardReadyFrames = 0;
        changedFrames = 0;
        boardStarted = false;
        if (overlay != null) overlay.showWaiting("รอตาราง 5×5");
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

        addOverlay();

        imageReader = ImageReader.newInstance(
                screenW, screenH, PixelFormat.RGBA_8888, 2);

        virtualDisplay = projection.createVirtualDisplay(
                "NumberSpotterCapture",
                screenW,
                screenH,
                getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                worker
        );

        imageReader.setOnImageAvailableListener(reader -> {
            long now = SystemClock.uptimeMillis();
            if (now - lastFrameAt < 140 || ocrBusy.get()) {
                Image skip = reader.acquireLatestImage();
                if (skip != null) skip.close();
                return;
            }
            lastFrameAt = now;

            Image image = reader.acquireLatestImage();
            if (image == null) return;
            Bitmap bitmap = imageToBitmap(image);
            image.close();
            if (bitmap == null) return;
            processBitmap(bitmap);
        }, worker);

        updateNotification("กำลังรอตารางเกม");
    }

    private Bitmap imageToBitmap(Image image) {
        try {
            Image.Plane plane = image.getPlanes()[0];
            java.nio.ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * screenW;
            int paddedW = screenW + rowPadding / pixelStride;

            Bitmap padded = Bitmap.createBitmap(paddedW, screenH, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, screenW, screenH);
            if (cropped != padded) padded.recycle();
            return cropped;
        } catch (Throwable t) {
            return null;
        }
    }

    private void processBitmap(Bitmap bitmap) {
        if (!ocrBusy.compareAndSet(false, true)) {
            bitmap.recycle();
            return;
        }

        InputImage input = InputImage.fromBitmap(bitmap, 0);
        Task<Text> task = recognizer.process(input);
        task.addOnSuccessListener(result -> {
            try {
                Scan scan = makeScan(result);
                handleScan(scan, bitmap);
            } finally {
                bitmap.recycle();
                ocrBusy.set(false);
            }
        }).addOnFailureListener(e -> {
            bitmap.recycle();
            ocrBusy.set(false);
            if (overlay != null) overlay.showWaiting("OCR กำลังลองใหม่");
        });
    }

    private Scan makeScan(Text text) {
        Scan scan = new Scan();
        RectF board = boardRect();

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
                    if (value < 1 || value > 50) continue;

                    Rect box = element.getBoundingBox();
                    if (box == null) continue;
                    float cx = box.exactCenterX();
                    float cy = box.exactCenterY();
                    if (!board.contains(cx, cy)) continue;

                    int cell = pointToCell(cx, cy, board);
                    if (cell < 0 || cell >= 25) continue;

                    // Prefer the first clean numeric reading in each cell.
                    if (scan.cellValues[cell] == -1) {
                        scan.cellValues[cell] = value;
                        scan.recognizedCount++;
                    }
                }
            }
        }

        boolean[] seen = new boolean[51];
        for (int v : scan.cellValues) {
            if (v >= 1 && v <= 50) seen[v] = true;
        }
        for (int i = 1; i <= 50; i++) if (seen[i]) scan.distinctCount++;
        return scan;
    }

    private void handleScan(Scan scan, Bitmap bitmap) {
        // Countdown 1-2-3 is outside the board and cannot satisfy this readiness check.
        if (!boardStarted) {
            if (scan.recognizedCount >= 17 && scan.distinctCount >= 15) {
                boardReadyFrames++;
            } else {
                boardReadyFrames = 0;
            }

            if (boardReadyFrames < 3) {
                if (overlay != null) overlay.showWaiting("รอตารางจริง… " + boardReadyFrames + "/3");
                return;
            }

            boardStarted = true;
            target = 1;
            currentCell = -1;
            changedFrames = 0;
            vibrateOnce();
        }

        if (target > 50) {
            if (overlay != null) overlay.showFinished();
            updateNotification("ครบ 1–50 แล้ว");
            return;
        }

        if (currentCell < 0) {
            currentCell = findValue(scan, target);
            if (currentCell < 0 && target == 1) {
                currentCell = findOneByShape(bitmap);
            }

            if (currentCell >= 0) {
                changedFrames = 0;
                if (overlay != null) overlay.showTarget(target, currentCell);
                updateNotification("กำลังหาเลข " + target);
            } else {
                if (overlay != null) overlay.showWaiting("กำลังหาเลข " + target);
            }
            return;
        }

        // Keep the marker locked to the current target. Never jump ahead merely because OCR missed a frame.
        if (overlay != null) overlay.showTarget(target, currentCell);

        int currentValue = scan.cellValues[currentCell];
        boolean targetStillThere = currentValue == target;
        boolean nextExists = target == 50 || findValue(scan, target + 1) >= 0;

        if (!targetStillThere && nextExists && scan.recognizedCount >= 14) {
            changedFrames++;
        } else {
            changedFrames = 0;
        }

        if (changedFrames >= 2) {
            target++;
            currentCell = -1;
            changedFrames = 0;
            vibrateOnce();

            if (target <= 50) {
                int nextCell = findValue(scan, target);
                if (nextCell >= 0) {
                    currentCell = nextCell;
                    if (overlay != null) overlay.showTarget(target, currentCell);
                } else if (overlay != null) {
                    overlay.showWaiting("กำลังหาเลข " + target);
                }
            } else if (overlay != null) {
                overlay.showFinished();
            }
        }
    }

    private int findValue(Scan scan, int value) {
        for (int i = 0; i < 25; i++) {
            if (scan.cellValues[i] == value) return i;
        }
        return -1;
    }

    // Fallback used only for the initial 1. The handwritten-looking 1 is much narrower
    // than 7/11/etc. This prevents a duplicated OCR "7" from being selected as 1.
    private int findOneByShape(Bitmap bitmap) {
        RectF board = boardRect();
        float cw = board.width() / 5f;
        float ch = board.height() / 5f;
        int bestCell = -1;
        float bestRatio = Float.MAX_VALUE;

        for (int cell = 0; cell < 25; cell++) {
            int col = cell % 5;
            int row = cell / 5;
            int l = Math.max(0, Math.round(board.left + col * cw + cw * 0.22f));
            int r = Math.min(bitmap.getWidth(), Math.round(board.left + (col + 1) * cw - cw * 0.22f));
            int t = Math.max(0, Math.round(board.top + row * ch + ch * 0.22f));
            int b = Math.min(bitmap.getHeight(), Math.round(board.top + (row + 1) * ch - ch * 0.20f));
            if (r <= l || b <= t) continue;

            int minX = r, maxX = l, minY = b, maxY = t, dark = 0;
            for (int y = t; y < b; y += 2) {
                for (int x = l; x < r; x += 2) {
                    int c = bitmap.getPixel(x, y);
                    int gray = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100;
                    if (gray < 105) {
                        dark++;
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
            if (dark < 12 || maxY <= minY) continue;
            float ratio = (maxX - minX + 1f) / (maxY - minY + 1f);
            if (ratio < bestRatio) {
                bestRatio = ratio;
                bestCell = cell;
            }
        }

        return bestRatio < 0.34f ? bestCell : -1;
    }

    private RectF boardRect() {
        return new RectF(
                screenW * BOARD_LEFT,
                screenH * BOARD_TOP,
                screenW * BOARD_RIGHT,
                screenH * BOARD_BOTTOM
        );
    }

    private int pointToCell(float x, float y, RectF board) {
        int col = (int) ((x - board.left) / (board.width() / 5f));
        int row = (int) ((y - board.top) / (board.height() / 5f));
        if (col < 0 || col > 4 || row < 0 || row > 4) return -1;
        return row * 5 + col;
    }

    private void addOverlay() {
        if (!Settings.canDrawOverlays(this)) return;
        if (overlay != null) return;

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
        overlay.showWaiting("รอตาราง 5×5");
    }

    private void vibrateOnce() {
        if (!vibrateEnabled) return;
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(35);
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
        int recognizedCount = 0;
        int distinctCount = 0;

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
        private int targetNumber = 1;
        private String message = "รอตาราง 5×5";
        private boolean finished = false;

        SpotOverlay(Context context) {
            super(context);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(5));
            ring.setColor(Color.rgb(255, 88, 40));

            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Color.argb(32, 255, 88, 40));

            labelBg.setColor(Color.argb(215, 25, 25, 25));
            labelText.setColor(Color.WHITE);
            labelText.setTextSize(dp(17));
            labelText.setFakeBoldText(true);
        }

        void showTarget(int number, int cell) {
            post(() -> {
                finished = false;
                targetNumber = number;
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
            float chipRight = Math.min(getWidth() - dp(12), chipLeft + dp(210));
            float chipBottom = chipTop + dp(44);
            canvas.drawRoundRect(chipLeft, chipTop, chipRight, chipBottom, dp(18), dp(18), labelBg);
            canvas.drawText(message, chipLeft + dp(14), chipTop + dp(29), labelText);

            if (finished || targetCell < 0) return;

            RectF board = new RectF(
                    getWidth() * BOARD_LEFT,
                    getHeight() * BOARD_TOP,
                    getWidth() * BOARD_RIGHT,
                    getHeight() * BOARD_BOTTOM
            );
            float cw = board.width() / 5f;
            float ch = board.height() / 5f;
            int col = targetCell % 5;
            int row = targetCell / 5;

            float cx = board.left + (col + 0.5f) * cw;
            float cy = board.top + (row + 0.5f) * ch;
            float rx = cw * 0.43f;
            float ry = ch * 0.40f;

            RectF oval = new RectF(cx - rx, cy - ry, cx + rx, cy + ry);
            canvas.drawOval(oval, fill);
            canvas.drawOval(oval, ring);
        }

        private float dp(int value) {
            return value * getResources().getDisplayMetrics().density;
        }
    }
}
