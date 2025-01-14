/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi.hotspot2;

import static android.net.wifi.WifiConfiguration.MeteredOverride;

import static com.android.server.wifi.MboOceConstants.DEFAULT_BLOCKLIST_DURATION_MS;

import android.annotation.Nullable;
import android.net.wifi.EAPConstants;
import android.net.wifi.ScanResult;
import android.net.wifi.SecurityParams;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiSsid;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.Credential.SimCredential;
import android.net.wifi.hotspot2.pps.Credential.UserCredential;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;

import com.android.modules.utils.build.SdkLevel;
import com.android.server.wifi.Clock;
import com.android.server.wifi.IMSIParameter;
import com.android.server.wifi.WifiCarrierInfoManager;
import com.android.server.wifi.WifiKeyStore;
import com.android.server.wifi.hotspot2.anqp.ANQPElement;
import com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType;
import com.android.server.wifi.hotspot2.anqp.DomainNameElement;
import com.android.server.wifi.hotspot2.anqp.NAIRealmElement;
import com.android.server.wifi.hotspot2.anqp.RoamingConsortiumElement;
import com.android.server.wifi.hotspot2.anqp.ThreeGPPNetworkElement;
import com.android.server.wifi.hotspot2.anqp.eap.AuthParam;
import com.android.server.wifi.hotspot2.anqp.eap.NonEAPInnerAuth;
import com.android.server.wifi.util.ArrayUtils;
import com.android.server.wifi.util.InformationElementUtil.RoamingConsortium;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Abstraction for Passpoint service provider.  This class contains the both static
 * Passpoint configuration data and the runtime data (e.g. blacklisted SSIDs, statistics).
 */
public class PasspointProvider {
    private static final String TAG = "PasspointProvider";

    /**
     * Used as part of alias string for certificates and keys.  The alias string is in the format
     * of: [KEY_TYPE]_HS2_[ProviderID]
     * For example: "CACERT_HS2_0", "USRCERT_HS2_0", "USRPKEY_HS2_0", "CACERT_HS2_REMEDIATION_0"
     */
    private static final String ALIAS_HS_TYPE = "HS2_";
    private static final String ALIAS_ALIAS_REMEDIATION_TYPE = "REMEDIATION_";

    private static final String SYSTEM_CA_STORE_PATH = "/system/etc/security/cacerts";
    private static final long MAX_RCOI_ENTRY_LIFETIME_MS = 600_000; // 10 minutes

    private final PasspointConfiguration mConfig;
    private final WifiKeyStore mKeyStore;

    /**
     * Aliases for the private keys and certificates installed in the keystore.  Each alias
     * is a suffix of the actual certificate or key name installed in the keystore.  The
     * certificate or key name in the keystore is consist of |Type|_|alias|.
     * This will be consistent with the usage of the term "alias" in {@link WifiEnterpriseConfig}.
     */
    private List<String> mCaCertificateAliases;
    private String mClientPrivateKeyAndCertificateAlias;
    private String mRemediationCaCertificateAlias;

    private final long mProviderId;
    private final int mCreatorUid;
    private final String mPackageName;

    private final IMSIParameter mImsiParameter;

    private final int mEAPMethodID;
    private final AuthParam mAuthParam;
    private final WifiCarrierInfoManager mWifiCarrierInfoManager;

    private int mBestGuessCarrierId = TelephonyManager.UNKNOWN_CARRIER_ID;
    private boolean mHasEverConnected;
    private boolean mIsShared;
    private boolean mIsFromSuggestion;
    private boolean mIsTrusted;
    private boolean mIsRestricted;
    private boolean mVerboseLoggingEnabled;

    private final Clock mClock;
    private long mReauthDelay = 0;
    private List<String> mBlockedBssids = new ArrayList<>();
    private String mAnonymousIdentity = null;
    private String mConnectChoice = null;
    private int mConnectChoiceRssi = 0;
    private String mMostRecentSsid = null;
    private long mMostRecentConnectionTime;

    // A map that maps SSIDs (String) to a pair of RCOI and a timestamp (both are Long) to be
    // used later when connecting to an RCOI-based Passpoint network.
    private final Map<String, Pair<Long, Long>> mRcoiMatchForNetwork = new HashMap<>();

    /**
     * Comparator to sort PasspointProviders in descending order by their most recent connection
     * time.
     */
    public static class ConnectionTimeComparator implements Comparator<PasspointProvider> {
        public int compare(PasspointProvider a, PasspointProvider b) {
            long diff = a.getMostRecentConnectionTime() - b.getMostRecentConnectionTime();
            return (diff < 0) ? -1 : 1;
        }
    }

    public PasspointProvider(PasspointConfiguration config, WifiKeyStore keyStore,
            WifiCarrierInfoManager wifiCarrierInfoManager, long providerId, int creatorUid,
            String packageName, boolean isFromSuggestion, Clock clock) {
        this(config, keyStore, wifiCarrierInfoManager, providerId, creatorUid, packageName,
                isFromSuggestion, null, null, null, false, false, clock);
    }

    public PasspointProvider(PasspointConfiguration config, WifiKeyStore keyStore,
            WifiCarrierInfoManager wifiCarrierInfoManager, long providerId, int creatorUid,
            String packageName, boolean isFromSuggestion, List<String> caCertificateAliases,
            String clientPrivateKeyAndCertificateAlias, String remediationCaCertificateAlias,
            boolean hasEverConnected, boolean isShared, Clock clock) {
        // Maintain a copy of the configuration to avoid it being updated by others.
        mConfig = new PasspointConfiguration(config);
        mKeyStore = keyStore;
        mProviderId = providerId;
        mCreatorUid = creatorUid;
        mPackageName = packageName;
        mCaCertificateAliases = caCertificateAliases;
        mClientPrivateKeyAndCertificateAlias = clientPrivateKeyAndCertificateAlias;
        mRemediationCaCertificateAlias = remediationCaCertificateAlias;
        mHasEverConnected = hasEverConnected;
        mIsShared = isShared;
        mIsFromSuggestion = isFromSuggestion;
        mWifiCarrierInfoManager = wifiCarrierInfoManager;
        mIsTrusted = true;
        mIsRestricted = false;
        mClock = clock;

        // Setup EAP method and authentication parameter based on the credential.
        if (mConfig.getCredential().getUserCredential() != null) {
            mEAPMethodID = EAPConstants.EAP_TTLS;
            mAuthParam = new NonEAPInnerAuth(NonEAPInnerAuth.getAuthTypeID(
                    mConfig.getCredential().getUserCredential().getNonEapInnerMethod()));
            mImsiParameter = null;
        } else if (mConfig.getCredential().getCertCredential() != null) {
            mEAPMethodID = EAPConstants.EAP_TLS;
            mAuthParam = null;
            mImsiParameter = null;
        } else {
            mEAPMethodID = mConfig.getCredential().getSimCredential().getEapType();
            mAuthParam = null;
            mImsiParameter = IMSIParameter.build(
                    mConfig.getCredential().getSimCredential().getImsi());
        }
    }

    /**
     * Set passpoint network trusted or not.
     * Default is true. Only allows to change when it is from suggestion.
     */
    public void setTrusted(boolean trusted) {
        if (!mIsFromSuggestion) {
            Log.e(TAG, "setTrusted can only be called for suggestion passpoint network");
            return;
        }
        mIsTrusted = trusted;
    }

    /**
     * Check passpoint network trusted or not.
     */
    public boolean isTrusted() {
        return mIsTrusted;
    }

    /**
     * Set passpoint network restricted or not.
     * Default is false. Only allows to change when it is from suggestion.
     */
    public void setRestricted(boolean restricted) {
        if (!mIsFromSuggestion) {
            Log.e(TAG, "setRestricted can only be called for suggestion passpoint network");
            return;
        }
        mIsRestricted = restricted;
    }

    /**
     * Check passpoint network restricted or not.
     */
    public boolean isRestricted() {
        return mIsRestricted;
    }

    /**
     * Set Anonymous Identity for passpoint network.
     */
    public void setAnonymousIdentity(String anonymousIdentity) {
        mAnonymousIdentity = anonymousIdentity;
    }

    public String getAnonymousIdentity() {
        return mAnonymousIdentity;
    }

    public PasspointConfiguration getConfig() {
        // Return a copy of the configuration to avoid it being updated by others.
        return new PasspointConfiguration(mConfig);
    }

    public List<String> getCaCertificateAliases() {
        return mCaCertificateAliases;
    }

    public String getClientPrivateKeyAndCertificateAlias() {
        return mClientPrivateKeyAndCertificateAlias;
    }

    public String getRemediationCaCertificateAlias() {
        return mRemediationCaCertificateAlias;
    }

    public long getProviderId() {
        return mProviderId;
    }

    public int getCreatorUid() {
        return mCreatorUid;
    }

    @Nullable
    public String getPackageName() {
        return mPackageName;
    }

    public boolean getHasEverConnected() {
        return mHasEverConnected;
    }

    public void setHasEverConnected(boolean hasEverConnected) {
        mHasEverConnected = hasEverConnected;
    }

    public boolean isFromSuggestion() {
        return mIsFromSuggestion;
    }

    /**
     * Enable/disable the auto-join configuration of the corresponding passpoint configuration.
     *
     * @return true if the setting has changed
     */
    public boolean setAutojoinEnabled(boolean autoJoinEnabled) {
        boolean changed = mConfig.isAutojoinEnabled() != autoJoinEnabled;
        mConfig.setAutojoinEnabled(autoJoinEnabled);
        return changed;
    }

    public boolean isAutojoinEnabled() {
        return mConfig.isAutojoinEnabled();
    }

    /**
     * Enable/disable mac randomization for this passpoint profile.
     *
     * @return true if the setting has changed
     */
    public boolean setMacRandomizationEnabled(boolean enabled) {
        boolean changed = mConfig.isMacRandomizationEnabled() != enabled;
        mConfig.setMacRandomizationEnabled(enabled);
        return changed;
    }

    /**
     * Get whether mac randomization is enabled for this passpoint profile.
     */
    public boolean isMacRandomizationEnabled() {
        return mConfig.isMacRandomizationEnabled();
    }

    /**
     * Get the metered override for this passpoint profile.
     *
     * @return true if the setting has changed
     */
    public boolean setMeteredOverride(@MeteredOverride int meteredOverride) {
        boolean changed = mConfig.getMeteredOverride() != meteredOverride;
        mConfig.setMeteredOverride(meteredOverride);
        return changed;
    }

    /**
     * Install certificates and key based on current configuration.
     * Note: the certificates and keys in the configuration will get cleared once
     * they're installed in the keystore.
     *
     * @return true on success
     */
    public boolean installCertsAndKeys() {
        // Install CA certificate.
        X509Certificate[] x509Certificates = mConfig.getCredential().getCaCertificates();
        if (x509Certificates != null) {
            mCaCertificateAliases = new ArrayList<>();
            for (int i = 0; i < x509Certificates.length; i++) {
                String alias = String.format("%s%s_%d", ALIAS_HS_TYPE, mProviderId, i);
                if (!mKeyStore.putCaCertInKeyStore(alias, x509Certificates[i])) {
                    Log.e(TAG, "Failed to install CA Certificate " + alias);
                    uninstallCertsAndKeys();
                    return false;
                } else {
                    mCaCertificateAliases.add(alias);
                }
            }
        }

        // Install the client private key & certificate.
        if (mConfig.getCredential().getClientPrivateKey() != null
                && mConfig.getCredential().getClientCertificateChain() != null
                && mConfig.getCredential().getCertCredential() != null) {
            String keyName = ALIAS_HS_TYPE + mProviderId;
            PrivateKey clientKey = mConfig.getCredential().getClientPrivateKey();
            X509Certificate clientCert = getClientCertificate(
                    mConfig.getCredential().getClientCertificateChain(),
                    mConfig.getCredential().getCertCredential().getCertSha256Fingerprint());
            if (clientCert == null) {
                Log.e(TAG, "Failed to locate client certificate");
                uninstallCertsAndKeys();
                return false;
            }
            if (!mKeyStore.putUserPrivKeyAndCertsInKeyStore(
                    keyName, clientKey, new Certificate[]{clientCert})) {
                Log.e(TAG, "Failed to install client private key or certificate");
                uninstallCertsAndKeys();
                return false;
            }
            mClientPrivateKeyAndCertificateAlias = keyName;
        }

        if (mConfig.getSubscriptionUpdate() != null) {
            X509Certificate certificate = mConfig.getSubscriptionUpdate().getCaCertificate();
            if (certificate == null) {
                Log.e(TAG, "Failed to locate CA certificate for remediation");
                uninstallCertsAndKeys();
                return false;
            }
            String certName = ALIAS_HS_TYPE + ALIAS_ALIAS_REMEDIATION_TYPE + mProviderId;
            if (!mKeyStore.putCaCertInKeyStore(certName, certificate)) {
                Log.e(TAG, "Failed to install CA certificate for remediation");
                uninstallCertsAndKeys();
                return false;
            }
            mRemediationCaCertificateAlias = certName;
        }

        // Clear the keys and certificates in the configuration.
        mConfig.getCredential().setCaCertificates(null);
        mConfig.getCredential().setClientPrivateKey(null);
        mConfig.getCredential().setClientCertificateChain(null);
        if (mConfig.getSubscriptionUpdate() != null) {
            mConfig.getSubscriptionUpdate().setCaCertificate(null);
        }
        return true;
    }

    /**
     * Remove any installed certificates and key.
     */
    public void uninstallCertsAndKeys() {
        if (mCaCertificateAliases != null) {
            for (String certificateAlias : mCaCertificateAliases) {
                if (!mKeyStore.removeEntryFromKeyStore(certificateAlias)) {
                    Log.e(TAG, "Failed to remove entry: " + certificateAlias);
                }
            }
            mCaCertificateAliases = null;
        }
        if (mClientPrivateKeyAndCertificateAlias != null) {
            if (!mKeyStore.removeEntryFromKeyStore(mClientPrivateKeyAndCertificateAlias)) {
                Log.e(TAG, "Failed to remove entry: " + mClientPrivateKeyAndCertificateAlias);
            }
            mClientPrivateKeyAndCertificateAlias = null;
        }
        if (mRemediationCaCertificateAlias != null) {
            if (!mKeyStore.removeEntryFromKeyStore(mRemediationCaCertificateAlias)) {
                Log.e(TAG, "Failed to remove entry: " + mRemediationCaCertificateAlias);
            }
            mRemediationCaCertificateAlias = null;
        }
    }

    /**
     * Try to update the carrier ID according to the IMSI parameter of passpoint configuration.
     *
     * @return true if the carrier ID is updated, otherwise false.
     */
    public boolean tryUpdateCarrierId() {
        return mWifiCarrierInfoManager.tryUpdateCarrierIdForPasspoint(mConfig);
    }

    private @Nullable String getMatchingSimImsi() {
        String matchingSIMImsi = null;
        if (mConfig.getSubscriptionId() != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            matchingSIMImsi = mWifiCarrierInfoManager
                    .getMatchingImsiBySubId(mConfig.getSubscriptionId());
        } else if (mConfig.getCarrierId() != TelephonyManager.UNKNOWN_CARRIER_ID) {
            matchingSIMImsi = mWifiCarrierInfoManager.getMatchingImsiBySubId(
                    mWifiCarrierInfoManager.getMatchingSubId(mConfig.getCarrierId()));
        } else {
            // Get the IMSI and carrier ID of SIM card which match with the IMSI prefix from
            // passpoint profile
            Pair<String, Integer> imsiCarrierIdPair = mWifiCarrierInfoManager
                    .getMatchingImsiCarrierId(mConfig.getCredential().getSimCredential().getImsi());
            if (imsiCarrierIdPair != null) {
                matchingSIMImsi = imsiCarrierIdPair.first;
                mBestGuessCarrierId = imsiCarrierIdPair.second;
            }
        }

        return matchingSIMImsi;
    }

    /**
     * Return the matching status with the given AP, based on the ANQP elements from the AP.
     *
     * @param anqpElements            ANQP elements from the AP
     * @param roamingConsortiumFromAp Roaming Consortium information element from the AP
     * @param scanResult              Latest Scan result
     * @return {@link PasspointMatch}
     */
    public PasspointMatch match(Map<ANQPElementType, ANQPElement> anqpElements,
            RoamingConsortium roamingConsortiumFromAp, ScanResult scanResult) {
        sweepMatchedRcoiMap();
        if (isProviderBlocked(scanResult)) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Provider " + mConfig.getServiceFriendlyName()
                        + " is blocked because reauthentication delay duration is still in"
                        + " progess");
            }
            return PasspointMatch.None;
        }

        // If the profile requires a SIM credential, make sure that the installed SIM matches
        String matchingSimImsi = null;
        if (mConfig.getCredential().getSimCredential() != null) {
            matchingSimImsi = getMatchingSimImsi();
            if (TextUtils.isEmpty(matchingSimImsi)) {
                if (mVerboseLoggingEnabled) {
                    Log.d(TAG, "No SIM card with IMSI "
                            + mConfig.getCredential().getSimCredential().getImsi()
                            + " is installed, final match: " + PasspointMatch.None);
                }
                return PasspointMatch.None;
            }
        }

        // Match FQDN for Home provider or RCOI(s) for Roaming provider
        // For SIM credential, the FQDN is in the format of wlan.mnc*.mcc*.3gppnetwork.org
        PasspointMatch providerMatch = matchFqdnAndRcoi(anqpElements, roamingConsortiumFromAp,
                matchingSimImsi, scanResult);

        // 3GPP Network matching
        if (providerMatch == PasspointMatch.None && ANQPMatcher.matchThreeGPPNetwork(
                (ThreeGPPNetworkElement) anqpElements.get(ANQPElementType.ANQP3GPPNetwork),
                mImsiParameter, matchingSimImsi)) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Final RoamingProvider match with "
                        + anqpElements.get(ANQPElementType.ANQP3GPPNetwork));
            }
            return PasspointMatch.RoamingProvider;
        }

        // Perform NAI Realm matching
        boolean realmMatch = ANQPMatcher.matchNAIRealm(
                (NAIRealmElement) anqpElements.get(ANQPElementType.ANQPNAIRealm),
                mConfig.getCredential().getRealm());

        // In case of no realm match, return provider match as is.
        if (!realmMatch) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "No NAI realm match, final match: " + providerMatch);
            }
            return providerMatch;
        }

        if (mVerboseLoggingEnabled) {
            Log.d(TAG, "NAI realm match with " + mConfig.getCredential().getRealm());
        }

        // Promote the provider match to RoamingProvider if provider match is not found, but NAI
        // realm is matched.
        if (providerMatch == PasspointMatch.None) {
            providerMatch = PasspointMatch.RoamingProvider;
        }

        if (mVerboseLoggingEnabled) {
            Log.d(TAG, "Final match: " + providerMatch);
        }
        return providerMatch;
    }

    /**
     * Generate a WifiConfiguration based on the provider's configuration.  The generated
     * WifiConfiguration will include all the necessary credentials for network connection except
     * the SSID, which should be added by the caller when the config is being used for network
     * connection.
     *
     * @return {@link WifiConfiguration}
     */
    public WifiConfiguration getWifiConfig() {
        WifiConfiguration wifiConfig = new WifiConfiguration();

        List<SecurityParams> paramsList = Arrays.asList(
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_PASSPOINT_R1_R2),
                SecurityParams.createSecurityParamsBySecurityType(
                        WifiConfiguration.SECURITY_TYPE_PASSPOINT_R3));
        wifiConfig.setSecurityParams(paramsList);

        wifiConfig.FQDN = mConfig.getHomeSp().getFqdn();
        wifiConfig.setPasspointUniqueId(mConfig.getUniqueId());
        if (mConfig.getHomeSp().getRoamingConsortiumOis() != null) {
            wifiConfig.roamingConsortiumIds = Arrays.copyOf(
                    mConfig.getHomeSp().getRoamingConsortiumOis(),
                    mConfig.getHomeSp().getRoamingConsortiumOis().length);
        }
        if (mConfig.getUpdateIdentifier() != Integer.MIN_VALUE) {
            // R2 profile, it needs to set updateIdentifier HS2.0 Indication element as PPS MO
            // ID in Association Request.
            wifiConfig.updateIdentifier = Integer.toString(mConfig.getUpdateIdentifier());
            if (isMeteredNetwork(mConfig)) {
                wifiConfig.meteredOverride = WifiConfiguration.METERED_OVERRIDE_METERED;
            }
        }
        wifiConfig.providerFriendlyName = mConfig.getHomeSp().getFriendlyName();
        int carrierId = mConfig.getCarrierId();
        if (carrierId == TelephonyManager.UNKNOWN_CARRIER_ID) {
            carrierId = mBestGuessCarrierId;
        }
        wifiConfig.carrierId = carrierId;
        if (mConfig.getSubscriptionGroup() != null) {
            wifiConfig.setSubscriptionGroup(mConfig.getSubscriptionGroup());
            wifiConfig.subscriptionId = mWifiCarrierInfoManager
                    .getActiveSubscriptionIdInGroup(wifiConfig.getSubscriptionGroup());
        } else {
            wifiConfig.subscriptionId =
                    mConfig.getSubscriptionId() == SubscriptionManager.INVALID_SUBSCRIPTION_ID
                            ? mWifiCarrierInfoManager.getMatchingSubId(carrierId)
                            : mConfig.getSubscriptionId();
        }

        wifiConfig.carrierMerged = mConfig.isCarrierMerged();
        wifiConfig.oemPaid = mConfig.isOemPaid();
        wifiConfig.oemPrivate = mConfig.isOemPrivate();

        WifiEnterpriseConfig enterpriseConfig = new WifiEnterpriseConfig();
        enterpriseConfig.setRealm(mConfig.getCredential().getRealm());
        enterpriseConfig.setDomainSuffixMatch(mConfig.getHomeSp().getFqdn());
        enterpriseConfig.setMinimumTlsVersion(mConfig.getCredential().getMinimumTlsVersion());
        if (mConfig.getCredential().getUserCredential() != null) {
            buildEnterpriseConfigForUserCredential(enterpriseConfig,
                    mConfig.getCredential().getUserCredential());
            setAnonymousIdentityToNaiRealm(enterpriseConfig, mConfig.getCredential().getRealm());
        } else if (mConfig.getCredential().getCertCredential() != null) {
            buildEnterpriseConfigForCertCredential(enterpriseConfig);
            setAnonymousIdentityToNaiRealm(enterpriseConfig, mConfig.getCredential().getRealm());
        } else {
            buildEnterpriseConfigForSimCredential(enterpriseConfig,
                    mConfig.getCredential().getSimCredential());
            enterpriseConfig.setAnonymousIdentity(mAnonymousIdentity);
        }
        // If AAA server trusted names are specified, use it to replace HOME SP FQDN
        // and use system CA regardless of provisioned CA certificate.
        if (!ArrayUtils.isEmpty(mConfig.getAaaServerTrustedNames())) {
            enterpriseConfig.setDomainSuffixMatch(
                    String.join(";", mConfig.getAaaServerTrustedNames()));
            enterpriseConfig.setCaPath(SYSTEM_CA_STORE_PATH);
        }
        if (SdkLevel.isAtLeastS()) {
            enterpriseConfig.setDecoratedIdentityPrefix(mConfig.getDecoratedIdentityPrefix());
        }
        wifiConfig.enterpriseConfig = enterpriseConfig;
        // PPS MO Credential/CheckAAAServerCertStatus node contains a flag which indicates
        // if the mobile device needs to check the AAA server certificate's revocation status
        // during EAP authentication.
        if (mConfig.getCredential().getCheckAaaServerCertStatus()) {
            // Check server certificate using OCSP (Online Certificate Status Protocol).
            wifiConfig.enterpriseConfig.setOcsp(WifiEnterpriseConfig.OCSP_REQUIRE_CERT_STATUS);
        }
        wifiConfig.allowAutojoin = isAutojoinEnabled();
        wifiConfig.shared = mIsShared;
        wifiConfig.fromWifiNetworkSuggestion = mIsFromSuggestion;
        wifiConfig.ephemeral = mIsFromSuggestion;
        wifiConfig.creatorName = mPackageName;
        wifiConfig.creatorUid = mCreatorUid;
        wifiConfig.trusted = mIsTrusted;
        wifiConfig.restricted = mIsRestricted;
        if (mConfig.isMacRandomizationEnabled()) {
            if (mConfig.isNonPersistentMacRandomizationEnabled()) {
                wifiConfig.macRandomizationSetting = WifiConfiguration.RANDOMIZATION_NON_PERSISTENT;
            } else {
                wifiConfig.macRandomizationSetting = WifiConfiguration.RANDOMIZATION_PERSISTENT;
            }
        } else {
            wifiConfig.macRandomizationSetting = WifiConfiguration.RANDOMIZATION_NONE;
        }
        wifiConfig.meteredOverride = mConfig.getMeteredOverride();
        wifiConfig.getNetworkSelectionStatus().setConnectChoice(mConnectChoice);
        wifiConfig.getNetworkSelectionStatus().setConnectChoiceRssi(mConnectChoiceRssi);
        return wifiConfig;
    }

    /**
     * @return true if provider is backed by a SIM credential.
     */
    public boolean isSimCredential() {
        return mConfig.getCredential().getSimCredential() != null;
    }

    /**
     * Convert a legacy {@link WifiConfiguration} representation of a Passpoint configuration to
     * a {@link PasspointConfiguration}.  This is used for migrating legacy Passpoint
     * configuration (release N and older).
     *
     * @param wifiConfig The {@link WifiConfiguration} to convert
     * @return {@link PasspointConfiguration}
     */
    public static PasspointConfiguration convertFromWifiConfig(WifiConfiguration wifiConfig) {
        PasspointConfiguration passpointConfig = new PasspointConfiguration();

        // Setup HomeSP.
        HomeSp homeSp = new HomeSp();
        if (TextUtils.isEmpty(wifiConfig.FQDN)) {
            Log.e(TAG, "Missing FQDN");
            return null;
        }
        homeSp.setFqdn(wifiConfig.FQDN);
        homeSp.setFriendlyName(wifiConfig.providerFriendlyName);
        if (wifiConfig.roamingConsortiumIds != null) {
            homeSp.setRoamingConsortiumOis(Arrays.copyOf(
                    wifiConfig.roamingConsortiumIds, wifiConfig.roamingConsortiumIds.length));
        }
        passpointConfig.setHomeSp(homeSp);
        passpointConfig.setCarrierId(wifiConfig.carrierId);

        // Setup Credential.
        Credential credential = new Credential();
        credential.setRealm(wifiConfig.enterpriseConfig.getRealm());
        switch (wifiConfig.enterpriseConfig.getEapMethod()) {
            case WifiEnterpriseConfig.Eap.TTLS:
                credential.setUserCredential(buildUserCredentialFromEnterpriseConfig(
                        wifiConfig.enterpriseConfig));
                break;
            case WifiEnterpriseConfig.Eap.TLS:
                Credential.CertificateCredential certCred = new Credential.CertificateCredential();
                certCred.setCertType(Credential.CertificateCredential.CERT_TYPE_X509V3);
                credential.setCertCredential(certCred);
                break;
            case WifiEnterpriseConfig.Eap.SIM:
                credential.setSimCredential(buildSimCredentialFromEnterpriseConfig(
                        EAPConstants.EAP_SIM, wifiConfig.enterpriseConfig));
                break;
            case WifiEnterpriseConfig.Eap.AKA:
                credential.setSimCredential(buildSimCredentialFromEnterpriseConfig(
                        EAPConstants.EAP_AKA, wifiConfig.enterpriseConfig));
                break;
            case WifiEnterpriseConfig.Eap.AKA_PRIME:
                credential.setSimCredential(buildSimCredentialFromEnterpriseConfig(
                        EAPConstants.EAP_AKA_PRIME, wifiConfig.enterpriseConfig));
                break;
            default:
                Log.e(TAG, "Unsupported EAP method: "
                        + wifiConfig.enterpriseConfig.getEapMethod());
                return null;
        }
        if (credential.getUserCredential() == null && credential.getCertCredential() == null
                && credential.getSimCredential() == null) {
            Log.e(TAG, "Missing credential");
            return null;
        }
        passpointConfig.setCredential(credential);

        return passpointConfig;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof PasspointProvider)) {
            return false;
        }
        PasspointProvider that = (PasspointProvider) thatObject;
        return mProviderId == that.mProviderId
                && (mCaCertificateAliases == null ? that.mCaCertificateAliases == null
                : mCaCertificateAliases.equals(that.mCaCertificateAliases))
                && TextUtils.equals(mClientPrivateKeyAndCertificateAlias,
                that.mClientPrivateKeyAndCertificateAlias)
                && (mConfig == null ? that.mConfig == null : mConfig.equals(that.mConfig))
                && TextUtils.equals(mRemediationCaCertificateAlias,
                that.mRemediationCaCertificateAlias);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mProviderId, mCaCertificateAliases,
                mClientPrivateKeyAndCertificateAlias, mConfig, mRemediationCaCertificateAlias);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("ProviderId: ").append(mProviderId).append("\n");
        builder.append("CreatorUID: ").append(mCreatorUid).append("\n");
        builder.append("Best guess Carrier ID: ").append(mBestGuessCarrierId).append("\n");
        builder.append("Ever connected: ").append(mHasEverConnected).append("\n");
        builder.append("Shared: ").append(mIsShared).append("\n");
        builder.append("Suggestion: ").append(mIsFromSuggestion).append("\n");
        builder.append("Trusted: ").append(mIsTrusted).append("\n");
        builder.append("Restricted: ").append(mIsRestricted).append("\n");
        builder.append("UserConnectChoice: ").append(mConnectChoice).append("\n");
        if (mReauthDelay != 0 && mClock.getElapsedSinceBootMillis() < mReauthDelay) {
            builder.append("Reauth delay remaining (seconds): ")
                    .append((mReauthDelay - mClock.getElapsedSinceBootMillis()) / 1000)
                    .append("\n");
            if (mBlockedBssids.isEmpty()) {
                builder.append("ESS is blocked").append("\n");
            } else {
                builder.append("List of blocked BSSIDs:").append("\n");
                for (String bssid : mBlockedBssids) {
                    builder.append(bssid).append("\n");
                }
            }
        } else {
            builder.append("Provider is not blocked").append("\n");
        }

        if (mPackageName != null) {
            builder.append("PackageName: ").append(mPackageName).append("\n");
        }
        builder.append("Configuration Begin ---\n");
        builder.append(mConfig);
        builder.append("Configuration End ---\n");
        builder.append("WifiConfiguration Begin ---\n");
        builder.append(getWifiConfig());
        builder.append("WifiConfiguration End ---\n");
        return builder.toString();
    }

    /**
     * Retrieve the client certificate from the certificates chain.  The certificate
     * with the matching SHA256 digest is the client certificate.
     *
     * @param certChain                 The client certificates chain
     * @param expectedSha256Fingerprint The expected SHA256 digest of the client certificate
     * @return {@link java.security.cert.X509Certificate}
     */
    private static X509Certificate getClientCertificate(X509Certificate[] certChain,
            byte[] expectedSha256Fingerprint) {
        if (certChain == null) {
            return null;
        }
        try {
            MessageDigest digester = MessageDigest.getInstance("SHA-256");
            for (X509Certificate certificate : certChain) {
                digester.reset();
                byte[] fingerprint = digester.digest(certificate.getEncoded());
                if (Arrays.equals(expectedSha256Fingerprint, fingerprint)) {
                    return certificate;
                }
            }
        } catch (CertificateEncodingException | NoSuchAlgorithmException e) {
            return null;
        }

        return null;
    }

    /**
     * Determines the Passpoint network is a metered network.
     *
     * Expiration date -> non-metered
     * Data limit -> metered
     * Time usage limit -> metered
     *
     * @param passpointConfig instance of {@link PasspointConfiguration}
     * @return {@code true} if the network is a metered network, {@code false} otherwise.
     */
    private boolean isMeteredNetwork(PasspointConfiguration passpointConfig) {
        if (passpointConfig == null) return false;

        // If DataLimit is zero, there is unlimited data usage for the account.
        // If TimeLimit is zero, there is unlimited time usage for the account.
        return passpointConfig.getUsageLimitDataLimit() > 0
                || passpointConfig.getUsageLimitTimeLimitInMinutes() > 0;
    }

    /**
     * Match given OIs to the Roaming Consortium OIs
     *
     * @param providerOis              Provider OIs to match against
     * @param roamingConsortiumElement RCOIs in the ANQP element
     * @param roamingConsortiumFromAp  RCOIs in the AP scan results
     * @param matchAll                 Indicates if all providerOis must match the RCOIs elements
     * @return OI value if there is a match, 0 otherwise. If matachAll is true, then this method
     * returns the first matched OI.
     */
    private long matchOis(long[] providerOis,
            RoamingConsortiumElement roamingConsortiumElement,
            RoamingConsortium roamingConsortiumFromAp,
            boolean matchAll) {
        // ANQP Roaming Consortium OI matching.
        long matchedRcoi = ANQPMatcher.matchRoamingConsortium(roamingConsortiumElement, providerOis,
                matchAll);
        if (matchedRcoi != 0) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, String.format("ANQP RCOI match: 0x%x", matchedRcoi));
            }
            return matchedRcoi;
        }

        // AP Roaming Consortium OI matching.
        long[] apRoamingConsortiums = roamingConsortiumFromAp.getRoamingConsortiums();
        if (apRoamingConsortiums == null || providerOis == null) {
            return 0;
        }
        // Roaming Consortium OI information element matching.
        for (long apOi : apRoamingConsortiums) {
            boolean matched = false;
            for (long providerOi : providerOis) {
                if (apOi == providerOi) {
                    if (mVerboseLoggingEnabled) {
                        Log.d(TAG, String.format("AP RCOI match: 0x%x", apOi));
                    }
                    if (!matchAll) {
                        return apOi;
                    } else {
                        matched = true;
                        if (matchedRcoi == 0) matchedRcoi = apOi;
                        break;
                    }
                }
            }
            if (matchAll && !matched) {
                return 0;
            }
        }
        return matchedRcoi;
    }

    /**
     * Perform a provider match based on the given ANQP elements for FQDN and RCOI
     *
     * @param anqpElements            List of ANQP elements
     * @param roamingConsortiumFromAp Roaming Consortium information element from the AP
     * @param matchingSIMImsi         Installed SIM IMSI that matches the SIM credential ANQP
     *                                element
     * @param scanResult              The relevant scan result
     * @return {@link PasspointMatch}
     */
    private PasspointMatch matchFqdnAndRcoi(Map<ANQPElementType, ANQPElement> anqpElements,
            RoamingConsortium roamingConsortiumFromAp, String matchingSIMImsi,
            ScanResult scanResult) {
        // Domain name matching.
        if (ANQPMatcher.matchDomainName(
                (DomainNameElement) anqpElements.get(ANQPElementType.ANQPDomName),
                mConfig.getHomeSp().getFqdn(), mImsiParameter, matchingSIMImsi)) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Domain name " + mConfig.getHomeSp().getFqdn()
                        + " match: HomeProvider");
            }
            return PasspointMatch.HomeProvider;
        }

        // Other Home Partners matching.
        if (mConfig.getHomeSp().getOtherHomePartners() != null) {
            for (String otherHomePartner : mConfig.getHomeSp().getOtherHomePartners()) {
                if (ANQPMatcher.matchDomainName(
                        (DomainNameElement) anqpElements.get(ANQPElementType.ANQPDomName),
                        otherHomePartner, null, null)) {
                    if (mVerboseLoggingEnabled) {
                        Log.d(TAG, "Other Home Partner " + otherHomePartner
                                + " match: HomeProvider");
                    }
                    return PasspointMatch.HomeProvider;
                }
            }
        }

        // HomeOI matching
        if (mConfig.getHomeSp().getMatchAllOis() != null) {
            // Ensure that every HomeOI whose corresponding HomeOIRequired value is true shall match
            // an OI in the Roaming Consortium advertised by the hotspot operator.
            if (matchOis(mConfig.getHomeSp().getMatchAllOis(),
                    (RoamingConsortiumElement) anqpElements.get(
                            ANQPElementType.ANQPRoamingConsortium),
                    roamingConsortiumFromAp, true) != 0) {
                if (mVerboseLoggingEnabled) {
                    Log.d(TAG, "All HomeOI RCOI match: HomeProvider");
                }
                return PasspointMatch.HomeProvider;
            }
        } else if (mConfig.getHomeSp().getMatchAnyOis() != null) {
            // Ensure that any HomeOI whose corresponding HomeOIRequired value is false shall match
            // an OI in the Roaming Consortium advertised by the hotspot operator.
            if (matchOis(mConfig.getHomeSp().getMatchAnyOis(),
                    (RoamingConsortiumElement) anqpElements.get(
                            ANQPElementType.ANQPRoamingConsortium),
                    roamingConsortiumFromAp, false) != 0) {
                if (mVerboseLoggingEnabled) {
                    Log.d(TAG, "Any HomeOI RCOI match: HomeProvider");
                }
                return PasspointMatch.HomeProvider;
            }
        }

        // Roaming Consortium OI matching.
        long matchedRcoi = matchOis(mConfig.getHomeSp().getRoamingConsortiumOis(),
                (RoamingConsortiumElement) anqpElements.get(ANQPElementType.ANQPRoamingConsortium),
                roamingConsortiumFromAp, false);
        if (matchedRcoi != 0) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, String.format("RCOI match: RoamingProvider, selected RCOI = 0x%x",
                        matchedRcoi));
            }
            addMatchedRcoi(scanResult, matchedRcoi);
            return PasspointMatch.RoamingProvider;
        }

        if (mVerboseLoggingEnabled) {
            Log.d(TAG, "No domain name or RCOI match");
        }
        return PasspointMatch.None;
    }

    /**
     * Fill in WifiEnterpriseConfig with information from an user credential.
     *
     * @param config     Instance of {@link WifiEnterpriseConfig}
     * @param credential Instance of {@link UserCredential}
     */
    private void buildEnterpriseConfigForUserCredential(WifiEnterpriseConfig config,
            Credential.UserCredential credential) {
        String password;
        try {
            byte[] pwOctets = Base64.decode(credential.getPassword(), Base64.DEFAULT);
            password = new String(pwOctets, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Failed to decode password");
            password = credential.getPassword();
        }
        config.setEapMethod(WifiEnterpriseConfig.Eap.TTLS);
        config.setIdentity(credential.getUsername());
        config.setPassword(password);
        if (!ArrayUtils.isEmpty(mCaCertificateAliases)) {
            config.setCaCertificateAliases(mCaCertificateAliases.toArray(new String[0]));
        } else {
            config.setCaPath(SYSTEM_CA_STORE_PATH);
        }
        int phase2Method = WifiEnterpriseConfig.Phase2.NONE;
        switch (credential.getNonEapInnerMethod()) {
            case Credential.UserCredential.AUTH_METHOD_PAP:
                phase2Method = WifiEnterpriseConfig.Phase2.PAP;
                break;
            case Credential.UserCredential.AUTH_METHOD_MSCHAP:
                phase2Method = WifiEnterpriseConfig.Phase2.MSCHAP;
                break;
            case Credential.UserCredential.AUTH_METHOD_MSCHAPV2:
                phase2Method = WifiEnterpriseConfig.Phase2.MSCHAPV2;
                break;
            default:
                // Should never happen since this is already validated when the provider is
                // added.
                Log.wtf(TAG, "Unsupported Auth: " + credential.getNonEapInnerMethod());
                break;
        }
        config.setPhase2Method(phase2Method);
    }

    /**
     * Fill in WifiEnterpriseConfig with information from a certificate credential.
     *
     * @param config Instance of {@link WifiEnterpriseConfig}
     */
    private void buildEnterpriseConfigForCertCredential(WifiEnterpriseConfig config) {
        config.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
        config.setClientCertificateAlias(mClientPrivateKeyAndCertificateAlias);
        if (!ArrayUtils.isEmpty(mCaCertificateAliases)) {
            config.setCaCertificateAliases(mCaCertificateAliases.toArray(new String[0]));
        } else {
            config.setCaPath(SYSTEM_CA_STORE_PATH);
        }
    }

    /**
     * Fill in WifiEnterpriseConfig with information from a SIM credential.
     *
     * @param config     Instance of {@link WifiEnterpriseConfig}
     * @param credential Instance of {@link SimCredential}
     */
    private void buildEnterpriseConfigForSimCredential(WifiEnterpriseConfig config,
            Credential.SimCredential credential) {
        int eapMethod = WifiEnterpriseConfig.Eap.NONE;
        switch (credential.getEapType()) {
            case EAPConstants.EAP_SIM:
                eapMethod = WifiEnterpriseConfig.Eap.SIM;
                break;
            case EAPConstants.EAP_AKA:
                eapMethod = WifiEnterpriseConfig.Eap.AKA;
                break;
            case EAPConstants.EAP_AKA_PRIME:
                eapMethod = WifiEnterpriseConfig.Eap.AKA_PRIME;
                break;
            default:
                // Should never happen since this is already validated when the provider is
                // added.
                Log.wtf(TAG, "Unsupported EAP Method: " + credential.getEapType());
                break;
        }
        config.setEapMethod(eapMethod);
        config.setPlmn(credential.getImsi());
    }

    private static void setAnonymousIdentityToNaiRealm(WifiEnterpriseConfig config, String realm) {
        /**
         * Set WPA supplicant's anonymous identity field to a string containing the NAI realm, so
         * that this value will be sent to the EAP server as part of the EAP-Response/ Identity
         * packet. WPA supplicant will reset this field after using it for the EAP-Response/Identity
         * packet, and revert to using the (real) identity field for subsequent transactions that
         * request an identity (e.g. in EAP-TTLS).
         *
         * This NAI realm value (the portion of the identity after the '@') is used to tell the
         * AAA server which AAA/H to forward packets to. The hardcoded username, "anonymous", is a
         * placeholder that is not used--it is set to this value by convention. See Section 5.1 of
         * RFC3748 for more details.
         *
         * NOTE: we do not set this value for EAP-SIM/AKA/AKA', since the EAP server expects the
         * EAP-Response/Identity packet to contain an actual, IMSI-based identity, in order to
         * identify the device.
         */
        config.setAnonymousIdentity("anonymous@" + realm);
    }

    /**
     * Helper function for creating a
     * {@link android.net.wifi.hotspot2.pps.Credential.UserCredential} from the given
     * {@link WifiEnterpriseConfig}
     *
     * @param config The enterprise configuration containing the credential
     * @return {@link android.net.wifi.hotspot2.pps.Credential.UserCredential}
     */
    private static Credential.UserCredential buildUserCredentialFromEnterpriseConfig(
            WifiEnterpriseConfig config) {
        Credential.UserCredential userCredential = new Credential.UserCredential();
        userCredential.setEapType(EAPConstants.EAP_TTLS);

        if (TextUtils.isEmpty(config.getIdentity())) {
            Log.e(TAG, "Missing username for user credential");
            return null;
        }
        userCredential.setUsername(config.getIdentity());

        if (TextUtils.isEmpty(config.getPassword())) {
            Log.e(TAG, "Missing password for user credential");
            return null;
        }
        String encodedPassword =
                new String(Base64.encode(config.getPassword().getBytes(StandardCharsets.UTF_8),
                        Base64.DEFAULT), StandardCharsets.UTF_8);
        userCredential.setPassword(encodedPassword);

        switch (config.getPhase2Method()) {
            case WifiEnterpriseConfig.Phase2.PAP:
                userCredential.setNonEapInnerMethod(Credential.UserCredential.AUTH_METHOD_PAP);
                break;
            case WifiEnterpriseConfig.Phase2.MSCHAP:
                userCredential.setNonEapInnerMethod(Credential.UserCredential.AUTH_METHOD_MSCHAP);
                break;
            case WifiEnterpriseConfig.Phase2.MSCHAPV2:
                userCredential.setNonEapInnerMethod(Credential.UserCredential.AUTH_METHOD_MSCHAPV2);
                break;
            default:
                Log.e(TAG, "Unsupported phase2 method for TTLS: " + config.getPhase2Method());
                return null;
        }
        return userCredential;
    }

    /**
     * Helper function for creating a
     * {@link android.net.wifi.hotspot2.pps.Credential.SimCredential} from the given
     * {@link WifiEnterpriseConfig}
     *
     * @param eapType The EAP type of the SIM credential
     * @param config  The enterprise configuration containing the credential
     * @return {@link android.net.wifi.hotspot2.pps.Credential.SimCredential}
     */
    private static Credential.SimCredential buildSimCredentialFromEnterpriseConfig(
            int eapType, WifiEnterpriseConfig config) {
        Credential.SimCredential simCredential = new Credential.SimCredential();
        if (TextUtils.isEmpty(config.getPlmn())) {
            Log.e(TAG, "Missing IMSI for SIM credential");
            return null;
        }
        simCredential.setImsi(config.getPlmn());
        simCredential.setEapType(eapType);
        return simCredential;
    }

    /**
     * Enable verbose logging
     *
     * @param verbose enables verbose logging
     */
    public void enableVerboseLogging(boolean verbose) {
        mVerboseLoggingEnabled = verbose;
    }

    /**
     * Block a BSS or ESS following a Deauthentication-Imminent WNM-Notification
     *
     * @param bssid          BSSID of the source AP
     * @param isEss          true: Block ESS, false: Block BSS
     * @param delayInSeconds Delay duration in seconds
     */
    public void blockBssOrEss(long bssid, boolean isEss, int delayInSeconds) {
        if (delayInSeconds < 0 || bssid == 0) {
            return;
        }

        mReauthDelay = mClock.getElapsedSinceBootMillis();
        if (delayInSeconds == 0) {
            // Section 3.2.1.2 in the specification defines that a Re-Auth Delay field
            // value of 0 means the delay value is chosen by the mobile device.
            mReauthDelay += DEFAULT_BLOCKLIST_DURATION_MS;
        } else {
            mReauthDelay += (delayInSeconds * 1000);
        }
        if (isEss) {
            // Deauth-imminent for the entire ESS, do not try to reauthenticate until the delay
            // is over. Clear the list of blocked BSSIDs.
            mBlockedBssids.clear();
        } else {
            // Add this MAC address to the list of blocked BSSIDs.
            mBlockedBssids.add(Utils.macToString(bssid));
        }
    }

    /**
     * Clear a block from a Passpoint provider. Used when Wi-Fi state is cleared, for example,
     * when turning Wi-Fi off.
     */
    public void clearProviderBlock() {
        mReauthDelay = 0;
        mBlockedBssids.clear();
    }

    /**
     * Checks if this provider is blocked or if there are any BSSes blocked
     *
     * @param scanResult Latest scan result
     * @return true if blocked, false otherwise
     */
    private boolean isProviderBlocked(ScanResult scanResult) {
        if (mReauthDelay == 0) {
            return false;
        }

        if (mClock.getElapsedSinceBootMillis() >= mReauthDelay) {
            // Provider was blocked, but the delay duration have passed
            mReauthDelay = 0;
            mBlockedBssids.clear();
            return false;
        }

        // Empty means the entire ESS is blocked
        if (mBlockedBssids.isEmpty() || mBlockedBssids.contains(scanResult.BSSID)) {
            return true;
        }

        // Trying to associate to another BSS in the ESS
        return false;
    }

    /**
     * Set the user connect choice on the passpoint network.
     *
     * @param choice The {@link WifiConfiguration#getProfileKey()} of the user connect
     *               network.
     * @param rssi   The signal strength of the network.
     */
    public void setUserConnectChoice(String choice, int rssi) {
        mConnectChoice = choice;
        mConnectChoiceRssi = rssi;
    }

    public String getConnectChoice() {
        return mConnectChoice;
    }

    public int getConnectChoiceRssi() {
        return mConnectChoiceRssi;
    }

    /**
     * Set the most recent SSID observed for the Passpoint network.
     */
    public void setMostRecentSsid(@Nullable String ssid) {
        if (ssid == null) return;
        mMostRecentSsid = ssid;
    }

    public @Nullable String getMostRecentSsid() {
        return mMostRecentSsid;
    }

    /** Indicate that the most recent connection timestamp should be updated. */
    public void updateMostRecentConnectionTime() {
        mMostRecentConnectionTime = mClock.getWallClockMillis();
    }

    public long getMostRecentConnectionTime() {
        return mMostRecentConnectionTime;
    }

    /**
     * Add a potential RCOI match of the Passpoint provider to a network in the environment
     * @param scanResult Scan result
     * @param matchedRcoi Matched RCOI
     */
    private void addMatchedRcoi(ScanResult scanResult, long matchedRcoi) {
        WifiSsid wifiSsid = scanResult.getWifiSsid();
        if (wifiSsid != null && wifiSsid.getUtf8Text() != null) {
            String ssid = wifiSsid.toString();
            mRcoiMatchForNetwork.put(ssid, new Pair<>(matchedRcoi,
                    mClock.getElapsedSinceBootMillis()));
        }
    }

    /**
     * Get the matched (selected) RCOI for a particular Passpoint network, and remove it from the
     * internal map.
     * @param ssid The SSID of the network
     * @return An RCOI that the provider has matched with the network
     */
    public long getAndRemoveMatchedRcoi(String ssid) {
        if (ssid == null) return 0;
        if (mRcoiMatchForNetwork.isEmpty()) return 0;
        Pair<Long, Long> rcoiMatchEntry = mRcoiMatchForNetwork.get(ssid);
        if (rcoiMatchEntry == null) return 0;
        mRcoiMatchForNetwork.remove(ssid);
        return rcoiMatchEntry.first;
    }

    /**
     * Sweep the match RCOI map and free up old entries
     */
    private void sweepMatchedRcoiMap() {
        if (mRcoiMatchForNetwork.isEmpty()) return;
        mRcoiMatchForNetwork.entrySet().removeIf(
                entry -> (entry.getValue().second + MAX_RCOI_ENTRY_LIFETIME_MS
                        < mClock.getElapsedSinceBootMillis()));
    }
}
