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

package android.location;

import static java.lang.Math.max;
import static java.lang.Math.min;

import android.Manifest;
import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.WorkSource;
import android.util.TimeUtils;

import com.android.internal.util.Preconditions;

import java.util.Objects;


/**
 * An encapsulation of various parameters for requesting location via {@link LocationManager}.
 */
public final class LocationRequest implements Parcelable {

    /**
     * Represents a passive only request. Such a request will not trigger any active locations or
     * power usage itself, but may receive locations generated in response to other requests.
     */
    public static final long PASSIVE_INTERVAL = Long.MAX_VALUE;

    /**
     * Used with {@link #setQuality} to request the most accurate locations available.
     *
     * <p>This may be up to 1 meter accuracy, although this is implementation dependent.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int ACCURACY_FINE = 100;

    /**
     * Used with {@link #setQuality} to request "block" level accuracy.
     *
     * <p>Block level accuracy is considered to be about 100 meter accuracy,
     * although this is implementation dependent. Using a coarse accuracy
     * such as this often consumes less power.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int ACCURACY_BLOCK = 102;

    /**
     * Used with {@link #setQuality} to request "city" level accuracy.
     *
     * <p>City level accuracy is considered to be about 10km accuracy,
     * although this is implementation dependent. Using a coarse accuracy
     * such as this often consumes less power.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int ACCURACY_CITY = 104;

    /**
     * Used with {@link #setQuality} to require no direct power impact (passive locations).
     *
     * <p>This location request will not trigger any active location requests,
     * but will receive locations triggered by other applications. Your application
     * will not receive any direct power blame for location work.
     *
     * @hide
     * @deprecated Use {@link #PASSIVE_INTERVAL} instead.
     */
    @SystemApi
    @Deprecated
    public static final int POWER_NONE = 200;

    /**
     * Used with {@link #setQuality} to request low power impact.
     *
     * <p>This location request will avoid high power location work where
     * possible.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int POWER_LOW = 201;

    /**
     * Used with {@link #setQuality} to allow high power consumption for location.
     *
     * <p>This location request will allow high power location work.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int POWER_HIGH = 203;

    private static final long IMPLICIT_MIN_UPDATE_INTERVAL = -1;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, publicAlternatives = "Use {@link "
            + "LocationManager} methods to provide the provider explicitly.")
    @Nullable private String mProvider;
    private int mQuality;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, publicAlternatives = "Use {@link "
            + "LocationRequest} instead.")
    private long mInterval;
    private long mMinUpdateIntervalMillis;
    private long mExpireAtRealtimeMillis;
    private long mDurationMillis;
    private int mMaxUpdates;
    private float mMinUpdateDistanceMeters;
    private boolean mHideFromAppOps;
    private boolean mLocationSettingsIgnored;
    private boolean mLowPower;
    private @Nullable WorkSource mWorkSource;

    /**
     * @hide
     * @deprecated Use the Builder to construct new LocationRequests.
     */
    @SystemApi
    @Deprecated
    @NonNull
    public static LocationRequest create() {
        // 60 minutes is the default legacy interval
        return new LocationRequest.Builder(60 * 60 * 1000)
                .setQuality(POWER_LOW)
                .build();
    }

    /**
     * @hide
     * @deprecated Use the Builder to construct new LocationRequests.
     */
    @SystemApi
    @Deprecated
    @NonNull
    public static LocationRequest createFromDeprecatedProvider(@NonNull String provider,
            long intervalMillis, float minUpdateDistanceMeters, boolean singleShot) {
        Preconditions.checkArgument(provider != null, "invalid null provider");

        if (intervalMillis < 0) {
            intervalMillis = 0;
        } else if (intervalMillis == PASSIVE_INTERVAL) {
            intervalMillis = Long.MAX_VALUE - 1;
        }
        if (minUpdateDistanceMeters < 0) {
            minUpdateDistanceMeters = 0;
        }

        int quality;
        if (LocationManager.PASSIVE_PROVIDER.equals(provider)) {
            quality = POWER_NONE;
        } else if (LocationManager.GPS_PROVIDER.equals(provider)) {
            quality = ACCURACY_FINE;
        } else {
            quality = POWER_LOW;
        }

        return new LocationRequest.Builder(intervalMillis)
                .setMinUpdateIntervalMillis(intervalMillis)
                .setMinUpdateDistanceMeters(minUpdateDistanceMeters)
                .setMaxUpdates(singleShot ? 1 : Integer.MAX_VALUE)
                .build()
                .setProvider(provider)
                .setQuality(quality);
    }

    /**
     * @hide
     * @deprecated Use the Builder to construct new LocationRequests.
     */
    @SystemApi
    @Deprecated
    @NonNull
    public static LocationRequest createFromDeprecatedCriteria(@NonNull Criteria criteria,
            long intervalMillis, float minUpdateDistanceMeters, boolean singleShot) {
        Preconditions.checkArgument(criteria != null, "invalid null criteria");

        if (intervalMillis < 0) {
            intervalMillis = 0;
        } else if (intervalMillis == PASSIVE_INTERVAL) {
            intervalMillis = Long.MAX_VALUE - 1;
        }
        if (minUpdateDistanceMeters < 0) {
            minUpdateDistanceMeters = 0;
        }

        return new LocationRequest.Builder(intervalMillis)
                .setQuality(criteria)
                .setMinUpdateIntervalMillis(intervalMillis)
                .setMinUpdateDistanceMeters(minUpdateDistanceMeters)
                .setMaxUpdates(singleShot ? 1 : Integer.MAX_VALUE)
                .build();
    }

    private LocationRequest(
            @Nullable String provider,
            long intervalMillis,
            int quality,
            long expireAtRealtimeMillis,
            long durationMillis,
            int maxUpdates,
            long minUpdateIntervalMillis,
            float minUpdateDistanceMeters,
            boolean hiddenFromAppOps,
            boolean locationSettingsIgnored,
            boolean lowPower,
            @Nullable WorkSource workSource) {
        Preconditions.checkArgument(intervalMillis != PASSIVE_INTERVAL || quality == POWER_NONE);
        Preconditions.checkArgument(minUpdateIntervalMillis <= intervalMillis);

        mProvider = provider;
        mInterval = intervalMillis;
        mQuality = quality;
        mMinUpdateIntervalMillis = minUpdateIntervalMillis;
        mExpireAtRealtimeMillis = expireAtRealtimeMillis;
        mDurationMillis = durationMillis;
        mMaxUpdates = maxUpdates;
        mMinUpdateDistanceMeters = minUpdateDistanceMeters;
        mHideFromAppOps = hiddenFromAppOps;
        mLowPower = lowPower;
        mLocationSettingsIgnored = locationSettingsIgnored;
        mWorkSource = workSource;
    }

    /**
     * @hide
     * @deprecated LocationRequests should be treated as immutable.
     */
    @SystemApi
    @Deprecated
    public @NonNull LocationRequest setProvider(@NonNull String provider) {
        Preconditions.checkArgument(provider != null);
        mProvider = provider;
        return this;
    }

    /**
     * @hide
     * @deprecated Providers are no longer an explicit part of a location request.
     */
    @SystemApi
    @Deprecated
    public @NonNull String getProvider() {
        return mProvider != null ? mProvider : LocationManager.FUSED_PROVIDER;
    }

    /**
     * @hide
     * @deprecated LocationRequests should be treated as immutable.
     */
    @SystemApi
    @Deprecated
    public @NonNull LocationRequest setQuality(int quality) {
        mQuality = Builder.checkQuality(quality, true);
        return this;
    }

    /**
     * Returns the quality of the location request.
     *
     * @return the quality of the location request
     *
     * @hide
     */
    @SystemApi
    public int getQuality() {
        if (mInterval == PASSIVE_INTERVAL) {
            return POWER_NONE;
        } else {
            return mQuality;
        }
    }

    /**
     * @hide
     * @deprecated LocationRequests should be treated as immutable.
     */
    @SystemApi
    @Deprecated
    public @NonNull LocationRequest setInterval(long millis) {
        Preconditions.checkArgument(millis >= 0);

        // legacy clients don't know about the passive interval
        if (millis == PASSIVE_INTERVAL) {
            millis = Long.MAX_VALUE - 1;
        }

        mInterval = millis;
        if (mMinUpdateIntervalMillis > mInterval) {
            mMinUpdateIntervalMillis = mInterval;
        }
        return this;
    }

    /**
     * @hide
     * @deprecated Use {@link #getIntervalMillis()} instead.
     */
    @SystemApi
    @Deprecated
    public long getInterval() {
        return getIntervalMillis();
    }

    /**
     * Returns the desired interval of location updates, or {@link #PASSIVE_INTERVAL} if this is a
     * passive, no power request. A passive request will not actively generate location updates
     * (and thus will not be power blamed for location), but may receive location updates generated
     * as a result of other location requests. A passive request must always have an explicit
     * minimum update interval set.
     *
     * <p>Locations may be available at a faster interval than specified here, see
     * {@link #getMinUpdateIntervalMillis()} for the behavior in that case.
     *
     * @return the desired interval of location updates
     */
    public long getIntervalMillis() {
        return mInterval;
    }

    /**
     * @hide
     * @deprecated LocationRequests should be treated as immutable.
     */
    @SystemApi
    @Deprecated
    public @NonNull LocationRequest setFastestInterval(long millis) {
        Preconditions.checkArgument(millis >= 0);
        mMinUpdateIntervalMillis = millis;
        return this;
    }

    /**
     * @hide
     * @deprecated Use {@link #getMinUpdateIntervalMillis()} instead.
     */
    @SystemApi
    @Deprecated
    public long getFastestInterval() {
        return getMinUpdateIntervalMillis();
    }

    /**
     * @hide
     * @deprecated LocationRequests should be treated as immutable.
     */
    @SystemApi
    @Deprecated
    public @NonNull LocationRequest setExpireAt(long millis) {
        mExpireAtRealtimeMillis = max(millis, 0);
        return this;
    }

    /**
     * @hide
     * @deprecated Prefer {@link #getDurationMillis()} where possible.
     */
    @SystemApi
    @Deprecated
    public long getExpireAt() {
        return mExpireAtRealtimeMillis;
    }

    /**
     * @hide
     * @deprecated LocationRequests should be treated as immutable.
     */
    @SystemApi
    @Deprecated
    public @NonNull LocationRequest setExpireIn(long millis) {
        mDurationMillis = millis;
        return this;
    }

    /**
     * @hide
     * @deprecated Use {@link #getDurationMillis()} instead.
     */
    @SystemApi
    @Deprecated
    public long getExpireIn() {
        return getDurationMillis();
    }

    /**
     * Returns the duration for which location will be provided before the request is automatically
     * removed. A duration of <code>Long.MAX_VALUE</code> represents an unlimited duration.
     *
     * @return the duration for which location will be provided
     */
    public long getDurationMillis() {
        return mDurationMillis;
    }

    /**
     * @hide
     */
    public long getExpirationRealtimeMs(long startRealtimeMs) {
        long expirationRealtimeMs;
        // Check for > Long.MAX_VALUE overflow (elapsedRealtime > 0):
        if (mDurationMillis > Long.MAX_VALUE - startRealtimeMs) {
            expirationRealtimeMs = Long.MAX_VALUE;
        } else {
            expirationRealtimeMs = startRealtimeMs + mDurationMillis;
        }
        return min(expirationRealtimeMs, mExpireAtRealtimeMillis);
    }

    /**
     * @hide
     * @deprecated LocationRequests should be treated as immutable.
     */
    @SystemApi
    @Deprecated
    public @NonNull LocationRequest setNumUpdates(int numUpdates) {
        if (numUpdates <= 0) {
            throw new IllegalArgumentException(
                    "invalid numUpdates: " + numUpdates);
        }
        mMaxUpdates = numUpdates;
        return this;
    }

    /**
     * @hide
     * @deprecated Use {@link #getMaxUpdates()} instead.
     */
    @SystemApi
    @Deprecated
    public int getNumUpdates() {
        return getMaxUpdates();
    }

    /**
     * Returns the maximum number of location updates for this request before the request is
     * automatically removed. A max updates value of <code>Integer.MAX_VALUE</code> represents an
     * unlimited number of updates.
     */
    public int getMaxUpdates() {
        return mMaxUpdates;
    }

    /**
     * Returns the minimum update interval. If location updates are available faster than the
     * request interval then locations will only be delivered if the minimum update interval has
     * expired since the last location update.
     *
     * <p class=note><strong>Note:</strong> Some allowance for jitter is already built into the
     * minimum update interval, so you need not worry about updates blocked simply because they
     * arrived a fraction of a second earlier than expected.
     *
     * @return the minimum update interval
     */
    public long getMinUpdateIntervalMillis() {
        if (mMinUpdateIntervalMillis == IMPLICIT_MIN_UPDATE_INTERVAL) {
            return mInterval;
        } else {
            // the min is only necessary in case someone use a deprecated function to mess with the
            // interval or min update interval
            return min(mMinUpdateIntervalMillis, mInterval);
        }
    }

    /**
     * @hide
     * @deprecated LocationRequests should be treated as immutable.
     */
    @SystemApi
    @Deprecated
    public @NonNull LocationRequest setSmallestDisplacement(float minDisplacementMeters) {
        mMinUpdateDistanceMeters = Preconditions.checkArgumentInRange(minDisplacementMeters, 0,
                Float.MAX_VALUE, "minDisplacementMeters");
        return this;
    }

    /**
     * @hide
     * @deprecated Use {@link #getMinUpdateDistanceMeters()} instead.
     */
    @SystemApi
    @Deprecated
    public float getSmallestDisplacement() {
        return getMinUpdateDistanceMeters();
    }

    /**
     * Returns the minimum distance between location updates. If a potential location update is
     * closer to the last location update than the minimum update distance, then the potential
     * location update will not occur. A value of 0 meters implies that no location update will ever
     * be rejected due to failing this constraint.
     *
     * @return the minimum distance between location updates
     */
    public float getMinUpdateDistanceMeters() {
        return mMinUpdateDistanceMeters;
    }

    /**
     * @hide
     * @deprecated LocationRequests should be treated as immutable.
     */
    @SystemApi
    @Deprecated
    public void setHideFromAppOps(boolean hiddenFromAppOps) {
        mHideFromAppOps = hiddenFromAppOps;
    }

    /**
     * @hide
     * @deprecated Use {@link #isHiddenFromAppOps()} instead.
     */
    @SystemApi
    @Deprecated
    public boolean getHideFromAppOps() {
        return isHiddenFromAppOps();
    }

    /**
     * Returns true if this request should be ignored while updating app ops with location usage.
     * This implies that someone else (usually the creator of the location request) is responsible
     * for updating app ops.
     *
     * @return true if this request should be ignored while updating app ops with location usage
     *
     * @hide
     */
    @TestApi
    @SystemApi
    public boolean isHiddenFromAppOps() {
        return mHideFromAppOps;
    }

    /**
     * @hide
     * @deprecated LocationRequests should be treated as immutable.
     */
    @SystemApi
    @Deprecated
    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    public @NonNull LocationRequest setLocationSettingsIgnored(boolean locationSettingsIgnored) {
        mLocationSettingsIgnored = locationSettingsIgnored;
        return this;
    }

    /**
     * Returns true if location settings, throttling, background location limits, and any other
     * possible limiting factors will be ignored in order to satisfy this request.
     *
     * @return true if all limiting factors will be ignored to satisfy this request
     *
     * @hide
     */
    @TestApi
    @SystemApi
    public boolean isLocationSettingsIgnored() {
        return mLocationSettingsIgnored;
    }

    /**
     * @hide
     * @deprecated LocationRequests should be treated as immutable.
     */
    @SystemApi
    @Deprecated
    public @NonNull LocationRequest setLowPowerMode(boolean enabled) {
        mLowPower = enabled;
        return this;
    }

    /**
     * @hide
     * @deprecated Use {@link #isLowPower()} instead.
     */
    @Deprecated
    @SystemApi
    public boolean isLowPowerMode() {
        return isLowPower();
    }

    /**
     * Returns true if extreme trade-offs should be made to save power for this request. This
     * usually involves specialized hardware modes which can greatly affect the quality of
     * locations.
     *
     * @return true if extreme trade-offs should be made to save power for this request
     *
     * @hide
     */
    @TestApi
    @SystemApi
    public boolean isLowPower() {
        return mLowPower;
    }

    /**
     * @hide
     * @deprecated LocationRequests should be treated as immutable.
     */
    @SystemApi
    @Deprecated
    public void setWorkSource(@Nullable WorkSource workSource) {
        mWorkSource = workSource;
    }

    /**
     * Returns the work source used for power blame for this request. If null, the system is free to
     * assign power blame as it deems most appropriate.
     *
     * @return the work source used for power blame for this request
     *
     * @hide
     */
    @TestApi
    @SystemApi
    public @Nullable WorkSource getWorkSource() {
        return mWorkSource;
    }


    public static final @NonNull Parcelable.Creator<LocationRequest> CREATOR =
            new Parcelable.Creator<LocationRequest>() {
                @Override
                public LocationRequest createFromParcel(Parcel in) {
                    return new LocationRequest(
                            /* provider= */ in.readString(),
                            /* intervalMillis= */ in.readLong(),
                            /* quality= */ in.readInt(),
                            /* expireAtRealtimeMillis= */ in.readLong(),
                            /* durationMillis= */ in.readLong(),
                            /* maxUpdates= */ in.readInt(),
                            /* minUpdateIntervalMillis= */ in.readLong(),
                            /* minUpdateDistanceMeters= */ in.readFloat(),
                            /* hiddenFromAppOps= */ in.readBoolean(),
                            /* locationSettingsIgnored= */ in.readBoolean(),
                            /* lowPower= */ in.readBoolean(),
                            /* workSource= */ in.readTypedObject(WorkSource.CREATOR));
                }

                @Override
                public LocationRequest[] newArray(int size) {
                    return new LocationRequest[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeString(mProvider);
        parcel.writeLong(mInterval);
        parcel.writeInt(mQuality);
        parcel.writeLong(mExpireAtRealtimeMillis);
        parcel.writeLong(mDurationMillis);
        parcel.writeInt(mMaxUpdates);
        parcel.writeLong(mMinUpdateIntervalMillis);
        parcel.writeFloat(mMinUpdateDistanceMeters);
        parcel.writeBoolean(mHideFromAppOps);
        parcel.writeBoolean(mLocationSettingsIgnored);
        parcel.writeBoolean(mLowPower);
        parcel.writeTypedObject(mWorkSource, 0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        LocationRequest that = (LocationRequest) o;
        return mInterval == that.mInterval
                && mQuality == that.mQuality
                && mExpireAtRealtimeMillis == that.mExpireAtRealtimeMillis
                && mDurationMillis == that.mDurationMillis
                && mMaxUpdates == that.mMaxUpdates
                && mMinUpdateIntervalMillis == that.mMinUpdateIntervalMillis
                && Float.compare(that.mMinUpdateDistanceMeters, mMinUpdateDistanceMeters) == 0
                && mHideFromAppOps == that.mHideFromAppOps
                && mLocationSettingsIgnored == that.mLocationSettingsIgnored
                && mLowPower == that.mLowPower
                && Objects.equals(mProvider, that.mProvider)
                && Objects.equals(mWorkSource, that.mWorkSource);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mProvider, mInterval, mWorkSource);
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("Request[");
        if (mProvider != null) {
            s.append(mProvider).append(" ");
        }
        if (mQuality != POWER_NONE && mQuality != ACCURACY_BLOCK) {
            s.append(qualityToString(mQuality)).append(" ");
        }
        if (mInterval != PASSIVE_INTERVAL) {
            s.append("interval=");
            TimeUtils.formatDuration(mInterval, s);
        } else {
            s.append("PASSIVE");
        }
        if (mExpireAtRealtimeMillis != Long.MAX_VALUE) {
            s.append(" expireAt=").append(TimeUtils.formatRealtime(mExpireAtRealtimeMillis));
        }
        if (mDurationMillis != Long.MAX_VALUE) {
            s.append(" duration=");
            TimeUtils.formatDuration(mDurationMillis, s);
        }
        if (mMaxUpdates != Integer.MAX_VALUE) {
            s.append(" maxUpdates=").append(mMaxUpdates);
        }
        if (mMinUpdateIntervalMillis < mInterval) {
            s.append(" minUpdateInterval=");
            TimeUtils.formatDuration(mMinUpdateIntervalMillis, s);
        }
        if (mMinUpdateDistanceMeters > 0.0) {
            s.append(" minUpdateDistance=").append(mMinUpdateDistanceMeters);
        }
        if (mLowPower) {
            s.append(" lowPower");
        }
        if (mHideFromAppOps) {
            s.append(" hiddenFromAppOps");
        }
        if (mLocationSettingsIgnored) {
            s.append(" locationSettingsIgnored");
        }
        if (mWorkSource != null) {
            s.append(" ").append(mWorkSource);
        }
        s.append(']');
        return s.toString();
    }

    private static String qualityToString(int quality) {
        switch (quality) {
            case ACCURACY_FINE:
                return "ACCURACY_FINE";
            case ACCURACY_BLOCK:
                return "ACCURACY_BLOCK";
            case ACCURACY_CITY:
                return "ACCURACY_CITY";
            case POWER_NONE:
                return "POWER_NONE";
            case POWER_LOW:
                return "POWER_LOW";
            case POWER_HIGH:
                return "POWER_HIGH";
            default:
                return "???";
        }
    }

    /**
     * A builder class for {@link LocationRequest}.
     */
    public static final class Builder {

        private static int checkQuality(int quality, boolean allowDeprecated) {
            switch (quality) {
                case ACCURACY_FINE:
                    // fall through
                case ACCURACY_BLOCK:
                    // fall through
                case ACCURACY_CITY:
                    // fall through
                case POWER_LOW:
                    // fall through
                case POWER_HIGH:
                    return quality;
                case POWER_NONE:
                    if (allowDeprecated) {
                        return quality;
                    }
                    // fall through
                default:
                    throw new IllegalArgumentException("invalid quality: " + quality);
            }
        }

        private long mIntervalMillis;
        private int mQuality;
        private long mDurationMillis;
        private int mMaxUpdates;
        private long mMinUpdateIntervalMillis;
        private float mMinUpdateDistanceMeters;
        private boolean mHiddenFromAppOps;
        private boolean mLocationSettingsIgnored;
        private boolean mLowPower;
        @Nullable private WorkSource mWorkSource;

        /**
         * Creates a new Builder with the given interval. See {@link #setIntervalMillis(long)} for
         * more information on the interval.
         */
        public Builder(long intervalMillis) {
            // gives us a range check
            setIntervalMillis(intervalMillis);

            mQuality = ACCURACY_BLOCK;
            mDurationMillis = Long.MAX_VALUE;
            mMaxUpdates = Integer.MAX_VALUE;
            mMinUpdateIntervalMillis = IMPLICIT_MIN_UPDATE_INTERVAL;
            mMinUpdateDistanceMeters = 0;
            mHiddenFromAppOps = false;
            mLocationSettingsIgnored = false;
            mLowPower = false;
            mWorkSource = null;
        }

        /**
         * Creates a new Builder with all parameters copied from the given location request.
         */
        public Builder(@NonNull LocationRequest locationRequest) {
            mIntervalMillis = locationRequest.mInterval;
            mQuality = locationRequest.mQuality;
            mDurationMillis = locationRequest.mDurationMillis;
            mMaxUpdates = locationRequest.mMaxUpdates;
            mMinUpdateIntervalMillis = locationRequest.mMinUpdateIntervalMillis;
            mMinUpdateDistanceMeters = locationRequest.mMinUpdateDistanceMeters;
            mHiddenFromAppOps = locationRequest.mHideFromAppOps;
            mLocationSettingsIgnored = locationRequest.mLocationSettingsIgnored;
            mLowPower = locationRequest.mLowPower;
            mWorkSource = locationRequest.mWorkSource;

            // handle edge cases that can only happen with location request that has been modified
            // by deprecated SystemApi methods
            if (mQuality == POWER_NONE) {
                mIntervalMillis = PASSIVE_INTERVAL;
            }
            if (mIntervalMillis == PASSIVE_INTERVAL
                    && mMinUpdateIntervalMillis == IMPLICIT_MIN_UPDATE_INTERVAL) {
                // this is the legacy default minimum update interval, so if we're forced to
                // change the value, at least this should be unsuprising to legacy clients (which
                // should be the only clients capable of getting in this weird state).
                mMinUpdateIntervalMillis = 10 * 60 * 1000;
            }
        }

        /**
         * Sets the request interval. The request interval may be set to {@link #PASSIVE_INTERVAL}
         * which indicates this request will not actively generate location updates (and thus will
         * not be power blamed for location), but may receive location updates generated as a result
         * of other location requests. A passive request must always have an explicit minimum
         * update interval set.
         *
         * <p>Locations may be available at a faster interval than specified here, see
         * {@link #setMinUpdateIntervalMillis(long)} for the behavior in that case.
         */
        public @NonNull Builder setIntervalMillis(@IntRange(from = 0) long intervalMillis) {
            mIntervalMillis = Preconditions.checkArgumentInRange(intervalMillis, 0, Long.MAX_VALUE,
                    "intervalMillis");
            return this;
        }

        /**
         * @hide
         */
        @SystemApi
        public @NonNull Builder setQuality(int quality) {
            mQuality = checkQuality(quality, false);
            return this;
        }

        /**
         * @hide
         */
        public @NonNull Builder setQuality(@NonNull Criteria criteria) {
            switch (criteria.getAccuracy()) {
                case Criteria.ACCURACY_COARSE:
                    mQuality = ACCURACY_BLOCK;
                    break;
                case Criteria.ACCURACY_FINE:
                    mQuality = ACCURACY_FINE;
                    break;
                default: {
                    if (criteria.getPowerRequirement() == Criteria.POWER_HIGH) {
                        mQuality = POWER_HIGH;
                    } else {
                        mQuality = POWER_LOW;
                    }
                }
            }
            return this;
        }

        /**
         * Sets the duration this request will continue before being automatically removed. Defaults
         * to <code>Long.MAX_VALUE</code>, which represents an unlimited duration.
         */
        public @NonNull Builder setDurationMillis(@IntRange(from = 1) long durationMillis) {
            mDurationMillis = Preconditions.checkArgumentInRange(durationMillis, 1, Long.MAX_VALUE,
                    "durationMillis");
            return this;
        }

        /**
         * Sets the maximum number of location updates for this request before this request is
         * automatically removed. Defaults to <code>Integer.MAX_VALUE</code>, which represents an
         * unlimited number of updates.
         */
        public @NonNull Builder setMaxUpdates(
                @IntRange(from = 1, to = Integer.MAX_VALUE) int maxUpdates) {
            mMaxUpdates = Preconditions.checkArgumentInRange(maxUpdates, 1, Integer.MAX_VALUE,
                    "maxUpdates");
            return this;
        }

        /**
         * Sets an explicit minimum update interval. If location updates are available faster than
         * the request interval then locations will only be delivered if the minimum update interval
         * has expired since the last location update. Defaults to no explicit minimum update
         * interval set, which means the minimum update interval is the same as the interval.
         *
         * <p class=note><strong>Note:</strong> Some allowance for jitter is already built into the
         * minimum update interval, so you need not worry about updates blocked simply because they
         * arrived a fraction of a second earlier than expected.
         *
         * <p class="note"><strong>Note:</strong> When {@link #build()} is invoked, the minimum of
         * the interval and the minimum update interval will be used as the minimum update interval
         * of the built request.
         */
        public @NonNull Builder setMinUpdateIntervalMillis(
                @IntRange(from = 0) long minUpdateIntervalMillis) {
            mMinUpdateIntervalMillis = Preconditions.checkArgumentInRange(minUpdateIntervalMillis,
                    0, Long.MAX_VALUE, "minUpdateIntervalMillis");
            return this;
        }

        /**
         * Clears an explicitly set minimum update interval and reverts to an implicit minimum
         * update interval which is the same as the interval.
         */
        public @NonNull Builder clearMinUpdateIntervalMillis() {
            mMinUpdateIntervalMillis = IMPLICIT_MIN_UPDATE_INTERVAL;
            return this;
        }

        /**
         * Sets the minimum update distance between delivered locations. If a potential location
         * update is closer to the last delivered location than the minimum update distance, then
         * the potential location update will not occur. Defaults to 0, which represents no minimum
         * update distance.
         */
        public @NonNull Builder setMinUpdateDistanceMeters(
                @FloatRange(from = 0, to = Float.MAX_VALUE) float minUpdateDistanceMeters) {
            mMinUpdateDistanceMeters = Preconditions.checkArgumentInRange(minUpdateDistanceMeters,
                    0, Float.MAX_VALUE, "minUpdateDistanceMeters");
            return this;
        }

        /**
         * If set to true, indicates that app ops should not be updated with location usage due to
         * this request. This implies that someone else (usually the creator of the location
         * request) is responsible for updating app ops as appropriate. Defaults to false.
         *
         * <p>Permissions enforcement occurs when resulting location request is actually used, not
         * when this method is invoked.
         *
         * @hide
         */
        @TestApi
        @SystemApi
        @RequiresPermission(Manifest.permission.UPDATE_APP_OPS_STATS)
        public @NonNull Builder setHiddenFromAppOps(boolean hiddenFromAppOps) {
            mHiddenFromAppOps = hiddenFromAppOps;
            return this;
        }

        /**
         * If set to true, indicates that location settings, throttling, background location limits,
         * and any other possible limiting factors should be ignored in order to satisfy this
         * request. This is only intended for use in user initiated emergency situations, and
         * should be used extremely cautiously. Defaults to false.
         *
         * <p>Permissions enforcement occurs when resulting location request is actually used, not
         * when this method is invoked.
         *
         * @hide
         */
        @TestApi
        @SystemApi
        @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
        public @NonNull Builder setLocationSettingsIgnored(boolean locationSettingsIgnored) {
            mLocationSettingsIgnored = locationSettingsIgnored;
            return this;
        }

        /**
         * It set to true, indicates that extreme trade-offs should be made if possible to save
         * power for this request. This usually involves specialized hardware modes which can
         * greatly affect the quality of locations. Defaults to false.
         *
         * <p>Permissions enforcement occurs when resulting location request is actually used, not
         * when this method is invoked.
         *
         * @hide
         */
        @TestApi
        @SystemApi
        @RequiresPermission(Manifest.permission.LOCATION_HARDWARE)
        public @NonNull Builder setLowPower(boolean lowPower) {
            mLowPower = lowPower;
            return this;
        }

        /**
         * Sets the work source to use for power blame for this location request. Defaults to null,
         * which implies the system is free to assign power blame as it determines best for this
         * request (which usually means blaming the owner of the location listener).
         *
         * <p>Permissions enforcement occurs when resulting location request is actually used, not
         * when this method is invoked.
         *
         * @hide
         */
        @TestApi
        @SystemApi
        @RequiresPermission(Manifest.permission.UPDATE_DEVICE_STATS)
        public @NonNull Builder setWorkSource(@Nullable WorkSource workSource) {
            mWorkSource = workSource;
            return this;
        }

        /**
         * Builds a location request from this builder. If an explicit minimum update interval is
         * set, the minimum update interval of the location request will be the minimum of the
         * interval and minimum update interval.
         *
         * <p>If building a passive request then you must have set an explicit minimum update
         * interval.
         *
         * @throws IllegalStateException if building a passive request with no explicit minimum
         * update interval set
         * @return a new location request
         */
        public @NonNull LocationRequest build() {
            Preconditions.checkState(mIntervalMillis != PASSIVE_INTERVAL
                            || mMinUpdateIntervalMillis != IMPLICIT_MIN_UPDATE_INTERVAL,
                    "passive location requests must have an explicit minimum update interval");

            return new LocationRequest(
                    null,
                    mIntervalMillis,
                    mIntervalMillis != PASSIVE_INTERVAL ? mQuality : POWER_NONE,
                    Long.MAX_VALUE,
                    mDurationMillis,
                    mMaxUpdates,
                    min(mMinUpdateIntervalMillis, mIntervalMillis),
                    mMinUpdateDistanceMeters,
                    mHiddenFromAppOps,
                    mLocationSettingsIgnored,
                    mLowPower,
                    mWorkSource);
        }
    }
}
