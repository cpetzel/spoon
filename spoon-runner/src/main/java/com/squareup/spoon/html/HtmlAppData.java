package com.squareup.spoon.html;

import java.util.List;

import com.squareup.spoon.DeviceTest;
import com.squareup.spoon.DeviceTestResult;

/** Model for representing a {@code log.html} page. */
public final class HtmlAppData {
    public static HtmlAppData from(String name, DeviceTest test, DeviceTestResult result) {
        String status;
        switch (result.getStatus()) {
        case PASS:
            status = "passed";
            break;
        case FAIL:
            status = "failed";
            break;
        case ERROR:
            status = "errored";
            break;
        default:
            throw new IllegalArgumentException("Unknown status: " + result.getStatus());
        }

        String title = HtmlUtils.prettifyMethodName(test.getMethodName());
        String subtitle = "Test " + status + " in " + HtmlUtils.humanReadableDuration(result.getDuration()) + " on " + name;

        return new HtmlAppData(title, subtitle, result.getUserData(), result.getServerData(), result.getSplitTestAssignments());
    }

    public final String title;
    public final String subtitle;
    public final List<KeyValuePair> userData;
    public final List<KeyValuePair> serverData;
    public final List<KeyValuePair> splitTestData;

    HtmlAppData(String title, String subtitle, List<KeyValuePair> userData, List<KeyValuePair> serverData, List<KeyValuePair> splitTestData) {
        this.title = title;
        this.subtitle = subtitle;
        this.userData = userData;
        this.serverData = serverData;
        this.splitTestData = splitTestData;
    }

    public static class KeyValuePair {

        public KeyValuePair(String name, String assignment) {
            this.name = name;
            this.value = assignment;
        }

        public final String name;
        public final String value;

    }
}
