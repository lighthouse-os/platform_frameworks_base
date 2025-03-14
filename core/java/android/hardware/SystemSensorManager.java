/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.hardware;

import android.compat.annotation.UnsupportedAppUsage;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.MemoryFile;
import android.os.MessageQueue;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;

import dalvik.system.CloseGuard;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sensor manager implementation that communicates with the built-in
 * system sensors.
 *
 * @hide
 */
public class SystemSensorManager extends SensorManager {
    //TODO: disable extra logging before release
    private static final boolean DEBUG_DYNAMIC_SENSOR = true;
    private static final int MIN_DIRECT_CHANNEL_BUFFER_SIZE = 104;
    private static final int MAX_LISTENER_COUNT = 128;

    private static native void nativeClassInit();
    private static native long nativeCreate(String opPackageName);
    private static native boolean nativeGetSensorAtIndex(long nativeInstance,
            Sensor sensor, int index);
    private static native void nativeGetDynamicSensors(long nativeInstance, List<Sensor> list);
    private static native boolean nativeIsDataInjectionEnabled(long nativeInstance);

    private static native int nativeCreateDirectChannel(
            long nativeInstance, long size, int channelType, int fd, HardwareBuffer buffer);
    private static native void nativeDestroyDirectChannel(
            long nativeInstance, int channelHandle);
    private static native int nativeConfigDirectChannel(
            long nativeInstance, int channelHandle, int sensorHandle, int rate);

    private static native int nativeSetOperationParameter(
            long nativeInstance, int handle, int type, float[] floatValues, int[] intValues);

    private static final Object sLock = new Object();
    @GuardedBy("sLock")
    private static boolean sNativeClassInited = false;
    @GuardedBy("sLock")
    private static InjectEventQueue sInjectEventQueue = null;

    private final ArrayList<Sensor> mFullSensorsList = new ArrayList<>();
    private final Object mDynamicSensorListLock = new Object();
    @GuardedBy("mDynamicSensorListLock")
    private List<Sensor> mFullDynamicSensorsList = new ArrayList<>();
    @GuardedBy("mDynamicSensorListLock")
    private boolean mDynamicSensorListDirty = true;

    private final HashMap<Integer, Sensor> mHandleToSensor = new HashMap<>();

    // Listener list
    private final HashMap<SensorEventListener, SensorEventQueue> mSensorListeners =
            new HashMap<SensorEventListener, SensorEventQueue>();
    private final HashMap<TriggerEventListener, TriggerEventQueue> mTriggerListeners =
            new HashMap<TriggerEventListener, TriggerEventQueue>();

    // Dynamic Sensor callbacks
    private HashMap<DynamicSensorCallback, Handler>
            mDynamicSensorCallbacks = new HashMap<>();
    private BroadcastReceiver mDynamicSensorBroadcastReceiver;

    // Looper associated with the context in which this instance was created.
    private final Looper mMainLooper;
    private final int mTargetSdkLevel;
    private final Context mContext;
    private final long mNativeInstance;

    /** {@hide} */
    public SystemSensorManager(Context context, Looper mainLooper) {
        synchronized (sLock) {
            if (!sNativeClassInited) {
                sNativeClassInited = true;
                nativeClassInit();
            }
        }

        mMainLooper = mainLooper;
        mTargetSdkLevel = context.getApplicationInfo().targetSdkVersion;
        mContext = context;
        mNativeInstance = nativeCreate(context.getOpPackageName());

        // initialize the sensor list
        for (int index = 0;; ++index) {
            Sensor sensor = new Sensor();
            if (!nativeGetSensorAtIndex(mNativeInstance, sensor, index)) break;
            mFullSensorsList.add(sensor);
            mHandleToSensor.put(sensor.getHandle(), sensor);
        }
    }


    /** @hide */
    @Override
    protected List<Sensor> getFullSensorList() {
        return mFullSensorsList;
    }

    /** @hide */
    @Override
    protected List<Sensor> getFullDynamicSensorList() {
        // only set up broadcast receiver if the application tries to find dynamic sensors or
        // explicitly register a DynamicSensorCallback
        setupDynamicSensorBroadcastReceiver();
        updateDynamicSensorList();
        return mFullDynamicSensorsList;
    }

    /** @hide */
    @Override
    protected boolean registerListenerImpl(SensorEventListener listener, Sensor sensor,
            int delayUs, Handler handler, int maxBatchReportLatencyUs, int reservedFlags) {
        if (listener == null || sensor == null) {
            Log.e(TAG, "sensor or listener is null");
            return false;
        }
        // Trigger Sensors should use the requestTriggerSensor call.
        if (sensor.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT) {
            Log.e(TAG, "Trigger Sensors should use the requestTriggerSensor.");
            return false;
        }
        if (maxBatchReportLatencyUs < 0 || delayUs < 0) {
            Log.e(TAG, "maxBatchReportLatencyUs and delayUs should be non-negative");
            return false;
        }
        if (mSensorListeners.size() >= MAX_LISTENER_COUNT) {
            throw new IllegalStateException("register failed, "
                + "the sensor listeners size has exceeded the maximum limit "
                + MAX_LISTENER_COUNT);
        }
        if (Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.SENSOR_BLOCK, 0) == 1) {
            int sensortype = sensor.getType();
            if (sensortype == Sensor.TYPE_SIGNIFICANT_MOTION ||
                    sensortype == Sensor.TYPE_ACCELEROMETER ||
                    sensortype == Sensor.TYPE_LINEAR_ACCELERATION) {
                String pkgName = mContext.getPackageName();
                for (String blockedPkgName : mContext.getResources().getStringArray(
                        com.android.internal.R.array.config_blockPackagesSensorDrain)) {
                    if (pkgName.equals(blockedPkgName)) {
                        Log.w(TAG, "Preventing " + pkgName + "from draining battery using " +
                                sensor.getStringType());
                        return false;
                    }
                }
            }
        }

        // Invariants to preserve:
        // - one Looper per SensorEventListener
        // - one Looper per SensorEventQueue
        // We map SensorEventListener to a SensorEventQueue, which holds the looper
        synchronized (mSensorListeners) {
            SensorEventQueue queue = mSensorListeners.get(listener);
            if (queue == null) {
                Looper looper = (handler != null) ? handler.getLooper() : mMainLooper;
                final String fullClassName =
                        listener.getClass().getEnclosingClass() != null
                            ? listener.getClass().getEnclosingClass().getName()
                            : listener.getClass().getName();
                queue = new SensorEventQueue(listener, looper, this, fullClassName);
                if (!queue.addSensor(sensor, delayUs, maxBatchReportLatencyUs)) {
                    queue.dispose();
                    return false;
                }
                mSensorListeners.put(listener, queue);
                return true;
            } else {
                return queue.addSensor(sensor, delayUs, maxBatchReportLatencyUs);
            }
        }
    }

    /** @hide */
    @Override
    protected void unregisterListenerImpl(SensorEventListener listener, Sensor sensor) {
        // Trigger Sensors should use the cancelTriggerSensor call.
        if (sensor != null && sensor.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT) {
            return;
        }

        synchronized (mSensorListeners) {
            SensorEventQueue queue = mSensorListeners.get(listener);
            if (queue != null) {
                boolean result;
                if (sensor == null) {
                    result = queue.removeAllSensors();
                } else {
                    result = queue.removeSensor(sensor, true);
                }
                if (result && !queue.hasSensors()) {
                    mSensorListeners.remove(listener);
                    queue.dispose();
                }
            }
        }
    }

    /** @hide */
    @Override
    protected boolean requestTriggerSensorImpl(TriggerEventListener listener, Sensor sensor) {
        if (sensor == null) throw new IllegalArgumentException("sensor cannot be null");

        if (listener == null) throw new IllegalArgumentException("listener cannot be null");

        if (sensor.getReportingMode() != Sensor.REPORTING_MODE_ONE_SHOT) return false;

        if (mTriggerListeners.size() >= MAX_LISTENER_COUNT) {
            throw new IllegalStateException("request failed, "
                    + "the trigger listeners size has exceeded the maximum limit "
                    + MAX_LISTENER_COUNT);
        }

        synchronized (mTriggerListeners) {
            TriggerEventQueue queue = mTriggerListeners.get(listener);
            if (queue == null) {
                final String fullClassName =
                        listener.getClass().getEnclosingClass() != null
                            ? listener.getClass().getEnclosingClass().getName()
                            : listener.getClass().getName();
                queue = new TriggerEventQueue(listener, mMainLooper, this, fullClassName);
                if (!queue.addSensor(sensor, 0, 0)) {
                    queue.dispose();
                    return false;
                }
                mTriggerListeners.put(listener, queue);
                return true;
            } else {
                return queue.addSensor(sensor, 0, 0);
            }
        }
    }

    /** @hide */
    @Override
    protected boolean cancelTriggerSensorImpl(TriggerEventListener listener, Sensor sensor,
            boolean disable) {
        if (sensor != null && sensor.getReportingMode() != Sensor.REPORTING_MODE_ONE_SHOT) {
            return false;
        }
        synchronized (mTriggerListeners) {
            TriggerEventQueue queue = mTriggerListeners.get(listener);
            if (queue != null) {
                boolean result;
                if (sensor == null) {
                    result = queue.removeAllSensors();
                } else {
                    result = queue.removeSensor(sensor, disable);
                }
                if (result && !queue.hasSensors()) {
                    mTriggerListeners.remove(listener);
                    queue.dispose();
                }
                return result;
            }
            return false;
        }
    }

    protected boolean flushImpl(SensorEventListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener cannot be null");

        synchronized (mSensorListeners) {
            SensorEventQueue queue = mSensorListeners.get(listener);
            if (queue == null) {
                return false;
            } else {
                return (queue.flush() == 0);
            }
        }
    }

    protected boolean initDataInjectionImpl(boolean enable) {
        synchronized (sLock) {
            if (enable) {
                boolean isDataInjectionModeEnabled = nativeIsDataInjectionEnabled(mNativeInstance);
                // The HAL does not support injection OR SensorService hasn't been set in DI mode.
                if (!isDataInjectionModeEnabled) {
                    Log.e(TAG, "Data Injection mode not enabled");
                    return false;
                }
                // Initialize a client for data_injection.
                if (sInjectEventQueue == null) {
                    try {
                        sInjectEventQueue = new InjectEventQueue(
                                mMainLooper, this, mContext.getPackageName());
                    } catch (RuntimeException e) {
                        Log.e(TAG, "Cannot create InjectEventQueue: " + e);
                    }
                }
                return sInjectEventQueue != null;
            } else {
                // If data injection is being disabled clean up the native resources.
                if (sInjectEventQueue != null) {
                    sInjectEventQueue.dispose();
                    sInjectEventQueue = null;
                }
                return true;
            }
        }
    }

    protected boolean injectSensorDataImpl(Sensor sensor, float[] values, int accuracy,
            long timestamp) {
        synchronized (sLock) {
            if (sInjectEventQueue == null) {
                Log.e(TAG, "Data injection mode not activated before calling injectSensorData");
                return false;
            }
            int ret = sInjectEventQueue.injectSensorData(sensor.getHandle(), values, accuracy,
                                                         timestamp);
            // If there are any errors in data injection clean up the native resources.
            if (ret != 0) {
                sInjectEventQueue.dispose();
                sInjectEventQueue = null;
            }
            return ret == 0;
        }
    }

    private void cleanupSensorConnection(Sensor sensor) {
        mHandleToSensor.remove(sensor.getHandle());

        if (sensor.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT) {
            synchronized (mTriggerListeners) {
                HashMap<TriggerEventListener, TriggerEventQueue> triggerListeners =
                        new HashMap<TriggerEventListener, TriggerEventQueue>(mTriggerListeners);

                for (TriggerEventListener l : triggerListeners.keySet()) {
                    if (DEBUG_DYNAMIC_SENSOR) {
                        Log.i(TAG, "removed trigger listener" + l.toString()
                                + " due to sensor disconnection");
                    }
                    cancelTriggerSensorImpl(l, sensor, true);
                }
            }
        } else {
            synchronized (mSensorListeners) {
                HashMap<SensorEventListener, SensorEventQueue> sensorListeners =
                        new HashMap<SensorEventListener, SensorEventQueue>(mSensorListeners);

                for (SensorEventListener l: sensorListeners.keySet()) {
                    if (DEBUG_DYNAMIC_SENSOR) {
                        Log.i(TAG, "removed event listener" + l.toString()
                                + " due to sensor disconnection");
                    }
                    unregisterListenerImpl(l, sensor);
                }
            }
        }
    }

    private void updateDynamicSensorList() {
        synchronized (mDynamicSensorListLock) {
            if (mDynamicSensorListDirty) {
                List<Sensor> list = new ArrayList<>();
                nativeGetDynamicSensors(mNativeInstance, list);

                final List<Sensor> updatedList = new ArrayList<>();
                final List<Sensor> addedList = new ArrayList<>();
                final List<Sensor> removedList = new ArrayList<>();

                boolean changed = diffSortedSensorList(
                        mFullDynamicSensorsList, list, updatedList, addedList, removedList);

                if (changed) {
                    if (DEBUG_DYNAMIC_SENSOR) {
                        Log.i(TAG, "DYNS dynamic sensor list cached should be updated");
                    }
                    mFullDynamicSensorsList = updatedList;

                    for (Sensor s: addedList) {
                        mHandleToSensor.put(s.getHandle(), s);
                    }

                    Handler mainHandler = new Handler(mContext.getMainLooper());

                    for (Map.Entry<DynamicSensorCallback, Handler> entry :
                            mDynamicSensorCallbacks.entrySet()) {
                        final DynamicSensorCallback callback = entry.getKey();
                        Handler handler =
                                entry.getValue() == null ? mainHandler : entry.getValue();

                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                for (Sensor s: addedList) {
                                    callback.onDynamicSensorConnected(s);
                                }
                                for (Sensor s: removedList) {
                                    callback.onDynamicSensorDisconnected(s);
                                }
                            }
                        });
                    }

                    for (Sensor s: removedList) {
                        cleanupSensorConnection(s);
                    }
                }

                mDynamicSensorListDirty = false;
            }
        }
    }

    private void setupDynamicSensorBroadcastReceiver() {
        if (mDynamicSensorBroadcastReceiver == null) {
            mDynamicSensorBroadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent.getAction() == Intent.ACTION_DYNAMIC_SENSOR_CHANGED) {
                        if (DEBUG_DYNAMIC_SENSOR) {
                            Log.i(TAG, "DYNS received DYNAMIC_SENSOR_CHANED broadcast");
                        }
                        // Dynamic sensors probably changed
                        synchronized (mDynamicSensorListLock) {
                            mDynamicSensorListDirty = true;
                            updateDynamicSensorList();
                        }
                    }
                }
            };

            IntentFilter filter = new IntentFilter("dynamic_sensor_change");
            filter.addAction(Intent.ACTION_DYNAMIC_SENSOR_CHANGED);
            mContext.registerReceiver(mDynamicSensorBroadcastReceiver, filter);
        }
    }

    private void teardownDynamicSensorBroadcastReceiver() {
        mDynamicSensorCallbacks.clear();
        mContext.unregisterReceiver(mDynamicSensorBroadcastReceiver);
        mDynamicSensorBroadcastReceiver = null;
    }

    /** @hide */
    protected void registerDynamicSensorCallbackImpl(
            DynamicSensorCallback callback, Handler handler) {
        if (DEBUG_DYNAMIC_SENSOR) {
            Log.i(TAG, "DYNS Register dynamic sensor callback");
        }

        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }
        if (mDynamicSensorCallbacks.containsKey(callback)) {
            // has been already registered, ignore
            return;
        }

        setupDynamicSensorBroadcastReceiver();
        mDynamicSensorCallbacks.put(callback, handler);
    }

    /** @hide */
    protected void unregisterDynamicSensorCallbackImpl(
            DynamicSensorCallback callback) {
        if (DEBUG_DYNAMIC_SENSOR) {
            Log.i(TAG, "Removing dynamic sensor listerner");
        }
        mDynamicSensorCallbacks.remove(callback);
    }

    /*
     * Find the difference of two List<Sensor> assuming List are sorted by handle of sensor,
     * assuming the input list is already sorted by handle. Inputs are ol and nl; outputs are
     * updated, added and removed. Any of the output lists can be null in case the result is not
     * interested.
     */
    private static boolean diffSortedSensorList(
            List<Sensor> oldList, List<Sensor> newList, List<Sensor> updated,
            List<Sensor> added, List<Sensor> removed) {

        boolean changed = false;

        int i = 0, j = 0;
        while (true) {
            if (j < oldList.size() && (i >= newList.size()
                    || newList.get(i).getHandle() > oldList.get(j).getHandle())) {
                changed = true;
                if (removed != null) {
                    removed.add(oldList.get(j));
                }
                ++j;
            } else if (i < newList.size() && (j >= oldList.size()
                    || newList.get(i).getHandle() < oldList.get(j).getHandle())) {
                changed = true;
                if (added != null) {
                    added.add(newList.get(i));
                }
                if (updated != null) {
                    updated.add(newList.get(i));
                }
                ++i;
            } else if (i < newList.size() && j < oldList.size()
                    && newList.get(i).getHandle() == oldList.get(j).getHandle()) {
                if (updated != null) {
                    updated.add(oldList.get(j));
                }
                ++i;
                ++j;
            } else {
                break;
            }
        }
        return changed;
    }

    /** @hide */
    protected int configureDirectChannelImpl(
            SensorDirectChannel channel, Sensor sensor, int rate) {
        if (!channel.isOpen()) {
            throw new IllegalStateException("channel is closed");
        }

        if (rate < SensorDirectChannel.RATE_STOP
                || rate > SensorDirectChannel.RATE_VERY_FAST) {
            throw new IllegalArgumentException("rate parameter invalid");
        }

        if (sensor == null && rate != SensorDirectChannel.RATE_STOP) {
            // the stop all sensors case
            throw new IllegalArgumentException(
                    "when sensor is null, rate can only be DIRECT_RATE_STOP");
        }

        int sensorHandle = (sensor == null) ? -1 : sensor.getHandle();

        int ret = nativeConfigDirectChannel(
                mNativeInstance, channel.getNativeHandle(), sensorHandle, rate);

        if (rate == SensorDirectChannel.RATE_STOP) {
            return (ret == 0) ? 1 : 0;
        } else {
            return (ret > 0) ? ret : 0;
        }
    }

    /** @hide */
    protected SensorDirectChannel createDirectChannelImpl(
            MemoryFile memoryFile, HardwareBuffer hardwareBuffer) {
        int id;
        int type;
        long size;
        if (memoryFile != null) {
            int fd;
            try {
                fd = memoryFile.getFileDescriptor().getInt$();
            } catch (IOException e) {
                throw new IllegalArgumentException("MemoryFile object is not valid");
            }

            if (memoryFile.length() < MIN_DIRECT_CHANNEL_BUFFER_SIZE) {
                throw new IllegalArgumentException(
                        "Size of MemoryFile has to be greater than "
                        + MIN_DIRECT_CHANNEL_BUFFER_SIZE);
            }

            size = memoryFile.length();
            id = nativeCreateDirectChannel(
                    mNativeInstance, size, SensorDirectChannel.TYPE_MEMORY_FILE, fd, null);
            if (id <= 0) {
                throw new UncheckedIOException(
                        new IOException("create MemoryFile direct channel failed " + id));
            }
            type = SensorDirectChannel.TYPE_MEMORY_FILE;
        } else if (hardwareBuffer != null) {
            if (hardwareBuffer.getFormat() != HardwareBuffer.BLOB) {
                throw new IllegalArgumentException("Format of HardwareBuffer must be BLOB");
            }
            if (hardwareBuffer.getHeight() != 1) {
                throw new IllegalArgumentException("Height of HardwareBuffer must be 1");
            }
            if (hardwareBuffer.getWidth() < MIN_DIRECT_CHANNEL_BUFFER_SIZE) {
                throw new IllegalArgumentException(
                        "Width if HaradwareBuffer must be greater than "
                        + MIN_DIRECT_CHANNEL_BUFFER_SIZE);
            }
            if ((hardwareBuffer.getUsage() & HardwareBuffer.USAGE_SENSOR_DIRECT_DATA) == 0) {
                throw new IllegalArgumentException(
                        "HardwareBuffer must set usage flag USAGE_SENSOR_DIRECT_DATA");
            }
            size = hardwareBuffer.getWidth();
            id = nativeCreateDirectChannel(
                    mNativeInstance, size, SensorDirectChannel.TYPE_HARDWARE_BUFFER,
                    -1, hardwareBuffer);
            if (id <= 0) {
                throw new UncheckedIOException(
                        new IOException("create HardwareBuffer direct channel failed " + id));
            }
            type = SensorDirectChannel.TYPE_HARDWARE_BUFFER;
        } else {
            throw new NullPointerException("shared memory object cannot be null");
        }
        return new SensorDirectChannel(this, id, type, size);
    }

    /** @hide */
    protected void destroyDirectChannelImpl(SensorDirectChannel channel) {
        if (channel != null) {
            nativeDestroyDirectChannel(mNativeInstance, channel.getNativeHandle());
        }
    }

    /*
     * BaseEventQueue is the communication channel with the sensor service,
     * SensorEventQueue, TriggerEventQueue are subclases and there is one-to-one mapping between
     * the queues and the listeners. InjectEventQueue is also a sub-class which is a special case
     * where data is being injected into the sensor HAL through the sensor service. It is not
     * associated with any listener and there is one InjectEventQueue associated with a
     * SensorManager instance.
     */
    private abstract static class BaseEventQueue {
        private static native long nativeInitBaseEventQueue(long nativeManager,
                WeakReference<BaseEventQueue> eventQWeak, MessageQueue msgQ,
                String packageName, int mode, String opPackageName);
        private static native int nativeEnableSensor(long eventQ, int handle, int rateUs,
                int maxBatchReportLatencyUs);
        private static native int nativeDisableSensor(long eventQ, int handle);
        private static native void nativeDestroySensorEventQueue(long eventQ);
        private static native int nativeFlushSensor(long eventQ);
        private static native int nativeInjectSensorData(long eventQ, int handle,
                float[] values, int accuracy, long timestamp);

        private long mNativeSensorEventQueue;
        private final SparseBooleanArray mActiveSensors = new SparseBooleanArray();
        protected final SparseIntArray mSensorAccuracies = new SparseIntArray();
        private final CloseGuard mCloseGuard = CloseGuard.get();
        protected final SystemSensorManager mManager;

        protected static final int OPERATING_MODE_NORMAL = 0;
        protected static final int OPERATING_MODE_DATA_INJECTION = 1;

        BaseEventQueue(Looper looper, SystemSensorManager manager, int mode, String packageName) {
            if (packageName == null) packageName = "";
            mNativeSensorEventQueue = nativeInitBaseEventQueue(manager.mNativeInstance,
                    new WeakReference<>(this), looper.getQueue(),
                    packageName, mode, manager.mContext.getOpPackageName());
            mCloseGuard.open("dispose");
            mManager = manager;
        }

        public void dispose() {
            dispose(false);
        }

        public boolean addSensor(
                Sensor sensor, int delayUs, int maxBatchReportLatencyUs) {
            // Check if already present.
            int handle = sensor.getHandle();
            if (mActiveSensors.get(handle)) return false;

            // Get ready to receive events before calling enable.
            mActiveSensors.put(handle, true);
            addSensorEvent(sensor);
            if (enableSensor(sensor, delayUs, maxBatchReportLatencyUs) != 0) {
                // Try continuous mode if batching fails.
                if (maxBatchReportLatencyUs == 0
                        || maxBatchReportLatencyUs > 0 && enableSensor(sensor, delayUs, 0) != 0) {
                    removeSensor(sensor, false);
                    return false;
                }
            }
            return true;
        }

        public boolean removeAllSensors() {
            for (int i = 0; i < mActiveSensors.size(); i++) {
                if (mActiveSensors.valueAt(i) == true) {
                    int handle = mActiveSensors.keyAt(i);
                    Sensor sensor = mManager.mHandleToSensor.get(handle);
                    if (sensor != null) {
                        disableSensor(sensor);
                        mActiveSensors.put(handle, false);
                        removeSensorEvent(sensor);
                    } else {
                        // sensor just disconnected -- just ignore.
                    }
                }
            }
            return true;
        }

        public boolean removeSensor(Sensor sensor, boolean disable) {
            final int handle = sensor.getHandle();
            if (mActiveSensors.get(handle)) {
                if (disable) disableSensor(sensor);
                mActiveSensors.put(sensor.getHandle(), false);
                removeSensorEvent(sensor);
                return true;
            }
            return false;
        }

        public int flush() {
            if (mNativeSensorEventQueue == 0) throw new NullPointerException();
            return nativeFlushSensor(mNativeSensorEventQueue);
        }

        public boolean hasSensors() {
            // no more sensors are set
            return mActiveSensors.indexOfValue(true) >= 0;
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                dispose(true);
            } finally {
                super.finalize();
            }
        }

        private void dispose(boolean finalized) {
            if (mCloseGuard != null) {
                if (finalized) {
                    mCloseGuard.warnIfOpen();
                }
                mCloseGuard.close();
            }
            if (mNativeSensorEventQueue != 0) {
                nativeDestroySensorEventQueue(mNativeSensorEventQueue);
                mNativeSensorEventQueue = 0;
            }
        }

        private int enableSensor(
                Sensor sensor, int rateUs, int maxBatchReportLatencyUs) {
            if (mNativeSensorEventQueue == 0) throw new NullPointerException();
            if (sensor == null) throw new NullPointerException();
            return nativeEnableSensor(mNativeSensorEventQueue, sensor.getHandle(), rateUs,
                    maxBatchReportLatencyUs);
        }

        protected int injectSensorDataBase(int handle, float[] values, int accuracy,
                                           long timestamp) {
            return nativeInjectSensorData(
                    mNativeSensorEventQueue, handle, values, accuracy, timestamp);
        }

        private int disableSensor(Sensor sensor) {
            if (mNativeSensorEventQueue == 0) throw new NullPointerException();
            if (sensor == null) throw new NullPointerException();
            return nativeDisableSensor(mNativeSensorEventQueue, sensor.getHandle());
        }
        @UnsupportedAppUsage
        protected abstract void dispatchSensorEvent(int handle, float[] values, int accuracy,
                long timestamp);
        @UnsupportedAppUsage
        protected abstract void dispatchFlushCompleteEvent(int handle);

        @UnsupportedAppUsage
        protected void dispatchAdditionalInfoEvent(
                int handle, int type, int serial, float[] floatValues, int[] intValues) {
            // default implementation is do nothing
        }

        protected abstract void addSensorEvent(Sensor sensor);
        protected abstract void removeSensorEvent(Sensor sensor);
    }

    static final class SensorEventQueue extends BaseEventQueue {
        private final SensorEventListener mListener;
        private final SparseArray<SensorEvent> mSensorsEvents = new SparseArray<SensorEvent>();

        public SensorEventQueue(SensorEventListener listener, Looper looper,
                SystemSensorManager manager, String packageName) {
            super(looper, manager, OPERATING_MODE_NORMAL, packageName);
            mListener = listener;
        }

        @Override
        public void addSensorEvent(Sensor sensor) {
            SensorEvent t = new SensorEvent(Sensor.getMaxLengthValuesArray(sensor,
                    mManager.mTargetSdkLevel));
            synchronized (mSensorsEvents) {
                mSensorsEvents.put(sensor.getHandle(), t);
            }
        }

        @Override
        public void removeSensorEvent(Sensor sensor) {
            synchronized (mSensorsEvents) {
                mSensorsEvents.delete(sensor.getHandle());
            }
        }

        // Called from native code.
        @SuppressWarnings("unused")
        @Override
        protected void dispatchSensorEvent(int handle, float[] values, int inAccuracy,
                long timestamp) {
            final Sensor sensor = mManager.mHandleToSensor.get(handle);
            if (sensor == null) {
                // sensor disconnected
                return;
            }

            SensorEvent t = null;
            synchronized (mSensorsEvents) {
                t = mSensorsEvents.get(handle);
            }

            if (t == null) {
                // This may happen if the client has unregistered and there are pending events in
                // the queue waiting to be delivered. Ignore.
                return;
            }
            // Copy from the values array.
            System.arraycopy(values, 0, t.values, 0, t.values.length);
            t.timestamp = timestamp;
            t.accuracy = inAccuracy;
            t.sensor = sensor;

            // call onAccuracyChanged() only if the value changes
            final int accuracy = mSensorAccuracies.get(handle);
            if ((t.accuracy >= 0) && (accuracy != t.accuracy)) {
                mSensorAccuracies.put(handle, t.accuracy);
                mListener.onAccuracyChanged(t.sensor, t.accuracy);
            }
            mListener.onSensorChanged(t);
        }

        // Called from native code.
        @SuppressWarnings("unused")
        @Override
        protected void dispatchFlushCompleteEvent(int handle) {
            if (mListener instanceof SensorEventListener2) {
                final Sensor sensor = mManager.mHandleToSensor.get(handle);
                if (sensor == null) {
                    // sensor disconnected
                    return;
                }
                ((SensorEventListener2) mListener).onFlushCompleted(sensor);
            }
            return;
        }

        // Called from native code.
        @SuppressWarnings("unused")
        @Override
        protected void dispatchAdditionalInfoEvent(
                int handle, int type, int serial, float[] floatValues, int[] intValues) {
            if (mListener instanceof SensorEventCallback) {
                final Sensor sensor = mManager.mHandleToSensor.get(handle);
                if (sensor == null) {
                    // sensor disconnected
                    return;
                }
                SensorAdditionalInfo info =
                        new SensorAdditionalInfo(sensor, type, serial, intValues, floatValues);
                ((SensorEventCallback) mListener).onSensorAdditionalInfo(info);
            }
        }
    }

    static final class TriggerEventQueue extends BaseEventQueue {
        private final TriggerEventListener mListener;
        private final SparseArray<TriggerEvent> mTriggerEvents = new SparseArray<TriggerEvent>();

        public TriggerEventQueue(TriggerEventListener listener, Looper looper,
                SystemSensorManager manager, String packageName) {
            super(looper, manager, OPERATING_MODE_NORMAL, packageName);
            mListener = listener;
        }

        @Override
        public void addSensorEvent(Sensor sensor) {
            TriggerEvent t = new TriggerEvent(Sensor.getMaxLengthValuesArray(sensor,
                    mManager.mTargetSdkLevel));
            synchronized (mTriggerEvents) {
                mTriggerEvents.put(sensor.getHandle(), t);
            }
        }

        @Override
        public void removeSensorEvent(Sensor sensor) {
            synchronized (mTriggerEvents) {
                mTriggerEvents.delete(sensor.getHandle());
            }
        }

        // Called from native code.
        @SuppressWarnings("unused")
        @Override
        protected void dispatchSensorEvent(int handle, float[] values, int accuracy,
                long timestamp) {
            final Sensor sensor = mManager.mHandleToSensor.get(handle);
            if (sensor == null) {
                // sensor disconnected
                return;
            }
            TriggerEvent t = null;
            synchronized (mTriggerEvents) {
                t = mTriggerEvents.get(handle);
            }
            if (t == null) {
                Log.e(TAG, "Error: Trigger Event is null for Sensor: " + sensor);
                return;
            }

            // Copy from the values array.
            System.arraycopy(values, 0, t.values, 0, t.values.length);
            t.timestamp = timestamp;
            t.sensor = sensor;

            // A trigger sensor is auto disabled. So just clean up and don't call native
            // disable.
            mManager.cancelTriggerSensorImpl(mListener, sensor, false);

            mListener.onTrigger(t);
        }

        @SuppressWarnings("unused")
        protected void dispatchFlushCompleteEvent(int handle) {
        }
    }

    final class InjectEventQueue extends BaseEventQueue {
        public InjectEventQueue(Looper looper, SystemSensorManager manager, String packageName) {
            super(looper, manager, OPERATING_MODE_DATA_INJECTION, packageName);
        }

        int injectSensorData(int handle, float[] values, int accuracy, long timestamp) {
            return injectSensorDataBase(handle, values, accuracy, timestamp);
        }

        @SuppressWarnings("unused")
        protected void dispatchSensorEvent(int handle, float[] values, int accuracy,
                long timestamp) {
        }

        @SuppressWarnings("unused")
        protected void dispatchFlushCompleteEvent(int handle) {

        }

        @SuppressWarnings("unused")
        protected void addSensorEvent(Sensor sensor) {

        }

        @SuppressWarnings("unused")
        protected void removeSensorEvent(Sensor sensor) {

        }
    }

    protected boolean setOperationParameterImpl(SensorAdditionalInfo parameter) {
        int handle = -1;
        if (parameter.sensor != null) handle = parameter.sensor.getHandle();
        return nativeSetOperationParameter(
                mNativeInstance, handle,
                parameter.type, parameter.floatValues, parameter.intValues) == 0;
    }
}
