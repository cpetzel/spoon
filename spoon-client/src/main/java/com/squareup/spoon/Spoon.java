package com.squareup.spoon;

import static android.content.Context.MODE_WORLD_READABLE;
import static android.graphics.Bitmap.CompressFormat.PNG;
import static android.graphics.Bitmap.Config.ARGB_8888;
import static com.squareup.spoon.Chmod.chmodPlusR;
import static com.squareup.spoon.Chmod.chmodPlusRWX;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;

/** Utility class for capturing screenshots for Spoon. */
public final class Spoon {
    static final String SPOON_SCREENSHOTS = "spoon-screenshots";
    static final String APP_DATA = "data";

    static final String NAME_SEPARATOR = "_";
    static final String TEST_CASE_CLASS = "android.test.InstrumentationTestCase";
    static final String TEST_CASE_METHOD = "runMethod";
    private static final String EXTENSION = ".png";
    private static final String TAG = "Spoon";
    private static final Object LOCK = new Object();
    private static final Pattern TAG_VALIDATION = Pattern.compile("[a-zA-Z0-9_-]+");

    public static final String APP_DATA_FILENAME = "app_data.dat";

    public static Set<String> clearedDirs = new HashSet<String>();

    /**
     * This should create a file on disk that contains data about the app under test - split test
     * data - server data - user data
     * 
     * @param data
     */
    public static File dumpAppData(Activity a, String data) {

        try {
            File appDataDirectory = obtainDirectory(APP_DATA, a);
            File appDataFile = new File(appDataDirectory, APP_DATA_FILENAME);
            // write the data to file
            writeDataToFile(data, appDataFile);
            Log.d(TAG, "wrote data file to " + appDataDirectory);
            return appDataFile;
        } catch (Exception e) {
            throw new RuntimeException("Unable to capture screenshot.", e);
        }

    }

    private static void writeDataToFile(String data, File fileOut) {

        OutputStream fos = null;
        try {
            fos = new BufferedOutputStream(new FileOutputStream(fileOut));

            chmodPlusR(fileOut);

            /*
             * This logic will check whether the file exists or not. If the file is not found at the
             * specified location it would create a new file
             */
            if (!fileOut.exists()) {
                fileOut.createNewFile();
            }

            /*
             * String content cannot be directly written into a file. It needs to be converted into
             * bytes
             */
            byte[] bytesArray = data.getBytes();

            fos.write(bytesArray);
            fos.flush();
            System.out.println("App Data File Written Successfully");
            chmodPlusR(fileOut);

        } catch (Exception e) {
            throw new RuntimeException("Unable to write the app data to " + fileOut, e);
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }

    }

    // same as screenshots, but with different ir
    private static File obtainDirectory(String parentPath, Context context) throws IllegalAccessException {
        File dir = context.getDir(parentPath, MODE_WORLD_READABLE);
        Log.d(TAG, "obtaining directory = " + dir);

        synchronized (LOCK) {

            if (!clearedDirs.contains(parentPath)) {
                deletePath(dir, false);
                Log.d(TAG, "DELETINGe = " + dir);
                clearedDirs.add(parentPath);
            }
        }

        StackTraceElement testClass = findTestClassTraceElement(Thread.currentThread().getStackTrace());
        String className = testClass.getClassName().replaceAll("[^A-Za-z0-9._-]", "_");
        File dirClass = new File(dir, className);
        File dirMethod = new File(dirClass, testClass.getMethodName());
        createDir(dirMethod);
        return dirMethod;
    }

    /**
     * Take a screenshot with the specified tag.
     * 
     * @param activity
     *            Activity with which to capture a screenshot.
     * @param tag
     *            Unique tag to further identify the screenshot. Must match [a-zA-Z0-9_-]+.
     * @return the image file that was created
     */
    public static File screenshot(Activity activity, String tag) {
        if (!TAG_VALIDATION.matcher(tag).matches()) { throw new IllegalArgumentException("Tag must match " + TAG_VALIDATION.pattern() + "."); }
        try {
            File screenshotDirectory = obtainDirectory(SPOON_SCREENSHOTS, activity);
            Log.d(TAG, "screenshots dir = " + screenshotDirectory);
            String screenshotName = System.currentTimeMillis() + NAME_SEPARATOR + tag + EXTENSION;
            File screenshotFile = new File(screenshotDirectory, screenshotName);
            takeScreenshot(screenshotFile, activity);
            Log.d(TAG, "Captured screenshot '" + tag + "'.");
            return screenshotFile;
        } catch (Exception e) {
            throw new RuntimeException("Unable to capture screenshot.", e);
        }
    }

    private static void takeScreenshot(File file, final Activity activity) throws IOException {
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        final Bitmap bitmap = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, ARGB_8888);

        if (Looper.myLooper() == Looper.getMainLooper()) {
            // On main thread already, Just Do It™.
            drawDecorViewToBitmap(activity, bitmap);
        } else {
            // On a background thread, post to main.
            final CountDownLatch latch = new CountDownLatch(1);
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        drawDecorViewToBitmap(activity, bitmap);
                    } finally {
                        latch.countDown();
                    }
                }
            });
            try {
                latch.await();
            } catch (InterruptedException e) {
                String msg = "Unable to get screenshot " + file.getAbsolutePath();
                Log.e(TAG, msg, e);
                throw new RuntimeException(msg, e);
            }
        }

        OutputStream fos = null;
        try {
            fos = new BufferedOutputStream(new FileOutputStream(file));
            bitmap.compress(PNG, 100 /* quality */, fos);

            chmodPlusR(file);
        } finally {
            bitmap.recycle();
            if (fos != null) {
                fos.close();
            }
        }
    }

    private static void drawDecorViewToBitmap(Activity activity, Bitmap bitmap) {
        Canvas canvas = new Canvas(bitmap);
        activity.getWindow().getDecorView().draw(canvas);
    }

    /** Returns the test class element by looking at the method InstrumentationTestCase invokes. */
    static StackTraceElement findTestClassTraceElement(StackTraceElement[] trace) {
        for (int i = trace.length - 1; i >= 0; i--) {
            StackTraceElement element = trace[i];
            if (TEST_CASE_CLASS.equals(element.getClassName()) //
                && TEST_CASE_METHOD.equals(element.getMethodName())) { return trace[i - 3]; }
        }

        throw new IllegalArgumentException("Could not find test class!");
    }

    private static void createDir(File dir) throws IllegalAccessException {
        File parent = dir.getParentFile();
        if (!parent.exists()) {
            createDir(parent);
        }
        if (!dir.exists() && !dir.mkdirs()) { throw new IllegalAccessException("Unable to create output dir: " + dir.getAbsolutePath()); }
        chmodPlusRWX(dir);
    }

    private static void deletePath(File path, boolean inclusive) {
        if (path.isDirectory()) {
            File[] children = path.listFiles();
            if (children != null) {
                for (File child : children) {
                    deletePath(child, true);
                }
            }
        }
        if (inclusive) {
            path.delete();
        }
    }

    private Spoon() {
        // No instances.
    }
}
