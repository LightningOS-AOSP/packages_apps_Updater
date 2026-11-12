/*
 * Copyright (C) 2026 The LightningOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lightning.updater.misc;

import android.os.Build;
import android.os.SystemProperties;
import android.text.format.DateFormat;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the ROM build ID (for example
 * "LightningOS-1.1-Ignition-17.0-MINI-20261113-0433-begonia-UNOFFICIAL")
 * and falls back to individual system properties for anything that is
 * missing or does not match the expected pattern.
 */
public final class BuildInfo {

    private static final String TAG = "BuildInfo";

    private static final Pattern P = Pattern.compile(
            "^(.+?)-(\\d+(?:\\.\\d+)*)-([A-Za-z0-9]+)-(\\d+(?:\\.\\d+)*)-([A-Za-z0-9]+)"
                    + "-(\\d{8})-(\\d{4})-([A-Za-z0-9_]+)-([A-Za-z]+)$");

    public final String rom, version, codename, android, flavor, date, time, device, type;
    /** True when every field was recovered from the build ID itself. */
    public final boolean parsedFromId;

    private BuildInfo(String rom, String version, String codename, String android,
            String flavor, String date, String time, String device, String type,
            boolean parsedFromId) {
        this.rom = rom;
        this.version = version;
        this.codename = codename;
        this.android = android;
        this.flavor = flavor;
        this.date = date;
        this.time = time;
        this.device = device;
        this.type = type;
        this.parsedFromId = parsedFromId;
    }

    /** Returns null when the string does not match the expected pattern. */
    public static BuildInfo parse(String id) {
        if (id == null) {
            return null;
        }
        Matcher m = P.matcher(id.trim());
        if (!m.matches()) {
            return null;
        }
        return new BuildInfo(m.group(1), m.group(2), m.group(3), m.group(4),
                m.group(5), m.group(6), m.group(7), m.group(8), m.group(9), true);
    }

    /**
     * Builds the info from the ROM's system properties, using the build ID
     * when it matches the expected pattern and falling back to the individual
     * properties for each missing field.
     */
    public static BuildInfo fromSystem() {
        BuildInfo info = parse(SystemProperties.get(Constants.PROP_BUILD_VERSION));
        if (info != null) {
            return info;
        }

        String device = SystemProperties.get(Constants.PROP_DEVICE, "");
        String flavor = SystemProperties.get(Constants.PROP_PACKAGE_TYPE, "");

        String date = "";
        String time = "";
        long timestamp = SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0);
        if (timestamp > 0) {
            SimpleDateFormat utc = new SimpleDateFormat("yyyyMMdd HHmm", Locale.US);
            utc.setTimeZone(TimeZone.getTimeZone("UTC"));
            String[] parts = utc.format(new Date(timestamp * 1000)).split(" ");
            if (parts.length == 2) {
                date = parts[0];
                time = parts[1];
            }
        }

        return new BuildInfo("LightningOS", "", "", Build.VERSION.RELEASE,
                flavor, date, time, device, "", false);
    }

    /** "17.0" becomes "17", "16.1" stays "16.1". */
    public String androidShort() {
        return android.endsWith(".0") ? android.substring(0, android.length() - 2) : android;
    }

    /** "UNOFFICIAL" becomes "Unofficial". Returns "" when the type is unknown. */
    public String typeDisplay() {
        if (type == null || type.isEmpty()) {
            return "";
        }
        return type.substring(0, 1).toUpperCase(Locale.ROOT)
                + type.substring(1).toLowerCase(Locale.ROOT);
    }

    /** True when the build type is known and marked unofficial. */
    public boolean isUnofficial() {
        return type == null || type.isEmpty() || "UNOFFICIAL".equalsIgnoreCase(type);
    }

    /** True when the OTA metadata carried a build type. */
    public boolean hasOtaInfo() {
        return type != null && !type.isEmpty();
    }

    /** "Nov 13, 2026 · 04:33", using the date and time exactly as written in the ID. */
    public String buildDateDisplay(Locale locale) {
        if (date == null || date.isEmpty() || time == null || time.isEmpty()) {
            return "";
        }
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyyMMddHHmm", Locale.ROOT);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = in.parse(date + time);
            SimpleDateFormat dayOut = new SimpleDateFormat(
                    DateFormat.getBestDateTimePattern(locale, "MMMdyyyy"), locale);
            dayOut.setTimeZone(TimeZone.getTimeZone("UTC"));
            return dayOut.format(d) + " · " + time.substring(0, 2) + ":" + time.substring(2);
        } catch (ParseException e) {
            return date + " " + time;
        }
    }
}
