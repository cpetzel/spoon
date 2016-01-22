package com.squareup.spoon;

import static com.squareup.spoon.SpoonLogger.logDebug;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.logcat.LogCatListener;
import com.android.ddmlib.logcat.LogCatMessage;
import com.android.ddmlib.logcat.LogCatReceiverTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LumosFailureDeviceLogger extends SpoonDeviceLogger {

    private List<LogCatMessage> perTestMessages;

    public LumosFailureDeviceLogger(IDevice device) {
        super(device);
    }

    @Override
    public void log(List<LogCatMessage> msgList) {

        //if log statements keep coming in, and we are in between tests / not started, just don't worry about them
        if (perTestMessages == null) return;

        synchronized (perTestMessages) {
            // TODO maybe a null check...
            perTestMessages.addAll(msgList);
        }
    }

    public void handleTestStarted() {
        logDebug(true, "handleTestStarted() - creating new perTestMessages List");
        perTestMessages = new ArrayList<LogCatMessage>();
    }

    public void handleTestFailure() {
        messages.addAll(perTestMessages);
    }

}
