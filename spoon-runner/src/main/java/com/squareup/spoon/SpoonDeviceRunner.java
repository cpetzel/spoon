package com.squareup.spoon;

import static com.squareup.spoon.SpoonLogger.logDebug;
import static com.squareup.spoon.SpoonLogger.logError;
import static com.squareup.spoon.SpoonLogger.logInfo;
import static com.squareup.spoon.SpoonUtils.GSON;
import static com.squareup.spoon.SpoonUtils.createAnimatedGif;
import static com.squareup.spoon.SpoonUtils.obtainDirectoryFileEntry;
import static com.squareup.spoon.SpoonUtils.obtainRealDevice;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;

import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.CollectingOutputReceiver;
import com.android.ddmlib.FileListingService.FileEntry;
import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.InstallException;
import com.android.ddmlib.MultiLineReceiver;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.SyncService;
import com.android.ddmlib.TimeoutException;
import com.android.ddmlib.SyncService.ISyncProgressMonitor;
import com.android.ddmlib.logcat.LogCatListener;
import com.android.ddmlib.logcat.LogCatMessage;
import com.android.ddmlib.testrunner.IRemoteAndroidTestRunner;
import com.android.ddmlib.testrunner.RemoteAndroidTestRunner;
import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.squareup.spoon.adapters.TestIdentifierAdapter;

/** Represents a single device and the test configuration to be executed. */
public final class SpoonDeviceRunner {
    private static final String FILE_EXECUTION = "execution.json";
    private static final String FILE_RESULT = "result.json";
    static final String TEMP_DIR = "work";
    static final String JUNIT_DIR = "junit-reports";
    static final String IMAGE_DIR = "image";
    static final String DATA_DIR = "data";

    static final String SPOON_SCREENSHOTS = "SPOON_SCREENSHOTS";

    private final File sdk;
    private final File apk;
    private final File testApk;
    private final String serial;
    private final boolean debug;
    private final boolean noAnimations;
    private final int adbTimeout;
    private final String className;
    private final String methodName;
    private final IRemoteAndroidTestRunner.TestSize testSize;
    private final File work;
    private final File junitReport;
    private final File imageDir;
    private final File dataDir;
    private final String classpath;
    private final SpoonInstrumentationInfo instrumentationInfo;
    private boolean disableLogging;

    /**
     * Create a test runner for a single device.
     * 
     * @param sdk
     *            Path to the local Android SDK directory.
     * @param apk
     *            Path to application APK.
     * @param testApk
     *            Path to test application APK.
     * @param output
     *            Path to output directory.
     * @param serial
     *            Device to run the test on.
     * @param debug
     *            Whether or not debug logging is enabled.
     * @param adbTimeout
     *            time in ms for longest test execution
     * @param classpath
     *            Custom JVM classpath or {@code null}.
     * @param instrumentationInfo
     *            Test apk manifest information.
     * @param className
     *            Test class name to run or {@code null} to run all tests.
     * @param methodName
     *            Test method name to run or {@code null} to run all tests. Must also pass
     *            {@code className}.
     */
    SpoonDeviceRunner(File sdk, File apk, File testApk, File output, String serial, boolean debug, boolean noAnimations, int adbTimeout,
        String classpath, SpoonInstrumentationInfo instrumentationInfo, String className, String methodName,
        IRemoteAndroidTestRunner.TestSize testSize, boolean disableLogging) {
        this.sdk = sdk;
        this.apk = apk;
        this.testApk = testApk;
        this.serial = serial;
        this.debug = debug;
        this.noAnimations = noAnimations;
        this.adbTimeout = adbTimeout;
        this.className = className;
        this.methodName = methodName;
        this.testSize = testSize;
        this.classpath = classpath;
        this.instrumentationInfo = instrumentationInfo;
        this.disableLogging = disableLogging;

        serial = SpoonUtils.sanitizeSerial(serial);
        this.work = FileUtils.getFile(output, TEMP_DIR, serial);
        this.junitReport = FileUtils.getFile(output, JUNIT_DIR, serial + ".xml");
        this.imageDir = FileUtils.getFile(output, IMAGE_DIR, serial);
        this.dataDir = FileUtils.getFile(output, DATA_DIR, serial);

    }

    /** Serialize to disk and start {@link #main(String...)} in another process. */
    public DeviceResult runInNewProcess() throws IOException, InterruptedException {
        logDebug(debug, "[%s]", serial);

        // Create the output directory.
        work.mkdirs();

        // Write our configuration to a file in the output directory.
        FileWriter executionWriter = new FileWriter(new File(work, FILE_EXECUTION));
        GSON.toJson(this, executionWriter);
        executionWriter.close();

        // Kick off a new process to interface with ADB and perform the real execution.
        String name = SpoonDeviceRunner.class.getName();
        Process process = new ProcessBuilder("java", "-Djava.awt.headless=true", "-cp", classpath, name, work.getAbsolutePath()).start();
        printStream(process.getInputStream(), "STDOUT");
        printStream(process.getErrorStream(), "STDERR");

        final int exitCode = process.waitFor();
        logDebug(debug, "Process.waitFor() finished for [%s] with exitCode %d", serial, exitCode);

        // Read the result from a file in the output directory.
        FileReader resultFile = new FileReader(new File(work, FILE_RESULT));
        DeviceResult result = GSON.fromJson(resultFile, DeviceResult.class);
        resultFile.close();

        return result;
    }

    private void printStream(InputStream stream, String tag) throws IOException {
        BufferedReader stdout = new BufferedReader(new InputStreamReader(stream));
        String s;
        while ((s = stdout.readLine()) != null) {
            logDebug(debug, "[%s] %s %s", serial, tag, s);
        }
    }

    /** Execute instrumentation on the target device and return a result summary. */
    public DeviceResult run(AndroidDebugBridge adb) {
        String appPackage = instrumentationInfo.getApplicationPackage();
        String testPackage = instrumentationInfo.getInstrumentationPackage();
        String testRunner = instrumentationInfo.getTestRunnerClass();
        TestIdentifierAdapter testIdentifierAdapter = TestIdentifierAdapter.fromTestRunner(testRunner);

        logDebug(debug, "InstrumentationInfo: [%s]", instrumentationInfo);

        if (debug) {
            SpoonUtils.setDdmlibInternalLoggingLevel();
        }

        DeviceResult.Builder result = new DeviceResult.Builder();

        IDevice device = obtainRealDevice(adb, serial);
        logDebug(debug, "Got realDevice for [%s]", serial);

        // Get relevant device information.
        final DeviceDetails deviceDetails = DeviceDetails.createForDevice(device);
        result.setDeviceDetails(deviceDetails);
        logDebug(debug, "[%s] setDeviceDetails %s", serial, deviceDetails);

        try {
            // Now install the main application and the instrumentation application.
            String installError = device.installPackage(apk.getAbsolutePath(), true);
            if (installError != null) {
                logInfo("[%s] app apk install failed.  Error [%s]", serial, installError);
                return result.markInstallAsFailed("Unable to install application APK.").build();
            }
            installError = device.installPackage(testApk.getAbsolutePath(), true);
            if (installError != null) {
                logInfo("[%s] test apk install failed.  Error [%s]", serial, installError);
                return result.markInstallAsFailed("Unable to install instrumentation APK.").build();
            }
        } catch (InstallException e) {
            logInfo("InstallException on device [%s]", serial);
            e.printStackTrace(System.out);
            return result.markInstallAsFailed(e.getMessage()).build();
        }

        // Create the output directory, if it does not already exist.
        work.mkdirs();

        // TODO the old way of logging. either always on or always off for the entire duration
        // SpoonDeviceLogger deviceLogger = null;
        // if (logcatEnabled) {
        // Initiate device logging.
        // deviceLogger = new SpoonDeviceLogger(device); // starts a thread
        // }

        // If this is Android Marshmallow or above grant WRITE_EXTERNAL_STORAGE
        if (Integer.parseInt(device.getProperty(IDevice.PROP_BUILD_API_LEVEL)) >= 23) {
            try {
                CollectingOutputReceiver grantOutputReceiver = new CollectingOutputReceiver();
                device.executeShellCommand("pm grant " + appPackage + " android.permission.READ_EXTERNAL_STORAGE", grantOutputReceiver);
                device.executeShellCommand("pm grant " + appPackage + " android.permission.WRITE_EXTERNAL_STORAGE", grantOutputReceiver);
            } catch (Exception e) {
                logInfo("Exception while granting external storage access to application apk" + "on device [%s]", serial);
                e.printStackTrace(System.out);
                return result.markInstallAsFailed("Unable to grant external storage access to" + " application APK.").build();
            }
        }
        
        
        SpoonDeviceLogger deviceLogger = new LumosFailureDeviceLogger(device);
        // Run all the tests! o/
        try {
            logDebug(debug, "About to actually run tests for [%s]", serial);
            RemoteAndroidTestRunner runner = new RemoteAndroidTestRunner(testPackage, testRunner, device);
            runner.setMaxtimeToOutputResponse(adbTimeout);
            if (!Strings.isNullOrEmpty(className)) {
                if (Strings.isNullOrEmpty(methodName)) {
                    runner.setClassName(className);
                } else {
                    runner.setMethodName(className, methodName);
                }
            }
            if (testSize != null) {
                runner.setTestSize(testSize);
            }
            runner.run(new SpoonTestRunListener(result, debug, testIdentifierAdapter, deviceLogger), new XmlTestRunListener(junitReport));
        } catch (Exception e) {
            result.addException(e);
        }

        if (!disableLogging && deviceLogger != null) {
            // Grab all the parsed logs and map them to individual tests.
            Map<DeviceTest, List<LogCatMessage>> logs = deviceLogger.getParsedLogs();
            for (Map.Entry<DeviceTest, List<LogCatMessage>> entry : logs.entrySet()) {
                DeviceTestResult.Builder builder = result.getMethodResultBuilder(entry.getKey());
                if (builder != null) {
                    builder.setLog(entry.getValue());
                }
            }
            logDebug(debug, "grabbed %d test logs from [%s]", logs.size(), serial);
        } else {
            logDebug(debug, "We are not grabbing any logs from the devices.", serial);
        }

        try {
            logDebug(debug, "About to grab screenshots and prepare output for [%s]", serial);

            // Sync device screenshots, if any, to the local filesystem.
            String dirName = SPOON_SCREENSHOTS;
            String localDirName = work.getAbsolutePath();

            // Get external storage directory (fix for Lollipop devices)
            String externalStorageDirectory = getExternalStorageDir(device);
            final String devicePath = externalStorageDirectory + "/lumosity_test_data/" + dirName;

            FileEntry deviceDir = obtainDirectoryFileEntry(devicePath);
            logDebug(debug, "Pulling screenshots from [%s] %s", serial, devicePath);

            device.getSyncService().pull(new FileEntry[] { deviceDir }, localDirName, SyncService.getNullProgressMonitor());

            logDebug(debug, "Done pulling screenshots from [%s] %s", serial, devicePath);

            File screenshotDir = new File(work, dirName);
            if (screenshotDir.exists()) {
                imageDir.mkdirs();

                // Move all children of the screenshot directory into the image folder.
                File[] classNameDirs = screenshotDir.listFiles();
                if (classNameDirs != null) {
                    // Multimap<DeviceTest, File> testScreenshots = ArrayListMultimap.create();
                    for (File classNameDir : classNameDirs) {
                        String className = classNameDir.getName();
                        File destDir = new File(imageDir, className);
                        FileUtils.copyDirectory(classNameDir, destDir);
                        logDebug(debug, "Copying from device [%s] ... %s to %s", serial, classNameDir.getAbsolutePath(),
                            destDir.getAbsolutePath());

                        // // Get a sorted list of all screenshots from the device run.
                        // List<File> screenshots = new ArrayList<File>(FileUtils.listFiles(destDir,
                        // TrueFileFilter.INSTANCE,
                        // TrueFileFilter.INSTANCE));
                        // Collections.sort(screenshots);
                        //
                        // // Iterate over each screenshot and associate it with its corresponding
                        // // method result.
                        // for (File screenshot : screenshots) {
                        // String methodName = screenshot.getParentFile().getName();
                        //
                        // DeviceTest testIdentifier = new DeviceTest(className, methodName);
                        // DeviceTestResult.Builder builder =
                        // result.getMethodResultBuilder(testIdentifier);
                        // if (builder != null) {
                        // builder.addScreenshot(screenshot);
                        // testScreenshots.put(testIdentifier, screenshot);
                        // } else {
                        // logError("Unable to find test for %s", testIdentifier);
                        // }
                        // }
                    }

                    // Don't generate animations if the switch is present
                    // if (!noAnimations) {
                    // // Make animated GIFs for all the tests which have screenshots.
                    // for (DeviceTest deviceTest : testScreenshots.keySet()) {
                    // List<File> screenshots = new
                    // ArrayList<File>(testScreenshots.get(deviceTest));
                    // if (screenshots.size() == 1) {
                    // continue; // Do not make an animated GIF if there is only one
                    // // screenshot.
                    // }
                    // File animatedGif = FileUtils.getFile(imageDir, deviceTest.getClassName(),
                    // deviceTest.getMethodName() + ".gif");
                    // createAnimatedGif(screenshots, animatedGif);
                    // result.getMethodResultBuilder(deviceTest).setAnimatedGif(animatedGif);
                    // }
                    // }
                }
                FileUtils.deleteDirectory(screenshotDir);
            }
        } catch (Exception e) {
            logDebug(debug, "EXCEPTION with pulling and manipulating screenshots on [%s]", serial);

            result.addException(e);
        }

        logDebug(debug, "DONE doing screenshot stuff on [%s]", serial);

        // TODO can eventuall just remove this, as our screenshot tool does not use this data
        if (false) {
            addAppDataToResult(result, device);
        }

        return result.build();
    }

    private void addAppDataToResult(DeviceResult.Builder result, IDevice device) {
        // gather Lumos App Data stuffs
        try {
            logDebug(debug, "About to grab app data and prepare output for [%s]", serial);

            // Sync device app data, if any, to the local filesystem.
            String dirName = "data";
            String localDirName = work.getAbsolutePath();

            // Get external storage directory (fix for Lollipop devices)
            String externalStorageDirectory = getExternalStorageDir(device);
            final String devicePath = externalStorageDirectory + "/lumosity_test_data/" + dirName;

            FileEntry deviceDir = obtainDirectoryFileEntry(devicePath);
            logDebug(debug, "Pulling App Data from [%s] %s", serial, devicePath);

            device.getSyncService().pull(new FileEntry[] { deviceDir }, localDirName, SyncService.getNullProgressMonitor());

            File appDataDir = new File(work, dirName);
            if (appDataDir.exists()) {
                dataDir.mkdirs();

                // Move all children of the appData directory into the data folder.
                File[] classNameDirs = appDataDir.listFiles();
                if (classNameDirs != null) {
                    // HashMap<DeviceTest, String> appTestDatas = new HashMap<DeviceTest, String>();
                    for (File classNameDir : classNameDirs) {
                        if (classNameDir.getName().contains("DS_STORE")) {
                            continue;
                        }
                        String className = classNameDir.getName();
                        File destDir = new File(dataDir, className);
                        FileUtils.copyDirectory(classNameDir, destDir);

                        // Get a sorted list of all test data files! in this class
                        List<File> appDataStuffs = new ArrayList<File>(FileUtils.listFiles(destDir, TrueFileFilter.INSTANCE,
                            TrueFileFilter.INSTANCE));

                        // Iterate over each screenshot and associate it with its corresponding
                        // method result.
                        for (File appDataThingy : appDataStuffs) {
                            String methodName = appDataThingy.getParentFile().getName();

                            DeviceTest testIdentifier = new DeviceTest(className, methodName);
                            DeviceTestResult.Builder builder = result.getMethodResultBuilder(testIdentifier);
                            if (builder != null) {

                                // what is data here?
                                String dataFromFile = FileUtils.readFileToString(appDataThingy);
                                builder.addAppData(dataFromFile);
                            } else {
                                logError("Unable to find test for %s", testIdentifier);
                            }
                        }
                    }
                }

                // not remove?
                FileUtils.deleteDirectory(appDataDir);
            }
        } catch (Exception e) {
            result.addException(e);
        }
    }

    private class MySyncMonitor implements ISyncProgressMonitor {

        @Override
        public void start(int totalWork) {
            logDebug(debug, "totalWork = " + totalWork);
        }

        @Override
        public void stop() {
            logDebug(debug, "stop()");
        }

        @Override
        public boolean isCanceled() {
            return false;
        }

        @Override
        public void startSubTask(String name) {
            logDebug(debug, "startSubTask() name = " + name);

        }

        @Override
        public void advance(int work) {
            // logDebug(debug, "advance = " + work);
        }

    };

    private String getExternalStorageDir(IDevice device) {
        // Get external storage directory
        CollectingOutputReceiver pathNameOutputReciever = new CollectingOutputReceiver();
        String dir = "";
        try {
            device.executeShellCommand("echo $EXTERNAL_STORAGE", pathNameOutputReciever);
            dir = pathNameOutputReciever.getOutput().trim();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return dir;
    }

    // ///////////////////////////////////////////////////////////////////////////
    // // Secondary Per-Device Process /////////////////////////////////////////
    // ///////////////////////////////////////////////////////////////////////////

    /** De-serialize from disk, run the tests, and serialize the result back to disk. */
    public static void main(String... args) {
        if (args.length != 1) { throw new IllegalArgumentException("Must be started with a device directory."); }

        try {
            String outputDirName = args[0];
            File outputDir = new File(outputDirName);
            File executionFile = new File(outputDir, FILE_EXECUTION);
            if (!executionFile.exists()) { throw new IllegalArgumentException("Device directory and/or execution file doesn't exist."); }

            FileReader reader = new FileReader(executionFile);
            SpoonDeviceRunner target = GSON.fromJson(reader, SpoonDeviceRunner.class);
            reader.close();

            AndroidDebugBridge adb = SpoonUtils.initAdb(target.sdk);
            DeviceResult result = target.run(adb);
            AndroidDebugBridge.terminate();

            // Write device result file.
            FileWriter writer = new FileWriter(new File(outputDir, FILE_RESULT));
            GSON.toJson(result, writer);
            writer.close();
        } catch (Throwable ex) {
            logInfo("ERROR: Unable to execute test for target.  Exception message: %s", ex.getMessage());
            ex.printStackTrace(System.out);
            System.exit(1);
        }
    }
}
