/*
 * Copyright (C) 2020 The Pixel Experience Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.internal.util.custom;

import android.os.Build;
import android.util.Log;

import java.util.Arrays;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

public class PixelPropsUtils {

    private static final String TAG = PixelPropsUtils.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final Map<String, Object> propsToChange;
	private static final Map<String, Object> propsToChangePixel2;
    private static final Map<String, Object> propsToChangePixel3XL;
    private static final Map<String, Object> propsToChangeOGPixelXL;

    private static final String[] packagesToChange = {
            "com.breel.wallpapers20",
            "com.google.android.apps.customization.pixel",
            "com.google.android.apps.fitness",
            "com.google.android.apps.recorder",
            "com.google.android.apps.subscriptions.red",
            "com.google.android.apps.tachyon",
            "com.google.android.apps.turboadapter",
            "com.google.android.apps.wallpaper.pixel",
            "com.google.android.as",
            "com.google.android.dialer",
            "com.google.android.inputmethod.latin",
            "com.google.android.soundpicker",
            "com.google.pixel.dynamicwallpapers",
            "com.google.pixel.livewallpaper",
            "com.google.android.apps.safetyhub",
            "com.google.android.apps.turbo",
            "com.google.android.apps.wallpaper",
            "com.google.android.apps.maps"
    };

    private static final String[] packagesToChangePixel3XL = {
            "com.google.android.googlequicksearchbox"
    };
	
	private static final String[] packagesToChangePixel2 = {
            "com.google.android.gms",
            "com.google.android.gms.location.history"
    };

    private static final String[] packagesToChangeOGPixelXL = {
            "com.google.android.apps.photos"
    };

    static {
        propsToChange = new HashMap<>();
        propsToChange.put("BRAND", "google");
        propsToChange.put("MANUFACTURER", "Google");
        propsToChange.put("DEVICE", "redfin");
        propsToChange.put("PRODUCT", "redfin");
        propsToChange.put("MODEL", "Pixel 5");
        propsToChange.put("FINGERPRINT", "google/redfin/redfin:11/RQ3A.210805.001.A1/7474174:user/release-keys");
        propsToChangePixel3XL = new HashMap<>();
        propsToChangePixel3XL.put("BRAND", "google");
        propsToChangePixel3XL.put("MANUFACTURER", "Google");
        propsToChangePixel3XL.put("DEVICE", "crosshatch");
        propsToChangePixel3XL.put("PRODUCT", "crosshatch");
        propsToChangePixel3XL.put("MODEL", "Pixel 3 XL");
        propsToChangePixel3XL.put("FINGERPRINT", "google/crosshatch/crosshatch:11/RQ3A.210805.001.A1/7474174:user/release-keys");
		propsToChangePixel2 = new HashMap<>();
        propsToChangePixel2.put("BRAND", "google");
        propsToChangePixel2.put("MANUFACTURER", "Google");
        propsToChangePixel2.put("DEVICE", "walleye");
        propsToChangePixel2.put("PRODUCT", "walleye");
        propsToChangePixel2.put("MODEL", "Pixel 2");
        propsToChangePixel2.put("FINGERPRINT", "google/walleye/walleye:8.1.0/OPM1.171019.011/4448085:user/release-keys");
        propsToChangeOGPixelXL = new HashMap<>();
        propsToChangeOGPixelXL.put("BRAND", "google");
        propsToChangeOGPixelXL.put("MANUFACTURER", "Google");
        propsToChangeOGPixelXL.put("DEVICE", "marlin");
        propsToChangeOGPixelXL.put("PRODUCT", "marlin");
        propsToChangeOGPixelXL.put("MODEL", "Pixel XL");
        propsToChangeOGPixelXL.put("FINGERPRINT", "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys");
    }

    public static void setProps(String packageName) {
        if (packageName == null){
            return;
        }
        if (Arrays.asList(packagesToChange).contains(packageName)){
            if (DEBUG){
                Log.d(TAG, "Defining props for: " + packageName);
            }
            for (Map.Entry<String, Object> prop : propsToChange.entrySet()) {
                String key = prop.getKey();
                Object value = prop.getValue();
                setPropValue(key, value);
            }
        }
        if (Arrays.asList(packagesToChangePixel3XL).contains(packageName)){
            if (DEBUG){
                Log.d(TAG, "Defining props for: " + packageName);
            }
            for (Map.Entry<String, Object> prop : propsToChangePixel3XL.entrySet()) {
                String key = prop.getKey();
                Object value = prop.getValue();
                setPropValue(key, value);
            }
        }
		if (Arrays.asList(packagesToChangePixel2).contains(packageName)){
            if (DEBUG){
                Log.d(TAG, "Defining props for: " + packageName);
            }
            for (Map.Entry<String, Object> prop : propsToChangePixel2.entrySet()) {
                String key = prop.getKey();
                Object value = prop.getValue();
                setPropValue(key, value);
            }
        }
        if (Arrays.asList(packagesToChangeOGPixelXL).contains(packageName)){
            if (DEBUG){
                Log.d(TAG, "Defining props for: " + packageName);
            }
            for (Map.Entry<String, Object> prop : propsToChangeOGPixelXL.entrySet()) {
                String key = prop.getKey();
                Object value = prop.getValue();
                setPropValue(key, value);
            }
        }
        // Set proper indexing fingerprint
        if (packageName.equals("com.google.android.settings.intelligence")){
            setPropValue("FINGERPRINT", Build.DATE);
        }
    }

    private static void setPropValue(String key, Object value){
        try {
            if (DEBUG){
                Log.d(TAG, "Defining prop " + key + " to " + value.toString());
            }
            Field field = Build.class.getDeclaredField(key);
            field.setAccessible(true);
            field.set(null, value);
            field.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }
}
