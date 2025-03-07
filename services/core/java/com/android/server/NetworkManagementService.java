/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server;

import static android.Manifest.permission.CONNECTIVITY_INTERNAL;
import static android.Manifest.permission.NETWORK_SETTINGS;
import static android.Manifest.permission.OBSERVE_NETWORK_POLICY;
import static android.Manifest.permission.SHUTDOWN;
import static android.net.INetd.FIREWALL_BLACKLIST;
import static android.net.INetd.FIREWALL_CHAIN_DOZABLE;
import static android.net.INetd.FIREWALL_CHAIN_ISOLATED;
import static android.net.INetd.FIREWALL_CHAIN_NONE;
import static android.net.INetd.FIREWALL_CHAIN_POWERSAVE;
import static android.net.INetd.FIREWALL_CHAIN_STANDBY;
import static android.net.INetd.FIREWALL_RULE_ALLOW;
import static android.net.INetd.FIREWALL_RULE_DENY;
import static android.net.INetd.FIREWALL_WHITELIST;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_NAME_DOZABLE;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_NAME_ISOLATED;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_NAME_POWERSAVE;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_NAME_STANDBY;
import static android.net.NetworkPolicyManager.FIREWALL_RULE_DEFAULT;
import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.STATS_PER_UID;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.TrafficStats.UID_TETHERING;
import static android.system.OsConstants.ENETDOWN;

import static com.android.server.NetworkManagementSocketTagger.PROP_QTAGUID_ENABLED;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.INetd;
import android.net.INetdUnsolicitedEventListener;
import android.net.INetworkManagementEventObserver;
import android.net.ITetheringStatsProvider;
import android.net.InetAddresses;
import android.net.InterfaceConfiguration;
import android.net.InterfaceConfigurationParcel;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkPolicyManager;
import android.net.NetworkRequest;
import android.net.NetworkStack;
import android.net.NetworkStats;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.net.TetherStatsParcel;
import android.net.UidRange;
import android.net.UidRangeParcel;
import android.net.shared.NetdUtils;
import android.net.shared.RouteUtils;
import android.net.shared.RouteUtils.ModifyOperation;
import android.net.util.NetdService;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.INetworkActivityListener;
import android.os.INetworkManagementService;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.telephony.DataConnectionRealTimeInfo;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.HexDump;
import com.android.internal.util.Preconditions;

import com.google.android.collect.Maps;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @hide
 */
public class NetworkManagementService extends INetworkManagementService.Stub {

    /**
     * Helper class that encapsulates NetworkManagementService dependencies and makes them
     * easier to mock in unit tests.
     */
    static class SystemServices {
        public IBinder getService(String name) {
            return ServiceManager.getService(name);
        }
        public void registerLocalService(NetworkManagementInternal nmi) {
            LocalServices.addService(NetworkManagementInternal.class, nmi);
        }
        public INetd getNetd() {
            return NetdService.get();
        }
    }

    private static final String TAG = "NetworkManagement";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    private static final int MAX_UID_RANGES_PER_COMMAND = 10;

    /**
     * Name representing {@link #setGlobalAlert(long)} limit when delivered to
     * {@link INetworkManagementEventObserver#limitReached(String, String)}.
     */
    public static final String LIMIT_GLOBAL_ALERT = "globalAlert";

    static final int DAEMON_MSG_MOBILE_CONN_REAL_TIME_INFO = 1;

    static final boolean MODIFY_OPERATION_ADD = true;
    static final boolean MODIFY_OPERATION_REMOVE = false;

    /**
     * Binder context for this service
     */
    private final Context mContext;

    private final Handler mDaemonHandler;

    private final SystemServices mServices;

    private INetd mNetdService;

    private final NetdUnsolicitedEventListener mNetdUnsolicitedEventListener;

    private IBatteryStats mBatteryStats;

    private final RemoteCallbackList<INetworkManagementEventObserver> mObservers =
            new RemoteCallbackList<>();

    @GuardedBy("mTetheringStatsProviders")
    private final HashMap<ITetheringStatsProvider, String>
            mTetheringStatsProviders = Maps.newHashMap();

    /**
     * If both locks need to be held, then they should be obtained in the order:
     * first {@link #mQuotaLock} and then {@link #mRulesLock}.
     */
    private final Object mQuotaLock = new Object();
    private final Object mRulesLock = new Object();

    /** Set of interfaces with active quotas. */
    @GuardedBy("mQuotaLock")
    private HashMap<String, Long> mActiveQuotas = Maps.newHashMap();
    /** Set of interfaces with active alerts. */
    @GuardedBy("mQuotaLock")
    private HashMap<String, Long> mActiveAlerts = Maps.newHashMap();
    /** Set of UIDs blacklisted on metered networks. */
    @GuardedBy("mRulesLock")
    private SparseBooleanArray mUidRejectOnMetered = new SparseBooleanArray();
    /** Set of UIDs whitelisted on metered networks. */
    @GuardedBy("mRulesLock")
    private SparseBooleanArray mUidAllowOnMetered = new SparseBooleanArray();
    /** Set of UIDs with cleartext penalties. */
    @GuardedBy("mQuotaLock")
    private SparseIntArray mUidCleartextPolicy = new SparseIntArray();
    /** Set of UIDs that are to be blocked/allowed by firewall controller. */
    @GuardedBy("mRulesLock")
    private SparseIntArray mUidFirewallRules = new SparseIntArray();
    /**
     * Set of UIDs that are to be blocked/allowed by firewall controller.  This set of Ids matches
     * to application idles.
     */
    @GuardedBy("mRulesLock")
    private SparseIntArray mUidFirewallStandbyRules = new SparseIntArray();
    /**
     * Set of UIDs that are to be blocked/allowed by firewall controller.  This set of Ids matches
     * to device idles.
     */
    @GuardedBy("mRulesLock")
    private SparseIntArray mUidFirewallDozableRules = new SparseIntArray();
    /**
     * Set of UIDs that are to be blocked/allowed by firewall controller.  This set of Ids matches
     * to device on power-save mode.
     */
    @GuardedBy("mRulesLock")
    private SparseIntArray mUidFirewallPowerSaveRules = new SparseIntArray();
    /**
     * Set of UIDs that are to be blocked/allowed by firewall controller.  This set of Ids matches
     * unconditionally at all times.
     */
    @GuardedBy("mRulesLock")
    private SparseIntArray mUidFirewallIsolatedRules = new SparseIntArray();
    /** Set of states for the child firewall chains. True if the chain is active. */
    @GuardedBy("mRulesLock")
    final SparseBooleanArray mFirewallChainStates = new SparseBooleanArray();

    @GuardedBy("mQuotaLock")
    private volatile boolean mDataSaverMode;

    private final Object mIdleTimerLock = new Object();
    /** Set of interfaces with active idle timers. */
    private static class IdleTimerParams {
        public final int timeout;
        public final int type;
        public int networkCount;

        IdleTimerParams(int timeout, int type) {
            this.timeout = timeout;
            this.type = type;
            this.networkCount = 1;
        }
    }
    private HashMap<String, IdleTimerParams> mActiveIdleTimers = Maps.newHashMap();

    private volatile boolean mFirewallEnabled;
    private volatile boolean mStrictEnabled;

    private boolean mMobileActivityFromRadio = false;
    private int mLastPowerStateFromRadio = DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
    private int mLastPowerStateFromWifi = DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;

    private final RemoteCallbackList<INetworkActivityListener> mNetworkActivityListeners =
            new RemoteCallbackList<>();
    private boolean mNetworkActive;

    /* map keys used by netd to keep per app interface restrictions
     * separate for each use case.
     */
    private static final String RESTRICT_USECASE_CELL = "cell";
    private static final String RESTRICT_USECASE_CELLULAR = "cellular";
    private static final String RESTRICT_USECASE_VPN  = "vpn";
    private static final String RESTRICT_USECASE_WIFI = "wifi";

    // Helper class for managing per uid interface blacklists.
    private static class RestrictIf {
        // Use case string
        public String useCase;
        // Interface name
        public String ifName;
        // NetworkCapabilities transport type used for this blacklist
        public int transport;
        // Active uid blacklist
        public SparseBooleanArray active;
        // Desired uid blacklist changes
        public SparseBooleanArray pending;

        RestrictIf(String useCase, int transport) {
            this.useCase = useCase;
            this.ifName = null;
            this.transport = transport;
            this.active = new SparseBooleanArray();
            this.pending = new SparseBooleanArray();
        }
    }

    @GuardedBy("mQuotaLock")
    private RestrictIf[] mRestrictIf = {
            // Ordered by match preference (in the event we get a callback with
            // multiple transports).
            new RestrictIf(RESTRICT_USECASE_VPN, NetworkCapabilities.TRANSPORT_VPN),
            new RestrictIf(RESTRICT_USECASE_CELL, NetworkCapabilities.TRANSPORT_CELLULAR),
            new RestrictIf(RESTRICT_USECASE_CELLULAR, NetworkCapabilities.TRANSPORT_CELLULAR),
            new RestrictIf(RESTRICT_USECASE_WIFI, NetworkCapabilities.TRANSPORT_WIFI),
    };

    private RestrictIf getUseCaseRestrictIf(String useCase) {
        for (RestrictIf restrictIf : mRestrictIf) {
            if (restrictIf.useCase.equals(useCase)) {
                return restrictIf;
            }
        }
        throw new IllegalStateException("Unknown interface restriction");
    }

    private final HashMap<Network, NetworkCapabilities> mNetworkCapabilitiesMap = new HashMap<>();

    /**
     * Constructs a new NetworkManagementService instance
     *
     * @param context  Binder context for this service
     */
    private NetworkManagementService(
            Context context, SystemServices services) {
        mContext = context;
        mServices = services;

        mDaemonHandler = new Handler(FgThread.get().getLooper());

        mNetdUnsolicitedEventListener = new NetdUnsolicitedEventListener();

        mServices.registerLocalService(new LocalService());

        synchronized (mTetheringStatsProviders) {
            mTetheringStatsProviders.put(new NetdTetheringStatsProvider(), "netd");
        }
    }

    @VisibleForTesting
    NetworkManagementService() {
        mContext = null;
        mDaemonHandler = null;
        mServices = null;
        mNetdUnsolicitedEventListener = null;
    }

    static NetworkManagementService create(Context context, SystemServices services)
            throws InterruptedException {
        final NetworkManagementService service =
                new NetworkManagementService(context, services);
        if (DBG) Slog.d(TAG, "Creating NetworkManagementService");
        if (DBG) Slog.d(TAG, "Connecting native netd service");
        service.connectNativeNetdService();
        if (DBG) Slog.d(TAG, "Connected");
        return service;
    }

    public static NetworkManagementService create(Context context) throws InterruptedException {
        return create(context, new SystemServices());
    }

    public void systemReady() {
        final ConnectivityManager mConnectivityManager =
                mContext.getSystemService(ConnectivityManager.class);

        final NetworkRequest.Builder builder = new NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        for (RestrictIf restrictIf : mRestrictIf) {
            builder.addTransportType(restrictIf.transport);
        }
        final NetworkRequest request = builder.build();

        final ConnectivityManager.NetworkCallback mNetworkCallback =
                new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network network,
                    NetworkCapabilities networkCapabilities) {
                mNetworkCapabilitiesMap.put(network, networkCapabilities);
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                // Callback ordering in Oreo+ is documented to be:
                // onCapabilitiesChanged, onLinkPropertiesChanged
                // At this point, we should always find the network in our
                // local map but guard anyway.
                NetworkCapabilities nc = mNetworkCapabilitiesMap.get(network);
                if (nc == null) {
                    Slog.e(TAG, "onLinkPropertiesChanged: network was not in map: "
                            + "network=" + network + " linkProperties=" + linkProperties);
                    return;
                }
                RestrictIf matchedRestrictIf = null;
                for (RestrictIf restrictIf : mRestrictIf) {
                    if (nc.hasTransport(restrictIf.transport)) {
                        matchedRestrictIf = restrictIf;
                        break;
                    }
                }
                if (matchedRestrictIf == null) {
                    return;
                }
                final String iface = linkProperties.getInterfaceName();
                if (TextUtils.isEmpty(iface)) {
                    return;
                }
                // The post below requires final arguments so
                final RestrictIf finalRestrictIf = matchedRestrictIf;
                // Exit the callback ASAP and move further work onto daemon thread
                mDaemonHandler.post(() ->
                        updateAppOnInterfaceCallback(finalRestrictIf, iface));
            }

            @Override
            public void onLost(Network network) {
                mNetworkCapabilitiesMap.remove(network);
            }
        };

        mConnectivityManager.registerNetworkCallback(request, mNetworkCallback);

        if (DBG) {
            final long start = System.currentTimeMillis();
            prepareNativeDaemon();
            final long delta = System.currentTimeMillis() - start;
            Slog.d(TAG, "Prepared in " + delta + "ms");
            return;
        } else {
            prepareNativeDaemon();
        }
    }

    private IBatteryStats getBatteryStats() {
        synchronized (this) {
            if (mBatteryStats != null) {
                return mBatteryStats;
            }
            mBatteryStats =
                    IBatteryStats.Stub.asInterface(mServices.getService(BatteryStats.SERVICE_NAME));
            return mBatteryStats;
        }
    }

    @Override
    public void registerObserver(INetworkManagementEventObserver observer) {
        NetworkStack.checkNetworkStackPermission(mContext);
        mObservers.register(observer);
    }

    @Override
    public void unregisterObserver(INetworkManagementEventObserver observer) {
        NetworkStack.checkNetworkStackPermission(mContext);
        mObservers.unregister(observer);
    }

    @FunctionalInterface
    private interface NetworkManagementEventCallback {
        public void sendCallback(INetworkManagementEventObserver o) throws RemoteException;
    }

    private void invokeForAllObservers(NetworkManagementEventCallback eventCallback) {
        final int length = mObservers.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                try {
                    eventCallback.sendCallback(mObservers.getBroadcastItem(i));
                } catch (RemoteException | RuntimeException e) {
                }
            }
        } finally {
            mObservers.finishBroadcast();
        }
    }

    /**
     * Notify our observers of an interface status change
     */
    private void notifyInterfaceStatusChanged(String iface, boolean up) {
        invokeForAllObservers(o -> o.interfaceStatusChanged(iface, up));
    }

    /**
     * Notify our observers of an interface link state change
     * (typically, an Ethernet cable has been plugged-in or unplugged).
     */
    private void notifyInterfaceLinkStateChanged(String iface, boolean up) {
        invokeForAllObservers(o -> o.interfaceLinkStateChanged(iface, up));
    }

    /**
     * Notify our observers of an interface addition.
     */
    private void notifyInterfaceAdded(String iface) {
        invokeForAllObservers(o -> o.interfaceAdded(iface));
    }

    /**
     * Notify our observers of an interface removal.
     */
    private void notifyInterfaceRemoved(String iface) {
        // netd already clears out quota and alerts for removed ifaces; update
        // our sanity-checking state.
        mActiveAlerts.remove(iface);
        mActiveQuotas.remove(iface);
        invokeForAllObservers(o -> o.interfaceRemoved(iface));
    }

    /**
     * Notify our observers of a limit reached.
     */
    private void notifyLimitReached(String limitName, String iface) {
        invokeForAllObservers(o -> o.limitReached(limitName, iface));
    }

    /**
     * Notify our observers of a change in the data activity state of the interface
     */
    private void notifyInterfaceClassActivity(int type, boolean isActive, long tsNanos,
            int uid, boolean fromRadio) {
        final boolean isMobile = ConnectivityManager.isNetworkTypeMobile(type);
        int powerState = isActive
                ? DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH
                : DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
        if (isMobile) {
            if (!fromRadio) {
                if (mMobileActivityFromRadio) {
                    // If this call is not coming from a report from the radio itself, but we
                    // have previously received reports from the radio, then we will take the
                    // power state to just be whatever the radio last reported.
                    powerState = mLastPowerStateFromRadio;
                }
            } else {
                mMobileActivityFromRadio = true;
            }
            if (mLastPowerStateFromRadio != powerState) {
                mLastPowerStateFromRadio = powerState;
                try {
                    getBatteryStats().noteMobileRadioPowerState(powerState, tsNanos, uid);
                } catch (RemoteException e) {
                }
                FrameworkStatsLog.write_non_chained(
                        FrameworkStatsLog.MOBILE_RADIO_POWER_STATE_CHANGED, uid, null, powerState);
            }
        }

        if (ConnectivityManager.isNetworkTypeWifi(type)) {
            if (mLastPowerStateFromWifi != powerState) {
                mLastPowerStateFromWifi = powerState;
                try {
                    getBatteryStats().noteWifiRadioPowerState(powerState, tsNanos, uid);
                } catch (RemoteException e) {
                }
                FrameworkStatsLog.write_non_chained(
                        FrameworkStatsLog.WIFI_RADIO_POWER_STATE_CHANGED, uid, null, powerState);
            }
        }

        if (!isMobile || fromRadio || !mMobileActivityFromRadio) {
            // Report the change in data activity.  We don't do this if this is a change
            // on the mobile network, that is not coming from the radio itself, and we
            // have previously seen change reports from the radio.  In that case only
            // the radio is the authority for the current state.
            final boolean active = isActive;
            invokeForAllObservers(o -> o.interfaceClassDataActivityChanged(
                    Integer.toString(type), active, tsNanos));
        }

        boolean report = false;
        synchronized (mIdleTimerLock) {
            if (mActiveIdleTimers.isEmpty()) {
                // If there are no idle timers, we are not monitoring activity, so we
                // are always considered active.
                isActive = true;
            }
            if (mNetworkActive != isActive) {
                mNetworkActive = isActive;
                report = isActive;
            }
        }
        if (report) {
            reportNetworkActive();
        }
    }

    @Override
    public void registerTetheringStatsProvider(ITetheringStatsProvider provider, String name) {
        NetworkStack.checkNetworkStackPermission(mContext);
        Objects.requireNonNull(provider);
        synchronized(mTetheringStatsProviders) {
            mTetheringStatsProviders.put(provider, name);
        }
    }

    @Override
    public void unregisterTetheringStatsProvider(ITetheringStatsProvider provider) {
        NetworkStack.checkNetworkStackPermission(mContext);
        synchronized(mTetheringStatsProviders) {
            mTetheringStatsProviders.remove(provider);
        }
    }

    @Override
    public void tetherLimitReached(ITetheringStatsProvider provider) {
        NetworkStack.checkNetworkStackPermission(mContext);
        synchronized(mTetheringStatsProviders) {
            if (!mTetheringStatsProviders.containsKey(provider)) {
                return;
            }
            // No current code examines the interface parameter in a global alert. Just pass null.
            mDaemonHandler.post(() -> notifyLimitReached(LIMIT_GLOBAL_ALERT, null));
        }
    }

    // Sync the state of the given chain with the native daemon.
    private void syncFirewallChainLocked(int chain, String name) {
        SparseIntArray rules;
        synchronized (mRulesLock) {
            final SparseIntArray uidFirewallRules = getUidFirewallRulesLR(chain);
            // Make a copy of the current rules, and then clear them. This is because
            // setFirewallUidRuleInternal only pushes down rules to the native daemon if they
            // are different from the current rules stored in the mUidFirewall*Rules array for
            // the specified chain. If we don't clear the rules, setFirewallUidRuleInternal
            // will do nothing.
            rules = uidFirewallRules.clone();
            uidFirewallRules.clear();
        }
        if (rules.size() > 0) {
            // Now push the rules. setFirewallUidRuleInternal will push each of these down to the
            // native daemon, and also add them to the mUidFirewall*Rules array for the specified
            // chain.
            if (DBG) Slog.d(TAG, "Pushing " + rules.size() + " active firewall "
                    + name + "UID rules");
            for (int i = 0; i < rules.size(); i++) {
                setFirewallUidRuleLocked(chain, rules.keyAt(i), rules.valueAt(i));
            }
        }
    }

    private void connectNativeNetdService() {
        mNetdService = mServices.getNetd();
        try {
            mNetdService.registerUnsolicitedEventListener(mNetdUnsolicitedEventListener);
            if (DBG) Slog.d(TAG, "Register unsolicited event listener");
        } catch (RemoteException | ServiceSpecificException e) {
            Slog.e(TAG, "Failed to set Netd unsolicited event listener " + e);
        }
    }

    /**
     * Prepare native daemon once connected, enabling modules and pushing any
     * existing in-memory rules.
     */
    private void prepareNativeDaemon() {

        // push any existing quota or UID rules
        synchronized (mQuotaLock) {

            // Netd unconditionally enable bandwidth control
            SystemProperties.set(PROP_QTAGUID_ENABLED, "1");

            mStrictEnabled = true;

            setDataSaverModeEnabled(mDataSaverMode);

            int size = mActiveQuotas.size();
            if (size > 0) {
                if (DBG) Slog.d(TAG, "Pushing " + size + " active quota rules");
                final HashMap<String, Long> activeQuotas = mActiveQuotas;
                mActiveQuotas = Maps.newHashMap();
                for (Map.Entry<String, Long> entry : activeQuotas.entrySet()) {
                    setInterfaceQuota(entry.getKey(), entry.getValue());
                }
            }

            size = mActiveAlerts.size();
            if (size > 0) {
                if (DBG) Slog.d(TAG, "Pushing " + size + " active alert rules");
                final HashMap<String, Long> activeAlerts = mActiveAlerts;
                mActiveAlerts = Maps.newHashMap();
                for (Map.Entry<String, Long> entry : activeAlerts.entrySet()) {
                    setInterfaceAlert(entry.getKey(), entry.getValue());
                }
            }

            SparseBooleanArray uidRejectOnQuota = null;
            SparseBooleanArray uidAcceptOnQuota = null;
            synchronized (mRulesLock) {
                size = mUidRejectOnMetered.size();
                if (size > 0) {
                    if (DBG) Slog.d(TAG, "Pushing " + size + " UIDs to metered blacklist rules");
                    uidRejectOnQuota = mUidRejectOnMetered;
                    mUidRejectOnMetered = new SparseBooleanArray();
                }

                size = mUidAllowOnMetered.size();
                if (size > 0) {
                    if (DBG) Slog.d(TAG, "Pushing " + size + " UIDs to metered whitelist rules");
                    uidAcceptOnQuota = mUidAllowOnMetered;
                    mUidAllowOnMetered = new SparseBooleanArray();
                }
            }
            if (uidRejectOnQuota != null) {
                for (int i = 0; i < uidRejectOnQuota.size(); i++) {
                    setUidMeteredNetworkBlacklist(uidRejectOnQuota.keyAt(i),
                            uidRejectOnQuota.valueAt(i));
                }
            }
            if (uidAcceptOnQuota != null) {
                for (int i = 0; i < uidAcceptOnQuota.size(); i++) {
                    setUidMeteredNetworkWhitelist(uidAcceptOnQuota.keyAt(i),
                            uidAcceptOnQuota.valueAt(i));
                }
            }

            size = mUidCleartextPolicy.size();
            if (size > 0) {
                if (DBG) Slog.d(TAG, "Pushing " + size + " active UID cleartext policies");
                final SparseIntArray local = mUidCleartextPolicy;
                mUidCleartextPolicy = new SparseIntArray();
                for (int i = 0; i < local.size(); i++) {
                    setUidCleartextNetworkPolicy(local.keyAt(i), local.valueAt(i));
                }
            }

            setFirewallEnabled(mFirewallEnabled);

            syncFirewallChainLocked(FIREWALL_CHAIN_NONE, "");
            syncFirewallChainLocked(FIREWALL_CHAIN_STANDBY, "standby ");
            syncFirewallChainLocked(FIREWALL_CHAIN_DOZABLE, "dozable ");
            syncFirewallChainLocked(FIREWALL_CHAIN_POWERSAVE, "powersave ");
            syncFirewallChainLocked(FIREWALL_CHAIN_ISOLATED, "isolated ");

            final int[] chains =
                    {FIREWALL_CHAIN_STANDBY, FIREWALL_CHAIN_DOZABLE, FIREWALL_CHAIN_POWERSAVE,
                    FIREWALL_CHAIN_ISOLATED};
            for (int chain : chains) {
                if (getFirewallChainState(chain)) {
                    setFirewallChainEnabled(chain, true);
                }
            }
        }


        try {
            getBatteryStats().noteNetworkStatsEnabled();
        } catch (RemoteException e) {
        }

    }

    /**
     * Notify our observers of a new or updated interface address.
     */
    private void notifyAddressUpdated(String iface, LinkAddress address) {
        invokeForAllObservers(o -> o.addressUpdated(iface, address));
    }

    /**
     * Notify our observers of a deleted interface address.
     */
    private void notifyAddressRemoved(String iface, LinkAddress address) {
        invokeForAllObservers(o -> o.addressRemoved(iface, address));
    }

    /**
     * Notify our observers of DNS server information received.
     */
    private void notifyInterfaceDnsServerInfo(String iface, long lifetime, String[] addresses) {
        invokeForAllObservers(o -> o.interfaceDnsServerInfo(iface, lifetime, addresses));
    }

    /**
     * Notify our observers of a route change.
     */
    private void notifyRouteChange(boolean updated, RouteInfo route) {
        if (updated) {
            invokeForAllObservers(o -> o.routeUpdated(route));
        } else {
            invokeForAllObservers(o -> o.routeRemoved(route));
        }
    }

    private class NetdUnsolicitedEventListener extends INetdUnsolicitedEventListener.Stub {
        @Override
        public void onInterfaceClassActivityChanged(boolean isActive,
                int label, long timestamp, int uid) throws RemoteException {
            final long timestampNanos;
            if (timestamp <= 0) {
                timestampNanos = SystemClock.elapsedRealtimeNanos();
            } else {
                timestampNanos = timestamp;
            }
            mDaemonHandler.post(() ->
                    notifyInterfaceClassActivity(label, isActive, timestampNanos, uid, false));
        }

        @Override
        public void onQuotaLimitReached(String alertName, String ifName)
                throws RemoteException {
            mDaemonHandler.post(() -> notifyLimitReached(alertName, ifName));
        }

        @Override
        public void onInterfaceDnsServerInfo(String ifName,
                long lifetime, String[] servers) throws RemoteException {
            mDaemonHandler.post(() -> notifyInterfaceDnsServerInfo(ifName, lifetime, servers));
        }

        @Override
        public void onInterfaceAddressUpdated(String addr,
                String ifName, int flags, int scope) throws RemoteException {
            final LinkAddress address = new LinkAddress(addr, flags, scope);
            mDaemonHandler.post(() -> notifyAddressUpdated(ifName, address));
        }

        @Override
        public void onInterfaceAddressRemoved(String addr,
                String ifName, int flags, int scope) throws RemoteException {
            final LinkAddress address = new LinkAddress(addr, flags, scope);
            mDaemonHandler.post(() -> notifyAddressRemoved(ifName, address));
        }

        @Override
        public void onInterfaceAdded(String ifName) throws RemoteException {
            mDaemonHandler.post(() -> notifyInterfaceAdded(ifName));
        }

        @Override
        public void onInterfaceRemoved(String ifName) throws RemoteException {
            mDaemonHandler.post(() -> notifyInterfaceRemoved(ifName));
        }

        @Override
        public void onInterfaceChanged(String ifName, boolean up)
                throws RemoteException {
            mDaemonHandler.post(() -> notifyInterfaceStatusChanged(ifName, up));
        }

        @Override
        public void onInterfaceLinkStateChanged(String ifName, boolean up)
                throws RemoteException {
            mDaemonHandler.post(() -> notifyInterfaceLinkStateChanged(ifName, up));
        }

        @Override
        public void onRouteChanged(boolean updated,
                String route, String gateway, String ifName) throws RemoteException {
            final RouteInfo processRoute = new RouteInfo(new IpPrefix(route),
                    ("".equals(gateway)) ? null : InetAddresses.parseNumericAddress(gateway),
                    ifName);
            mDaemonHandler.post(() -> notifyRouteChange(updated, processRoute));
        }

        @Override
        public void onStrictCleartextDetected(int uid, String hex) throws RemoteException {
            // Don't need to post to mDaemonHandler because the only thing
            // that notifyCleartextNetwork does is post to a handler
            ActivityManager.getService().notifyCleartextNetwork(uid,
                    HexDump.hexStringToByteArray(hex));
        }

        @Override
        public int getInterfaceVersion() {
            return INetdUnsolicitedEventListener.VERSION;
        }

        @Override
        public String getInterfaceHash() {
            return INetdUnsolicitedEventListener.HASH;
        }
    }

    //
    // INetworkManagementService members
    //
    @Override
    public String[] listInterfaces() {
        // TODO: Remove CONNECTIVITY_INTERNAL after bluetooth tethering has no longer called these
        //  APIs.
        NetworkStack.checkNetworkStackPermissionOr(mContext, CONNECTIVITY_INTERNAL);
        try {
            return mNetdService.interfaceGetList();
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Convert InterfaceConfiguration to InterfaceConfigurationParcel with given ifname.
     */
    private static InterfaceConfigurationParcel toStableParcel(InterfaceConfiguration cfg,
            String iface) {
        InterfaceConfigurationParcel cfgParcel = new InterfaceConfigurationParcel();
        cfgParcel.ifName = iface;
        String hwAddr = cfg.getHardwareAddress();
        if (!TextUtils.isEmpty(hwAddr)) {
            cfgParcel.hwAddr = hwAddr;
        } else {
            cfgParcel.hwAddr = "";
        }
        cfgParcel.ipv4Addr = cfg.getLinkAddress().getAddress().getHostAddress();
        cfgParcel.prefixLength = cfg.getLinkAddress().getPrefixLength();
        ArrayList<String> flags = new ArrayList<>();
        for (String flag : cfg.getFlags()) {
            flags.add(flag);
        }
        cfgParcel.flags = flags.toArray(new String[0]);

        return cfgParcel;
    }

    /**
     * Construct InterfaceConfiguration from InterfaceConfigurationParcel.
     */
    public static InterfaceConfiguration fromStableParcel(InterfaceConfigurationParcel p) {
        InterfaceConfiguration cfg = new InterfaceConfiguration();
        cfg.setHardwareAddress(p.hwAddr);

        final InetAddress addr = NetworkUtils.numericToInetAddress(p.ipv4Addr);
        cfg.setLinkAddress(new LinkAddress(addr, p.prefixLength));
        for (String flag : p.flags) {
            cfg.setFlag(flag);
        }

        return cfg;
    }

    @Override
    public InterfaceConfiguration getInterfaceConfig(String iface) {
        // TODO: Remove CONNECTIVITY_INTERNAL after bluetooth tethering has no longer called these
        //  APIs.
        NetworkStack.checkNetworkStackPermissionOr(mContext, CONNECTIVITY_INTERNAL);
        final InterfaceConfigurationParcel result;
        try {
            result = mNetdService.interfaceGetCfg(iface);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }

        try {
            final InterfaceConfiguration cfg = fromStableParcel(result);
            return cfg;
        } catch (IllegalArgumentException iae) {
            throw new IllegalStateException("Invalid InterfaceConfigurationParcel", iae);
        }
    }

    @Override
    public void setInterfaceConfig(String iface, InterfaceConfiguration cfg) {
        // TODO: Remove CONNECTIVITY_INTERNAL after bluetooth tethering has no longer called these
        //  APIs.
        NetworkStack.checkNetworkStackPermissionOr(mContext, CONNECTIVITY_INTERNAL);
        LinkAddress linkAddr = cfg.getLinkAddress();
        if (linkAddr == null || linkAddr.getAddress() == null) {
            throw new IllegalStateException("Null LinkAddress given");
        }

        final InterfaceConfigurationParcel cfgParcel = toStableParcel(cfg, iface);

        try {
            mNetdService.interfaceSetCfg(cfgParcel);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void setInterfaceDown(String iface) {
        NetworkStack.checkNetworkStackPermission(mContext);
        final InterfaceConfiguration ifcg = getInterfaceConfig(iface);
        ifcg.setInterfaceDown();
        setInterfaceConfig(iface, ifcg);
    }

    @Override
    public void setInterfaceUp(String iface) {
        NetworkStack.checkNetworkStackPermission(mContext);
        final InterfaceConfiguration ifcg = getInterfaceConfig(iface);
        ifcg.setInterfaceUp();
        setInterfaceConfig(iface, ifcg);
    }

    @Override
    public void setInterfaceIpv6PrivacyExtensions(String iface, boolean enable) {
        NetworkStack.checkNetworkStackPermission(mContext);
        try {
            mNetdService.interfaceSetIPv6PrivacyExtensions(iface, enable);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    /* TODO: This is right now a IPv4 only function. Works for wifi which loses its
       IPv6 addresses on interface down, but we need to do full clean up here */
    @Override
    public void clearInterfaceAddresses(String iface) {
        NetworkStack.checkNetworkStackPermission(mContext);
        try {
            mNetdService.interfaceClearAddrs(iface);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void enableIpv6(String iface) {
        NetworkStack.checkNetworkStackPermission(mContext);
        try {
            mNetdService.interfaceSetEnableIPv6(iface, true);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void setIPv6AddrGenMode(String iface, int mode) throws ServiceSpecificException {
        NetworkStack.checkNetworkStackPermission(mContext);
        try {
            mNetdService.setIPv6AddrGenMode(iface, mode);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public void disableIpv6(String iface) {
        NetworkStack.checkNetworkStackPermission(mContext);
        try {
            mNetdService.interfaceSetEnableIPv6(iface, false);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void addRoute(int netId, RouteInfo route) {
        NetworkStack.checkNetworkStackPermission(mContext);
        RouteUtils.modifyRoute(mNetdService, ModifyOperation.ADD, netId, route);
    }

    @Override
    public void removeRoute(int netId, RouteInfo route) {
        NetworkStack.checkNetworkStackPermission(mContext);
        RouteUtils.modifyRoute(mNetdService, ModifyOperation.REMOVE, netId, route);
    }

    private ArrayList<String> readRouteList(String filename) {
        FileInputStream fstream = null;
        ArrayList<String> list = new ArrayList<>();

        try {
            fstream = new FileInputStream(filename);
            DataInputStream in = new DataInputStream(fstream);
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            String s;

            // throw away the title line

            while (((s = br.readLine()) != null) && (s.length() != 0)) {
                list.add(s);
            }
        } catch (IOException ex) {
            // return current list, possibly empty
        } finally {
            if (fstream != null) {
                try {
                    fstream.close();
                } catch (IOException ex) {}
            }
        }

        return list;
    }

    @Override
    public void setMtu(String iface, int mtu) {
        NetworkStack.checkNetworkStackPermission(mContext);

        try {
            mNetdService.interfaceSetMtu(iface, mtu);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void shutdown() {
        // TODO: remove from aidl if nobody calls externally
        mContext.enforceCallingOrSelfPermission(SHUTDOWN, TAG);

        Slog.i(TAG, "Shutting down");
    }

    @Override
    public boolean getIpForwardingEnabled() throws IllegalStateException{
        NetworkStack.checkNetworkStackPermission(mContext);

        try {
            final boolean isEnabled = mNetdService.ipfwdEnabled();
            return isEnabled;
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void setIpForwardingEnabled(boolean enable) {
        NetworkStack.checkNetworkStackPermission(mContext);
        try {
            if (enable) {
                mNetdService.ipfwdEnableForwarding("tethering");
            } else {
                mNetdService.ipfwdDisableForwarding("tethering");
            }
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void startTethering(String[] dhcpRange) {
        startTetheringWithConfiguration(true, dhcpRange);
    }

    @Override
    public void startTetheringWithConfiguration(boolean usingLegacyDnsProxy, String[] dhcpRange) {
        NetworkStack.checkNetworkStackPermission(mContext);
        try {
            NetdUtils.tetherStart(mNetdService, usingLegacyDnsProxy, dhcpRange);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void stopTethering() {
        NetworkStack.checkNetworkStackPermission(mContext);
        try {
            mNetdService.tetherStop();
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean isTetheringStarted() {
        NetworkStack.checkNetworkStackPermission(mContext);

        try {
            final boolean isEnabled = mNetdService.tetherIsEnabled();
            return isEnabled;
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void tetherInterface(String iface) {
        NetworkStack.checkNetworkStackPermission(mContext);
        try {
            final LinkAddress addr = getInterfaceConfig(iface).getLinkAddress();
            final IpPrefix dest = new IpPrefix(addr.getAddress(), addr.getPrefixLength());
            NetdUtils.tetherInterface(mNetdService, iface, dest);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void untetherInterface(String iface) {
        NetworkStack.checkNetworkStackPermission(mContext);
        try {
            NetdUtils.untetherInterface(mNetdService, iface);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String[] listTetheredInterfaces() {
        NetworkStack.checkNetworkStackPermission(mContext);
        try {
            return mNetdService.tetherInterfaceList();
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void setDnsForwarders(Network network, String[] dns) {
        NetworkStack.checkNetworkStackPermission(mContext);

        int netId = (network != null) ? network.netId : ConnectivityManager.NETID_UNSET;

        try {
            mNetdService.tetherDnsSet(netId, dns);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String[] getDnsForwarders() {
        NetworkStack.checkNetworkStackPermission(mContext);
        try {
            return mNetdService.tetherDnsList();
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<InterfaceAddress> excludeLinkLocal(List<InterfaceAddress> addresses) {
        ArrayList<InterfaceAddress> filtered = new ArrayList<>(addresses.size());
        for (InterfaceAddress ia : addresses) {
            if (!ia.getAddress().isLinkLocalAddress())
                filtered.add(ia);
        }
        return filtered;
    }

    private void modifyInterfaceForward(boolean add, String fromIface, String toIface) {
        try {
            if (add) {
                mNetdService.ipfwdAddInterfaceForward(fromIface, toIface);
            } else {
                mNetdService.ipfwdRemoveInterfaceForward(fromIface, toIface);
            }
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void startInterfaceForwarding(String fromIface, String toIface) {
        NetworkStack.checkNetworkStackPermission(mContext);
        modifyInterfaceForward(true, fromIface, toIface);
    }

    @Override
    public void stopInterfaceForwarding(String fromIface, String toIface) {
        NetworkStack.checkNetworkStackPermission(mContext);
        modifyInterfaceForward(false, fromIface, toIface);
    }

    @Override
    public void enableNat(String internalInterface, String externalInterface) {
        NetworkStack.checkNetworkStackPermission(mContext);
        try {
            mNetdService.tetherAddForward(internalInterface, externalInterface);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void disableNat(String internalInterface, String externalInterface) {
        NetworkStack.checkNetworkStackPermission(mContext);
        try {
            mNetdService.tetherRemoveForward(internalInterface, externalInterface);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void addIdleTimer(String iface, int timeout, final int type) {
        NetworkStack.checkNetworkStackPermission(mContext);

        if (DBG) Slog.d(TAG, "Adding idletimer");

        synchronized (mIdleTimerLock) {
            IdleTimerParams params = mActiveIdleTimers.get(iface);
            if (params != null) {
                // the interface already has idletimer, update network count
                params.networkCount++;
                return;
            }

            try {
                mNetdService.idletimerAddInterface(iface, timeout, Integer.toString(type));
            } catch (RemoteException | ServiceSpecificException e) {
                throw new IllegalStateException(e);
            }
            mActiveIdleTimers.put(iface, new IdleTimerParams(timeout, type));

            // Networks start up.
            if (ConnectivityManager.isNetworkTypeMobile(type)) {
                mNetworkActive = false;
            }
            mDaemonHandler.post(() -> notifyInterfaceClassActivity(type, true,
                    SystemClock.elapsedRealtimeNanos(), -1, false));
        }
    }

    @Override
    public void removeIdleTimer(String iface) {
        NetworkStack.checkNetworkStackPermission(mContext);

        if (DBG) Slog.d(TAG, "Removing idletimer");

        synchronized (mIdleTimerLock) {
            final IdleTimerParams params = mActiveIdleTimers.get(iface);
            if (params == null || --(params.networkCount) > 0) {
                return;
            }

            try {
                mNetdService.idletimerRemoveInterface(iface,
                        params.timeout, Integer.toString(params.type));
            } catch (RemoteException | ServiceSpecificException e) {
                throw new IllegalStateException(e);
            }
            mActiveIdleTimers.remove(iface);
            mDaemonHandler.post(() -> notifyInterfaceClassActivity(params.type, false,
                    SystemClock.elapsedRealtimeNanos(), -1, false));
        }
    }

    @Override
    public void setInterfaceQuota(String iface, long quotaBytes) {
        NetworkStack.checkNetworkStackPermission(mContext);

        synchronized (mQuotaLock) {
            if (mActiveQuotas.containsKey(iface)) {
                throw new IllegalStateException("iface " + iface + " already has quota");
            }

            try {
                // TODO: support quota shared across interfaces
                mNetdService.bandwidthSetInterfaceQuota(iface, quotaBytes);

                mActiveQuotas.put(iface, quotaBytes);
            } catch (RemoteException | ServiceSpecificException e) {
                throw new IllegalStateException(e);
            }

            synchronized (mTetheringStatsProviders) {
                for (ITetheringStatsProvider provider : mTetheringStatsProviders.keySet()) {
                    try {
                        provider.setInterfaceQuota(iface, quotaBytes);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Problem setting tethering data limit on provider " +
                                mTetheringStatsProviders.get(provider) + ": " + e);
                    }
                }
            }
        }
    }

    @Override
    public void removeInterfaceQuota(String iface) {
        NetworkStack.checkNetworkStackPermission(mContext);

        synchronized (mQuotaLock) {
            if (!mActiveQuotas.containsKey(iface)) {
                // TODO: eventually consider throwing
                return;
            }

            mActiveQuotas.remove(iface);
            mActiveAlerts.remove(iface);

            try {
                // TODO: support quota shared across interfaces
                mNetdService.bandwidthRemoveInterfaceQuota(iface);
            } catch (RemoteException | ServiceSpecificException e) {
                throw new IllegalStateException(e);
            }

            synchronized (mTetheringStatsProviders) {
                for (ITetheringStatsProvider provider : mTetheringStatsProviders.keySet()) {
                    try {
                        provider.setInterfaceQuota(iface, ITetheringStatsProvider.QUOTA_UNLIMITED);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Problem removing tethering data limit on provider " +
                                mTetheringStatsProviders.get(provider) + ": " + e);
                    }
                }
            }
        }
    }

    @Override
    public void setInterfaceAlert(String iface, long alertBytes) {
        NetworkStack.checkNetworkStackPermission(mContext);

        // quick sanity check
        if (!mActiveQuotas.containsKey(iface)) {
            throw new IllegalStateException("setting alert requires existing quota on iface");
        }

        synchronized (mQuotaLock) {
            if (mActiveAlerts.containsKey(iface)) {
                throw new IllegalStateException("iface " + iface + " already has alert");
            }

            try {
                // TODO: support alert shared across interfaces
                mNetdService.bandwidthSetInterfaceAlert(iface, alertBytes);
                mActiveAlerts.put(iface, alertBytes);
            } catch (RemoteException | ServiceSpecificException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Override
    public void removeInterfaceAlert(String iface) {
        NetworkStack.checkNetworkStackPermission(mContext);

        synchronized (mQuotaLock) {
            if (!mActiveAlerts.containsKey(iface)) {
                // TODO: eventually consider throwing
                return;
            }

            try {
                // TODO: support alert shared across interfaces
                mNetdService.bandwidthRemoveInterfaceAlert(iface);
                mActiveAlerts.remove(iface);
            } catch (RemoteException | ServiceSpecificException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Override
    public void setGlobalAlert(long alertBytes) {
        NetworkStack.checkNetworkStackPermission(mContext);

        try {
            mNetdService.bandwidthSetGlobalAlert(alertBytes);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    private void setUidOnMeteredNetworkList(int uid, boolean blacklist, boolean enable) {
        NetworkStack.checkNetworkStackPermission(mContext);

        synchronized (mQuotaLock) {
            boolean oldEnable;
            SparseBooleanArray quotaList;
            synchronized (mRulesLock) {
                quotaList = blacklist ? mUidRejectOnMetered : mUidAllowOnMetered;
                oldEnable = quotaList.get(uid, false);
            }
            if (oldEnable == enable) {
                // TODO: eventually consider throwing
                return;
            }

            Trace.traceBegin(Trace.TRACE_TAG_NETWORK, "inetd bandwidth");
            try {
                if (blacklist) {
                    if (enable) {
                        mNetdService.bandwidthAddNaughtyApp(uid);
                    } else {
                        mNetdService.bandwidthRemoveNaughtyApp(uid);
                    }
                } else {
                    if (enable) {
                        mNetdService.bandwidthAddNiceApp(uid);
                    } else {
                        mNetdService.bandwidthRemoveNiceApp(uid);
                    }
                }
                synchronized (mRulesLock) {
                    if (enable) {
                        quotaList.put(uid, true);
                    } else {
                        quotaList.delete(uid);
                    }
                }
            } catch (RemoteException | ServiceSpecificException e) {
                throw new IllegalStateException(e);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
            }
        }
    }

    @Override
    public void setUidMeteredNetworkBlacklist(int uid, boolean enable) {
        setUidOnMeteredNetworkList(uid, true, enable);
    }

    @Override
    public void setUidMeteredNetworkWhitelist(int uid, boolean enable) {
        setUidOnMeteredNetworkList(uid, false, enable);
    }

    @Override
    public boolean setDataSaverModeEnabled(boolean enable) {
        mContext.enforceCallingOrSelfPermission(NETWORK_SETTINGS, TAG);

        if (DBG) Log.d(TAG, "setDataSaverMode: " + enable);
        synchronized (mQuotaLock) {
            if (mDataSaverMode == enable) {
                Log.w(TAG, "setDataSaverMode(): already " + mDataSaverMode);
                return true;
            }
            Trace.traceBegin(Trace.TRACE_TAG_NETWORK, "bandwidthEnableDataSaver");
            try {
                final boolean changed = mNetdService.bandwidthEnableDataSaver(enable);
                if (changed) {
                    mDataSaverMode = enable;
                } else {
                    Log.w(TAG, "setDataSaverMode(" + enable + "): netd command silently failed");
                }
                return changed;
            } catch (RemoteException e) {
                Log.w(TAG, "setDataSaverMode(" + enable + "): netd command failed", e);
                return false;
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
            }
        }
    }

    private static UidRangeParcel makeUidRangeParcel(int start, int stop) {
        UidRangeParcel range = new UidRangeParcel();
        range.start = start;
        range.stop = stop;
        return range;
    }

    private static UidRangeParcel[] toStableParcels(UidRange[] ranges) {
        UidRangeParcel[] stableRanges = new UidRangeParcel[ranges.length];
        for (int i = 0; i < ranges.length; i++) {
            stableRanges[i] = makeUidRangeParcel(ranges[i].start, ranges[i].stop);
        }
        return stableRanges;
    }

    private void updateAppOnInterfaceCallback(RestrictIf restrictIf, String newIface) {
        synchronized (mQuotaLock) {
            if (TextUtils.isEmpty(restrictIf.ifName)) {
                restrictIf.ifName = newIface;
            } else if (!restrictIf.ifName.equals(newIface)) { // interface name has changed
                // Prevent new incoming requests colliding with an update in progress
                for (int i = 0; i < restrictIf.active.size(); i++) {
                    final int uid = restrictIf.active.keyAt(i);
                    final boolean restrict = restrictIf.active.valueAt(i);
                    // Only remove/readd if a restriction is currently in place
                    if (!restrict) {
                        continue;
                    }
                    setAppOnInterfaceLocked(restrictIf.useCase, restrictIf.ifName, uid, false);
                    restrictIf.active.setValueAt(i, false);
                    // Use pending list to queue re-add.
                    // (Prefer keeping existing pending status if it exists.)
                    if (restrictIf.pending.indexOfKey(uid) < 0) {
                        restrictIf.pending.put(uid, true);
                    }
                }
                restrictIf.ifName = newIface;
            }
            processPendingAppOnInterfaceLocked(restrictIf);
        }
    }

    @Override
    public void restrictAppOnInterface(String useCase, int uid, boolean restrict) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        synchronized (mQuotaLock) {
            restrictAppOnInterfaceLocked(getUseCaseRestrictIf(useCase), uid, restrict);
        }
    }

    private void restrictAppOnInterfaceLocked(RestrictIf restrictIf, int uid, boolean restrict) {
        if (TextUtils.isEmpty(restrictIf.ifName)) {
            // We don't have an interface name yet so queue
            // the request for when it comes up
            restrictIf.pending.put(uid, restrict);
            return;
        }

        boolean oldValue = restrictIf.active.get(uid, false);
        if (oldValue == restrict) {
            return;
        }

        if (setAppOnInterfaceLocked(restrictIf.useCase, restrictIf.ifName, uid, restrict)) {
            restrictIf.active.put(uid, restrict);
        } else {
            // Perhaps the interface was down, queue to retry after receipt
            // of the next network callback for this network.
            restrictIf.pending.put(uid, true);
        }
    }

    private boolean setAppOnInterfaceLocked(String useCase, String ifName, int uid,
            boolean restrict) {
        boolean ok = true;
        try {
            if (restrict) {
                mNetdService.bandwidthAddRestrictAppOnInterface(useCase, ifName, uid);
            } else {
                mNetdService.bandwidthRemoveRestrictAppOnInterface(useCase, ifName, uid);
            }
        } catch (RemoteException e) {
            throw new IllegalStateException(e);
        } catch (ServiceSpecificException e) {
            // ENETDOWN is returned when the interface cannot be resolved to an index.
            // (and is only returned by bandwidthAdd... call)
            if (e.errorCode == ENETDOWN) {
                ok = false;
            } else {
                throw new IllegalStateException(e);
            }
        }
        return ok;
    }

    private void processPendingAppOnInterfaceLocked(RestrictIf restrictIf) {
        // Work on a copy of the pending list since failed add requests
        // get put back on.
        SparseBooleanArray pendingList = restrictIf.pending.clone();
        restrictIf.pending = new SparseBooleanArray();
        for (int i = 0; i < pendingList.size(); i++) {
            restrictAppOnInterfaceLocked(restrictIf, pendingList.keyAt(i), pendingList.valueAt(i));
        }
    }

    @Override
    public void setAllowOnlyVpnForUids(boolean add, UidRange[] uidRanges)
            throws ServiceSpecificException {
        NetworkStack.checkNetworkStackPermission(mContext);
        try {
            mNetdService.networkRejectNonSecureVpn(add, toStableParcels(uidRanges));
        } catch (ServiceSpecificException e) {
            Log.w(TAG, "setAllowOnlyVpnForUids(" + add + ", " + Arrays.toString(uidRanges) + ")"
                    + ": netd command failed", e);
            throw e;
        } catch (RemoteException e) {
            Log.w(TAG, "setAllowOnlyVpnForUids(" + add + ", " + Arrays.toString(uidRanges) + ")"
                    + ": netd command failed", e);
            throw e.rethrowAsRuntimeException();
        }
    }

    private void applyUidCleartextNetworkPolicy(int uid, int policy) {
        final int policyValue;
        switch (policy) {
            case StrictMode.NETWORK_POLICY_ACCEPT:
                policyValue = INetd.PENALTY_POLICY_ACCEPT;
                break;
            case StrictMode.NETWORK_POLICY_LOG:
                policyValue = INetd.PENALTY_POLICY_LOG;
                break;
            case StrictMode.NETWORK_POLICY_REJECT:
                policyValue = INetd.PENALTY_POLICY_REJECT;
                break;
            default:
                throw new IllegalArgumentException("Unknown policy " + policy);
        }

        try {
            mNetdService.strictUidCleartextPenalty(uid, policyValue);
            mUidCleartextPolicy.put(uid, policy);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void setUidCleartextNetworkPolicy(int uid, int policy) {
        if (Binder.getCallingUid() != uid) {
            NetworkStack.checkNetworkStackPermission(mContext);
        }

        synchronized (mQuotaLock) {
            final int oldPolicy = mUidCleartextPolicy.get(uid, StrictMode.NETWORK_POLICY_ACCEPT);
            if (oldPolicy == policy) {
                // This also ensures we won't needlessly apply an ACCEPT policy if we've just
                // enabled strict and the underlying iptables rules are empty.
                return;
            }

            // TODO: remove this code after removing prepareNativeDaemon()
            if (!mStrictEnabled) {
                // Module isn't enabled yet; stash the requested policy away to
                // apply later once the daemon is connected.
                mUidCleartextPolicy.put(uid, policy);
                return;
            }

            // netd does not keep state on strict mode policies, and cannot replace a non-accept
            // policy without deleting it first. Rather than add state to netd, just always send
            // it an accept policy when switching between two non-accept policies.
            // TODO: consider keeping state in netd so we can simplify this code.
            if (oldPolicy != StrictMode.NETWORK_POLICY_ACCEPT &&
                    policy != StrictMode.NETWORK_POLICY_ACCEPT) {
                applyUidCleartextNetworkPolicy(uid, StrictMode.NETWORK_POLICY_ACCEPT);
            }

            applyUidCleartextNetworkPolicy(uid, policy);
        }
    }

    @Override
    public boolean isBandwidthControlEnabled() {
        return true;
    }

    private class NetdTetheringStatsProvider extends ITetheringStatsProvider.Stub {
        @Override
        public NetworkStats getTetherStats(int how) {
            // We only need to return per-UID stats. Per-device stats are already counted by
            // interface counters.
            if (how != STATS_PER_UID) {
                return new NetworkStats(SystemClock.elapsedRealtime(), 0);
            }

            final TetherStatsParcel[] tetherStatsVec;
            try {
                tetherStatsVec = mNetdService.tetherGetStats();
            } catch (RemoteException | ServiceSpecificException e) {
                throw new IllegalStateException("problem parsing tethering stats: ", e);
            }

            final NetworkStats stats = new NetworkStats(SystemClock.elapsedRealtime(),
                tetherStatsVec.length);
            final NetworkStats.Entry entry = new NetworkStats.Entry();

            for (TetherStatsParcel tetherStats : tetherStatsVec) {
                try {
                    entry.iface = tetherStats.iface;
                    entry.uid = UID_TETHERING;
                    entry.set = SET_DEFAULT;
                    entry.tag = TAG_NONE;
                    entry.rxBytes   = tetherStats.rxBytes;
                    entry.rxPackets = tetherStats.rxPackets;
                    entry.txBytes   = tetherStats.txBytes;
                    entry.txPackets = tetherStats.txPackets;
                    stats.combineValues(entry);
                } catch (ArrayIndexOutOfBoundsException e) {
                    throw new IllegalStateException("invalid tethering stats " + e);
                }
            }

            return stats;
        }

        @Override
        public void setInterfaceQuota(String iface, long quotaBytes) {
            // Do nothing. netd is already informed of quota changes in setInterfaceQuota.
        }
    }

    @Override
    public NetworkStats getNetworkStatsTethering(int how) {
        NetworkStack.checkNetworkStackPermission(mContext);

        final NetworkStats stats = new NetworkStats(SystemClock.elapsedRealtime(), 1);
        synchronized (mTetheringStatsProviders) {
            for (ITetheringStatsProvider provider: mTetheringStatsProviders.keySet()) {
                try {
                    stats.combineAllValues(provider.getTetherStats(how));
                } catch (RemoteException e) {
                    Log.e(TAG, "Problem reading tethering stats from " +
                            mTetheringStatsProviders.get(provider) + ": " + e);
                }
            }
        }
        return stats;
    }

    @Override
    public void addVpnUidRanges(int netId, UidRange[] ranges) {
        NetworkStack.checkNetworkStackPermission(mContext);

        try {
            mNetdService.networkAddUidRanges(netId, toStableParcels(ranges));
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void removeVpnUidRanges(int netId, UidRange[] ranges) {
        NetworkStack.checkNetworkStackPermission(mContext);
        try {
            mNetdService.networkRemoveUidRanges(netId, toStableParcels(ranges));
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void setFirewallEnabled(boolean enabled) {
        enforceSystemUid();
        try {
            mNetdService.firewallSetFirewallType(
                    enabled ? INetd.FIREWALL_WHITELIST : INetd.FIREWALL_BLACKLIST);
            mFirewallEnabled = enabled;
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean isFirewallEnabled() {
        enforceSystemUid();
        return mFirewallEnabled;
    }

    @Override
    public void setFirewallInterfaceRule(String iface, boolean allow) {
        enforceSystemUid();
        Preconditions.checkState(mFirewallEnabled);
        try {
            mNetdService.firewallSetInterfaceRule(iface,
                    allow ? INetd.FIREWALL_RULE_ALLOW : INetd.FIREWALL_RULE_DENY);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    private void closeSocketsForFirewallChainLocked(int chain, String chainName) {
        // UID ranges to close sockets on.
        UidRangeParcel[] ranges;
        // UID ranges whose sockets we won't touch.
        int[] exemptUids;

        int numUids = 0;
        if (DBG) Slog.d(TAG, "Closing sockets after enabling chain " + chainName);
        if (getFirewallType(chain) == FIREWALL_WHITELIST) {
            // Close all sockets on all non-system UIDs...
            ranges = new UidRangeParcel[] {
                // TODO: is there a better way of finding all existing users? If so, we could
                // specify their ranges here.
                makeUidRangeParcel(Process.FIRST_APPLICATION_UID, Integer.MAX_VALUE),
            };
            // ... except for the UIDs that have allow rules.
            synchronized (mRulesLock) {
                final SparseIntArray rules = getUidFirewallRulesLR(chain);
                exemptUids = new int[rules.size()];
                for (int i = 0; i < exemptUids.length; i++) {
                    if (rules.valueAt(i) == FIREWALL_RULE_ALLOW) {
                        exemptUids[numUids] = rules.keyAt(i);
                        numUids++;
                    }
                }
            }
            // Normally, whitelist chains only contain deny rules, so numUids == exemptUids.length.
            // But the code does not guarantee this in any way, and at least in one case - if we add
            // a UID rule to the firewall, and then disable the firewall - the chains can contain
            // the wrong type of rule. In this case, don't close connections that we shouldn't.
            //
            // TODO: tighten up this code by ensuring we never set the wrong type of rule, and
            // fix setFirewallEnabled to grab mQuotaLock and clear rules.
            if (numUids != exemptUids.length) {
                exemptUids = Arrays.copyOf(exemptUids, numUids);
            }
        } else {
            // Close sockets for every UID that has a deny rule...
            synchronized (mRulesLock) {
                final SparseIntArray rules = getUidFirewallRulesLR(chain);
                ranges = new UidRangeParcel[rules.size()];
                for (int i = 0; i < ranges.length; i++) {
                    if (rules.valueAt(i) == FIREWALL_RULE_DENY) {
                        int uid = rules.keyAt(i);
                        ranges[numUids] = makeUidRangeParcel(uid, uid);
                        numUids++;
                    }
                }
            }
            // As above; usually numUids == ranges.length, but not always.
            if (numUids != ranges.length) {
                ranges = Arrays.copyOf(ranges, numUids);
            }
            // ... with no exceptions.
            exemptUids = new int[0];
        }

        try {
            mNetdService.socketDestroy(ranges, exemptUids);
        } catch(RemoteException | ServiceSpecificException e) {
            Slog.e(TAG, "Error closing sockets after enabling chain " + chainName + ": " + e);
        }
    }

    @Override
    public void setFirewallChainEnabled(int chain, boolean enable) {
        enforceSystemUid();
        synchronized (mQuotaLock) {
            synchronized (mRulesLock) {
                if (getFirewallChainState(chain) == enable) {
                    // All is the same, nothing to do.  This relies on the fact that netd has child
                    // chains default detached.
                    return;
                }
                setFirewallChainState(chain, enable);
            }

            final String chainName = getFirewallChainName(chain);
            if (chain == FIREWALL_CHAIN_NONE) {
                throw new IllegalArgumentException("Bad child chain: " + chainName);
            }

            try {
                mNetdService.firewallEnableChildChain(chain, enable);
            } catch (RemoteException | ServiceSpecificException e) {
                throw new IllegalStateException(e);
            }

            // Close any sockets that were opened by the affected UIDs. This has to be done after
            // disabling network connectivity, in case they react to the socket close by reopening
            // the connection and race with the iptables commands that enable the firewall. All
            // whitelist and blacklist chains allow RSTs through.
            if (enable) {
                closeSocketsForFirewallChainLocked(chain, chainName);
            }
        }
    }

    private String getFirewallChainName(int chain) {
        switch (chain) {
            case FIREWALL_CHAIN_STANDBY:
                return FIREWALL_CHAIN_NAME_STANDBY;
            case FIREWALL_CHAIN_DOZABLE:
                return FIREWALL_CHAIN_NAME_DOZABLE;
            case FIREWALL_CHAIN_POWERSAVE:
                return FIREWALL_CHAIN_NAME_POWERSAVE;
            case FIREWALL_CHAIN_ISOLATED:
                return FIREWALL_CHAIN_NAME_ISOLATED;
            default:
                throw new IllegalArgumentException("Bad child chain: " + chain);
        }
    }

    private int getFirewallType(int chain) {
        switch (chain) {
            case FIREWALL_CHAIN_STANDBY:
                return FIREWALL_BLACKLIST;
            case FIREWALL_CHAIN_DOZABLE:
                return FIREWALL_WHITELIST;
            case FIREWALL_CHAIN_POWERSAVE:
                return FIREWALL_WHITELIST;
            case FIREWALL_CHAIN_ISOLATED:
                return FIREWALL_BLACKLIST;
            default:
                return isFirewallEnabled() ? FIREWALL_WHITELIST : FIREWALL_BLACKLIST;
        }
    }

    @Override
    public void setFirewallUidRules(int chain, int[] uids, int[] rules) {
        enforceSystemUid();
        synchronized (mQuotaLock) {
            synchronized (mRulesLock) {
                SparseIntArray uidFirewallRules = getUidFirewallRulesLR(chain);
                SparseIntArray newRules = new SparseIntArray();
                // apply new set of rules
                for (int index = uids.length - 1; index >= 0; --index) {
                    int uid = uids[index];
                    int rule = rules[index];
                    updateFirewallUidRuleLocked(chain, uid, rule);
                    newRules.put(uid, rule);
                }
                // collect the rules to remove.
                SparseIntArray rulesToRemove = new SparseIntArray();
                for (int index = uidFirewallRules.size() - 1; index >= 0; --index) {
                    int uid = uidFirewallRules.keyAt(index);
                    if (newRules.indexOfKey(uid) < 0) {
                        rulesToRemove.put(uid, FIREWALL_RULE_DEFAULT);
                    }
                }
                // remove dead rules
                for (int index = rulesToRemove.size() - 1; index >= 0; --index) {
                    int uid = rulesToRemove.keyAt(index);
                    updateFirewallUidRuleLocked(chain, uid, FIREWALL_RULE_DEFAULT);
                }
            }
            try {
                switch (chain) {
                    case FIREWALL_CHAIN_DOZABLE:
                        mNetdService.firewallReplaceUidChain("fw_dozable", true, uids);
                        break;
                    case FIREWALL_CHAIN_STANDBY:
                        mNetdService.firewallReplaceUidChain("fw_standby", false, uids);
                        break;
                    case FIREWALL_CHAIN_POWERSAVE:
                        mNetdService.firewallReplaceUidChain("fw_powersave", true, uids);
                        break;
                    case FIREWALL_CHAIN_ISOLATED:
                        mNetdService.firewallReplaceUidChain("fw_isolated", false, uids);
                        break;
                    case FIREWALL_CHAIN_NONE:
                    default:
                        Slog.d(TAG, "setFirewallUidRules() called on invalid chain: " + chain);
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Error flushing firewall chain " + chain, e);
            }
        }
    }

    @Override
    public void setFirewallUidRule(int chain, int uid, int rule) {
        enforceSystemUid();
        synchronized (mQuotaLock) {
            setFirewallUidRuleLocked(chain, uid, rule);
        }
    }

    private void setFirewallUidRuleLocked(int chain, int uid, int rule) {
        if (updateFirewallUidRuleLocked(chain, uid, rule)) {
            final int ruleType = getFirewallRuleType(chain, rule);
            try {
                mNetdService.firewallSetUidRule(chain, uid, ruleType);
            } catch (RemoteException | ServiceSpecificException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // TODO: now that netd supports batching, NMS should not keep these data structures anymore...
    private boolean updateFirewallUidRuleLocked(int chain, int uid, int rule) {
        synchronized (mRulesLock) {
            SparseIntArray uidFirewallRules = getUidFirewallRulesLR(chain);

            final int oldUidFirewallRule = uidFirewallRules.get(uid, FIREWALL_RULE_DEFAULT);
            if (DBG) {
                Slog.d(TAG, "oldRule = " + oldUidFirewallRule
                        + ", newRule=" + rule + " for uid=" + uid + " on chain " + chain);
            }
            if (oldUidFirewallRule == rule) {
                if (DBG) Slog.d(TAG, "!!!!! Skipping change");
                // TODO: eventually consider throwing
                return false;
            }

            String ruleName = getFirewallRuleName(chain, rule);
            String oldRuleName = getFirewallRuleName(chain, oldUidFirewallRule);

            if (rule == NetworkPolicyManager.FIREWALL_RULE_DEFAULT) {
                uidFirewallRules.delete(uid);
            } else {
                uidFirewallRules.put(uid, rule);
            }
            return !ruleName.equals(oldRuleName);
        }
    }

    private @NonNull String getFirewallRuleName(int chain, int rule) {
        String ruleName;
        if (getFirewallType(chain) == FIREWALL_WHITELIST) {
            if (rule == FIREWALL_RULE_ALLOW) {
                ruleName = "allow";
            } else {
                ruleName = "deny";
            }
        } else { // Blacklist mode
            if (rule == FIREWALL_RULE_DENY) {
                ruleName = "deny";
            } else {
                ruleName = "allow";
            }
        }
        return ruleName;
    }

    @GuardedBy("mRulesLock")
    private @NonNull SparseIntArray getUidFirewallRulesLR(int chain) {
        switch (chain) {
            case FIREWALL_CHAIN_STANDBY:
                return mUidFirewallStandbyRules;
            case FIREWALL_CHAIN_DOZABLE:
                return mUidFirewallDozableRules;
            case FIREWALL_CHAIN_POWERSAVE:
                return mUidFirewallPowerSaveRules;
            case FIREWALL_CHAIN_ISOLATED:
                return mUidFirewallIsolatedRules;
            case FIREWALL_CHAIN_NONE:
                return mUidFirewallRules;
            default:
                throw new IllegalArgumentException("Unknown chain:" + chain);
        }
    }

    private int getFirewallRuleType(int chain, int rule) {
        if (rule == NetworkPolicyManager.FIREWALL_RULE_DEFAULT) {
            return getFirewallType(chain) == FIREWALL_WHITELIST
                    ? INetd.FIREWALL_RULE_DENY : INetd.FIREWALL_RULE_ALLOW;
        }
        return rule;
    }

    private static void enforceSystemUid() {
        final int uid = Binder.getCallingUid();
        if (uid != Process.SYSTEM_UID) {
            throw new SecurityException("Only available to AID_SYSTEM");
        }
    }

    @Override
    public void registerNetworkActivityListener(INetworkActivityListener listener) {
        mNetworkActivityListeners.register(listener);
    }

    @Override
    public void unregisterNetworkActivityListener(INetworkActivityListener listener) {
        mNetworkActivityListeners.unregister(listener);
    }

    @Override
    public boolean isNetworkActive() {
        synchronized (mNetworkActivityListeners) {
            return mNetworkActive || mActiveIdleTimers.isEmpty();
        }
    }

    private void reportNetworkActive() {
        final int length = mNetworkActivityListeners.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                try {
                    mNetworkActivityListeners.getBroadcastItem(i).onNetworkActive();
                } catch (RemoteException | RuntimeException e) {
                }
            }
        } finally {
            mNetworkActivityListeners.finishBroadcast();
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        pw.print("mMobileActivityFromRadio="); pw.print(mMobileActivityFromRadio);
                pw.print(" mLastPowerStateFromRadio="); pw.println(mLastPowerStateFromRadio);
        pw.print("mNetworkActive="); pw.println(mNetworkActive);

        synchronized (mQuotaLock) {
            pw.print("Active quota ifaces: "); pw.println(mActiveQuotas.toString());
            pw.print("Active alert ifaces: "); pw.println(mActiveAlerts.toString());
            pw.print("Data saver mode: "); pw.println(mDataSaverMode);
            synchronized (mRulesLock) {
                dumpUidRuleOnQuotaLocked(pw, "blacklist", mUidRejectOnMetered);
                dumpUidRuleOnQuotaLocked(pw, "whitelist", mUidAllowOnMetered);
            }
        }

        synchronized (mRulesLock) {
            dumpUidFirewallRule(pw, "", mUidFirewallRules);

            pw.print("UID firewall standby chain enabled: "); pw.println(
                    getFirewallChainState(FIREWALL_CHAIN_STANDBY));
            dumpUidFirewallRule(pw, FIREWALL_CHAIN_NAME_STANDBY, mUidFirewallStandbyRules);

            pw.print("UID firewall dozable chain enabled: "); pw.println(
                    getFirewallChainState(FIREWALL_CHAIN_DOZABLE));
            dumpUidFirewallRule(pw, FIREWALL_CHAIN_NAME_DOZABLE, mUidFirewallDozableRules);

            pw.println("UID firewall powersave chain enabled: " +
                    getFirewallChainState(FIREWALL_CHAIN_POWERSAVE));
            dumpUidFirewallRule(pw, FIREWALL_CHAIN_NAME_POWERSAVE, mUidFirewallPowerSaveRules);

            pw.println("UID firewall isolated chain enabled: " +
                    getFirewallChainState(FIREWALL_CHAIN_ISOLATED));
            dumpUidFirewallRule(pw, FIREWALL_CHAIN_NAME_ISOLATED, mUidFirewallIsolatedRules);
        }

        synchronized (mIdleTimerLock) {
            pw.println("Idle timers:");
            for (HashMap.Entry<String, IdleTimerParams> ent : mActiveIdleTimers.entrySet()) {
                pw.print("  "); pw.print(ent.getKey()); pw.println(":");
                IdleTimerParams params = ent.getValue();
                pw.print("    timeout="); pw.print(params.timeout);
                pw.print(" type="); pw.print(params.type);
                pw.print(" networkCount="); pw.println(params.networkCount);
            }
        }

        pw.print("Firewall enabled: "); pw.println(mFirewallEnabled);
        pw.print("Netd service status: " );
        if (mNetdService == null) {
            pw.println("disconnected");
        } else {
            try {
                final boolean alive = mNetdService.isAlive();
                pw.println(alive ? "alive": "dead");
            } catch (RemoteException e) {
                pw.println("unreachable");
            }
        }
    }

    private void dumpUidRuleOnQuotaLocked(PrintWriter pw, String name, SparseBooleanArray list) {
        pw.print("UID bandwith control ");
        pw.print(name);
        pw.print(" rule: [");
        final int size = list.size();
        for (int i = 0; i < size; i++) {
            pw.print(list.keyAt(i));
            if (i < size - 1) pw.print(",");
        }
        pw.println("]");
    }

    private void dumpUidFirewallRule(PrintWriter pw, String name, SparseIntArray rules) {
        pw.print("UID firewall ");
        pw.print(name);
        pw.print(" rule: [");
        final int size = rules.size();
        for (int i = 0; i < size; i++) {
            pw.print(rules.keyAt(i));
            pw.print(":");
            pw.print(rules.valueAt(i));
            if (i < size - 1) pw.print(",");
        }
        pw.println("]");
    }

    @Override
    public void addInterfaceToNetwork(String iface, int netId) {
        modifyInterfaceInNetwork(MODIFY_OPERATION_ADD, netId, iface);
    }

    @Override
    public void removeInterfaceFromNetwork(String iface, int netId) {
        modifyInterfaceInNetwork(MODIFY_OPERATION_REMOVE, netId, iface);
    }

    private void modifyInterfaceInNetwork(boolean add, int netId, String iface) {
        NetworkStack.checkNetworkStackPermission(mContext);
        try {
            if (add) {
                mNetdService.networkAddInterface(netId, iface);
            } else {
                mNetdService.networkRemoveInterface(netId, iface);
            }
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void addLegacyRouteForNetId(int netId, RouteInfo routeInfo, int uid) {
        NetworkStack.checkNetworkStackPermission(mContext);

        final LinkAddress la = routeInfo.getDestinationLinkAddress();
        final String ifName = routeInfo.getInterface();
        final String dst = la.toString();
        final String nextHop;

        if (routeInfo.hasGateway()) {
            nextHop = routeInfo.getGateway().getHostAddress();
        } else {
            nextHop = "";
        }
        try {
            mNetdService.networkAddLegacyRoute(netId, ifName, dst, nextHop, uid);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void setDefaultNetId(int netId) {
        NetworkStack.checkNetworkStackPermission(mContext);

        try {
            mNetdService.networkSetDefault(netId);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void clearDefaultNetId() {
        NetworkStack.checkNetworkStackPermission(mContext);

        try {
            mNetdService.networkClearDefault();
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void setNetworkPermission(int netId, int permission) {
        NetworkStack.checkNetworkStackPermission(mContext);

        try {
            mNetdService.networkSetPermissionForNetwork(netId, permission);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void allowProtect(int uid) {
        NetworkStack.checkNetworkStackPermission(mContext);

        try {
            mNetdService.networkSetProtectAllow(uid);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void denyProtect(int uid) {
        NetworkStack.checkNetworkStackPermission(mContext);

        try {
            mNetdService.networkSetProtectDeny(uid);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void addInterfaceToLocalNetwork(String iface, List<RouteInfo> routes) {
        modifyInterfaceInNetwork(MODIFY_OPERATION_ADD, INetd.LOCAL_NET_ID, iface);
        // modifyInterfaceInNetwork already check calling permission.
        RouteUtils.addRoutesToLocalNetwork(mNetdService, iface, routes);
    }

    @Override
    public void removeInterfaceFromLocalNetwork(String iface) {
        modifyInterfaceInNetwork(MODIFY_OPERATION_REMOVE, INetd.LOCAL_NET_ID, iface);
    }

    @Override
    public int removeRoutesFromLocalNetwork(List<RouteInfo> routes) {
        NetworkStack.checkNetworkStackPermission(mContext);
        return RouteUtils.removeRoutesFromLocalNetwork(mNetdService, routes);
    }

    @Override
    public boolean isNetworkRestricted(int uid) {
        mContext.enforceCallingOrSelfPermission(OBSERVE_NETWORK_POLICY, TAG);
        return isNetworkRestrictedInternal(uid);
    }

    private boolean isNetworkRestrictedInternal(int uid) {
        synchronized (mRulesLock) {
            if (getFirewallChainState(FIREWALL_CHAIN_ISOLATED)
                    && mUidFirewallIsolatedRules.get(uid) == FIREWALL_RULE_DENY) {
                if (DBG) Slog.d(TAG, "Uid " + uid + " restricted because of isolated mode");
                return true;
            }
            if (getFirewallChainState(FIREWALL_CHAIN_STANDBY)
                    && mUidFirewallStandbyRules.get(uid) == FIREWALL_RULE_DENY) {
                if (DBG) Slog.d(TAG, "Uid " + uid + " restricted because of app standby mode");
                return true;
            }
            if (getFirewallChainState(FIREWALL_CHAIN_DOZABLE)
                    && mUidFirewallDozableRules.get(uid) != FIREWALL_RULE_ALLOW) {
                if (DBG) Slog.d(TAG, "Uid " + uid + " restricted because of device idle mode");
                return true;
            }
            if (getFirewallChainState(FIREWALL_CHAIN_POWERSAVE)
                    && mUidFirewallPowerSaveRules.get(uid) != FIREWALL_RULE_ALLOW) {
                if (DBG) Slog.d(TAG, "Uid " + uid + " restricted because of power saver mode");
                return true;
            }
            if (mUidRejectOnMetered.get(uid)) {
                if (DBG) Slog.d(TAG, "Uid " + uid + " restricted because of no metered data"
                        + " in the background");
                return true;
            }
            if (mDataSaverMode && !mUidAllowOnMetered.get(uid)) {
                if (DBG) Slog.d(TAG, "Uid " + uid + " restricted because of data saver mode");
                return true;
            }
            return false;
        }
    }

    private void setFirewallChainState(int chain, boolean state) {
        synchronized (mRulesLock) {
            mFirewallChainStates.put(chain, state);
        }
    }

    private boolean getFirewallChainState(int chain) {
        synchronized (mRulesLock) {
            return mFirewallChainStates.get(chain);
        }
    }

    @VisibleForTesting
    class LocalService extends NetworkManagementInternal {
        @Override
        public boolean isNetworkRestrictedForUid(int uid) {
            return isNetworkRestrictedInternal(uid);
        }
    }

    @VisibleForTesting
    Injector getInjector() {
        return new Injector();
    }

    @VisibleForTesting
    class Injector {
        void setDataSaverMode(boolean dataSaverMode) {
            mDataSaverMode = dataSaverMode;
        }

        void setFirewallChainState(int chain, boolean state) {
            NetworkManagementService.this.setFirewallChainState(chain, state);
        }

        void setFirewallRule(int chain, int uid, int rule) {
            synchronized (mRulesLock) {
                getUidFirewallRulesLR(chain).put(uid, rule);
            }
        }

        void setUidOnMeteredNetworkList(boolean blacklist, int uid, boolean enable) {
            synchronized (mRulesLock) {
                if (blacklist) {
                    mUidRejectOnMetered.put(uid, enable);
                } else {
                    mUidAllowOnMetered.put(uid, enable);
                }
            }
        }

        void reset() {
            synchronized (mRulesLock) {
                setDataSaverMode(false);
                final int[] chains = {
                        FIREWALL_CHAIN_DOZABLE,
                        FIREWALL_CHAIN_STANDBY,
                        FIREWALL_CHAIN_POWERSAVE,
                        FIREWALL_CHAIN_ISOLATED
                };
                for (int chain : chains) {
                    setFirewallChainState(chain, false);
                    getUidFirewallRulesLR(chain).clear();
                }
                mUidAllowOnMetered.clear();
                mUidRejectOnMetered.clear();
            }
        }
    }
}
