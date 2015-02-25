package com.squareup.spoon;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Collections.unmodifiableList;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.android.ddmlib.logcat.LogCatMessage;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.squareup.spoon.html.HtmlAppData.GameTestData;
import com.squareup.spoon.html.HtmlAppData.KeyValuePair;
import com.squareup.spoon.misc.StackTrace;

public final class DeviceTestResult {
    /** Separator between screenshot timestamp and tag. */
    public static final String SCREENSHOT_SEPARATOR = Spoon.NAME_SEPARATOR;

    public enum Status {
        PASS, FAIL, ERROR
    }

    private final Status status;
    private final StackTrace exception;
    private final long duration;
    private final List<File> screenshots;
    private final File animatedGif;
    private final List<LogCatMessage> log;
    private final List<KeyValuePair> splitTestAssignments;
    private final List<KeyValuePair> userData;
    private final List<KeyValuePair> serverData;
    private final List<GameTestData> gameTests;

    private DeviceTestResult(Status status, StackTrace exception, long duration, List<File> screenshots, File animatedGif,
        List<LogCatMessage> log, List<KeyValuePair> splitTestAssignments, List<KeyValuePair> userData, List<KeyValuePair> serverData,
        List<GameTestData> tests) {
        this.status = status;
        this.exception = exception;
        this.duration = duration;
        this.screenshots = unmodifiableList(new ArrayList<File>(screenshots));
        this.animatedGif = animatedGif;
        this.log = unmodifiableList(new ArrayList<LogCatMessage>(log));
        this.splitTestAssignments = splitTestAssignments;
        this.userData = userData;
        this.serverData = serverData;
        this.gameTests = tests;
    }

    /** Execution status. */
    public Status getStatus() {
        return status;
    }

    /** Exception thrown during execution. */
    public StackTrace getException() {
        return exception;
    }

    /** Length of test execution, in seconds. */
    public long getDuration() {
        return duration;
    }

    /** Screenshots taken during test. */
    public List<File> getScreenshots() {
        return screenshots;
    }

    /** Animated GIF of screenshots. */
    public File getAnimatedGif() {
        return animatedGif;
    }

    public List<LogCatMessage> getLog() {
        return log;
    }

    public List<KeyValuePair> getSplitTestAssignments() {
        return splitTestAssignments;
    }

    public List<KeyValuePair> getServerData() {
        return serverData;
    }

    public List<KeyValuePair> getUserData() {
        return userData;
    }

    public List<GameTestData> getGameTestData() {
        return gameTests;
    }

    public static class Builder {
        private final List<File> screenshots = new ArrayList<File>();
        private Status status = Status.PASS;
        private StackTrace exception;
        private long start;
        private long duration = -1;
        private File animatedGif;
        private List<LogCatMessage> log;
        private List<KeyValuePair> splitTestAssignments;
        private List<KeyValuePair> userData;
        private List<KeyValuePair> serverData;
        private List<GameTestData> gameTests;

        public Builder markTestAsFailed(String message) {
            checkNotNull(message);
            checkArgument(status == Status.PASS, "Status was already marked as " + status);
            status = Status.FAIL;
            exception = StackTrace.from(message);
            return this;
        }

        public Builder markTestAsError(String message) {
            checkNotNull(message);
            checkArgument(status == Status.PASS, "Status was already marked as " + status);
            status = Status.ERROR;
            exception = StackTrace.from(message);
            return this;
        }

        public Builder setLog(List<LogCatMessage> log) {
            checkNotNull(log);
            checkArgument(this.log == null, "Log already added.");
            this.log = log;
            return this;
        }

        public Builder startTest() {
            checkArgument(start == 0, "Start already called.");
            start = System.nanoTime();
            return this;
        }

        public Builder endTest() {
            checkArgument(start != 0, "Start was not called.");
            checkArgument(duration == -1, "End was already called.");
            duration = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
            return this;
        }

        public Builder addScreenshot(File screenshot) {
            checkNotNull(screenshot);
            screenshots.add(screenshot);
            return this;
        }

        public Builder addAppData(String data) {
            checkNotNull(data);

            try {
                parseAppData(data);
            } catch (Exception e) {
                System.out.println("Error parsing Lumos app data. data = " + data);
            }
            return this;
        }

        public Builder setAnimatedGif(File animatedGif) {
            checkNotNull(animatedGif);
            checkArgument(this.animatedGif == null, "Animated GIF already set.");
            this.animatedGif = animatedGif;
            return this;
        }

        public DeviceTestResult build() {
            if (log == null) {
                log = Collections.emptyList();
            }
            return new DeviceTestResult(status, exception, duration, screenshots, animatedGif, log, splitTestAssignments, userData,
                serverData, gameTests);
        }

        private void parseAppData(String JsonDataFromTest) {

            userData = new LinkedList<KeyValuePair>();
            serverData = new LinkedList<KeyValuePair>();
            splitTestAssignments = new LinkedList<KeyValuePair>();
            gameTests = new LinkedList<GameTestData>();

            JsonParser parser = new JsonParser();
            JsonObject rootJson = (JsonObject) parser.parse(JsonDataFromTest);

            try {
                // user data
                JsonObject userDataJsonObject = rootJson.getAsJsonObject("user");
                Set<Entry<String, JsonElement>> userKeys = userDataJsonObject.entrySet();
                for (Entry<String, JsonElement> entry : userKeys) {
                    String name = entry.getKey();
                    JsonElement value = entry.getValue();
                    String splitValue = value.getAsString();
                    userData.add(new KeyValuePair(name, splitValue));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            try {
                // server data
                JsonObject serverDataJsonObject = rootJson.getAsJsonObject("server");
                Set<Entry<String, JsonElement>> serverKeys = serverDataJsonObject.entrySet();
                for (Entry<String, JsonElement> entry : serverKeys) {
                    String name = entry.getKey();
                    JsonElement value = entry.getValue();
                    String splitValue = value.getAsString();
                    serverData.add(new KeyValuePair(name, splitValue));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            try {
                // split tests
                JsonObject splitTestAssignmentJsonObject = rootJson.getAsJsonObject("split_test_assignments");
                Set<Entry<String, JsonElement>> keys = splitTestAssignmentJsonObject.entrySet();
                for (Entry<String, JsonElement> entry : keys) {
                    String name = entry.getKey();
                    JsonElement value = entry.getValue();
                    String splitValue = value.getAsString();
                    splitTestAssignments.add(new KeyValuePair(name, splitValue));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            try {
                // game app data
                JsonObject gameDataJson = rootJson.getAsJsonObject("game");

                JsonArray tests = gameDataJson.getAsJsonArray("tests");

                for (JsonElement test : tests) {
                    JsonObject testObject = test.getAsJsonObject();
                    int time = 0;
                    String interactionType = null;
                    String locale = "Hi Breanna";
                    try {
                        time = testObject.get("time").getAsInt();
                        interactionType = testObject.get("interactionType").getAsString();
                        locale = testObject.get("locale").getAsString();
                    } catch (Exception e) {
                        System.out.println("test info does not contain all data");
                    }
                    String assertMessage = testObject.get("assertMessage").getAsString();
                    String status = testObject.get("status").getAsString();
                    String testType = testObject.get("test").getAsString();
                    gameTests.add(new GameTestData(time, assertMessage, interactionType, status, testType, locale));
                }
            } catch (Exception e) {
                System.out.println("No Game or Test Json object in results");
            }
        }
    }
}
