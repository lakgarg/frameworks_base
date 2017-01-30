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

package com.android.server.webkit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.MediumTest;
import android.util.Base64;
import android.webkit.WebViewFactory;
import android.webkit.WebViewProviderInfo;
import android.webkit.WebViewProviderResponse;

import org.hamcrest.Description;

import org.junit.Test;
import org.junit.runner.RunWith;

import org.mockito.Mockito;
import org.mockito.Matchers;
import org.mockito.ArgumentMatcher;

import java.util.concurrent.CountDownLatch;


/**
 * Tests for WebViewUpdateService
 runtest --path frameworks/base/services/tests/servicestests/ \
     -c com.android.server.webkit.WebViewUpdateServiceTest
 */
// Use MediumTest instead of SmallTest as the implementation of WebViewUpdateService
// is intended to work on several threads and uses at least one sleep/wait-statement.
@RunWith(AndroidJUnit4.class)
@MediumTest
public class WebViewUpdateServiceTest {
    private final static String TAG = WebViewUpdateServiceTest.class.getSimpleName();

    private WebViewUpdateServiceImpl mWebViewUpdateServiceImpl;
    private TestSystemImpl mTestSystemImpl;

    private static final String WEBVIEW_LIBRARY_FLAG = "com.android.webview.WebViewLibrary";

    /**
     * Creates a new instance.
     */
    public WebViewUpdateServiceTest() {
    }

    private void setupWithPackages(WebViewProviderInfo[] packages) {
        setupWithPackages(packages, true);
    }

    private void setupWithPackages(WebViewProviderInfo[] packages,
            boolean fallbackLogicEnabled) {
        setupWithPackages(packages, fallbackLogicEnabled, 1);
    }

    private void setupWithPackages(WebViewProviderInfo[] packages,
            boolean fallbackLogicEnabled, int numRelros) {
        setupWithPackages(packages, fallbackLogicEnabled, numRelros,
                true /* isDebuggable == true -> don't check package signatures */);
    }

    private void setupWithPackages(WebViewProviderInfo[] packages,
            boolean fallbackLogicEnabled, int numRelros, boolean isDebuggable) {
        setupWithPackages(packages, fallbackLogicEnabled, numRelros, isDebuggable,
                false /* multiProcessDefault */);
    }

    private void setupWithPackages(WebViewProviderInfo[] packages,
            boolean fallbackLogicEnabled, int numRelros, boolean isDebuggable,
            boolean multiProcessDefault) {
        TestSystemImpl testing = new TestSystemImpl(packages, fallbackLogicEnabled, numRelros,
                isDebuggable, multiProcessDefault);
        mTestSystemImpl = Mockito.spy(testing);
        mWebViewUpdateServiceImpl =
            new WebViewUpdateServiceImpl(null /*Context*/, mTestSystemImpl);
    }

    private void setEnabledAndValidPackageInfos(WebViewProviderInfo[] providers) {
        // Set package infos for the primary user (user 0).
        setEnabledAndValidPackageInfosForUser(TestSystemImpl.PRIMARY_USER_ID, providers);
    }

    private void setEnabledAndValidPackageInfosForUser(int userId,
            WebViewProviderInfo[] providers) {
        for(WebViewProviderInfo wpi : providers) {
            mTestSystemImpl.setPackageInfoForUser(userId, createPackageInfo(wpi.packageName,
                    true /* enabled */, true /* valid */, true /* installed */));
        }
    }

    private void checkCertainPackageUsedAfterWebViewBootPreparation(String expectedProviderName,
            WebViewProviderInfo[] webviewPackages) {
        checkCertainPackageUsedAfterWebViewBootPreparation(
                expectedProviderName, webviewPackages, 1);
    }

    private void checkCertainPackageUsedAfterWebViewBootPreparation(String expectedProviderName,
            WebViewProviderInfo[] webviewPackages, int numRelros) {
        setupWithPackages(webviewPackages, true, numRelros);
        // Add (enabled and valid) package infos for each provider
        setEnabledAndValidPackageInfos(webviewPackages);

        runWebViewBootPreparationOnMainSync();

        Mockito.verify(mTestSystemImpl).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(expectedProviderName)));

        for (int n = 0; n < numRelros; n++) {
            mWebViewUpdateServiceImpl.notifyRelroCreationCompleted();
        }

        WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_SUCCESS, response.status);
        assertEquals(expectedProviderName, response.packageInfo.packageName);
    }

    // For matching the package name of a PackageInfo
    private class IsPackageInfoWithName extends ArgumentMatcher<PackageInfo> {
        private final String mPackageName;

        IsPackageInfoWithName(String name) {
            mPackageName = name;
        }

        @Override
        public boolean matches(Object p) {
            return ((PackageInfo) p).packageName.equals(mPackageName);
        }

        // Provide a more useful description in case of mismatch
        @Override
        public void describeTo (Description description) {
            description.appendText(String.format("PackageInfo with name '%s'", mPackageName));
        }
    }

    private static PackageInfo createPackageInfo(
            String packageName, boolean enabled, boolean valid, boolean installed) {
        PackageInfo p = new PackageInfo();
        p.packageName = packageName;
        p.applicationInfo = new ApplicationInfo();
        p.applicationInfo.enabled = enabled;
        p.applicationInfo.metaData = new Bundle();
        if (installed) {
            p.applicationInfo.flags |= ApplicationInfo.FLAG_INSTALLED;
        } else {
            p.applicationInfo.flags &= ~ApplicationInfo.FLAG_INSTALLED;
        }
        if (valid) {
            // no flag means invalid
            p.applicationInfo.metaData.putString(WEBVIEW_LIBRARY_FLAG, "blah");
        }
        // Default to this package being valid in terms of targetSdkVersion.
        p.applicationInfo.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;
        return p;
    }

    private static PackageInfo createPackageInfo(String packageName, boolean enabled, boolean valid,
            boolean installed, Signature[] signatures, long updateTime) {
        PackageInfo p = createPackageInfo(packageName, enabled, valid, installed);
        p.signatures = signatures;
        p.lastUpdateTime = updateTime;
        return p;
    }

    private static PackageInfo createPackageInfo(String packageName, boolean enabled, boolean valid,
            boolean installed, Signature[] signatures, long updateTime, boolean hidden) {
        PackageInfo p =
            createPackageInfo(packageName, enabled, valid, installed, signatures, updateTime);
        if (hidden) {
            p.applicationInfo.privateFlags |= ApplicationInfo.PRIVATE_FLAG_HIDDEN;
        } else {
            p.applicationInfo.privateFlags &= ~ApplicationInfo.PRIVATE_FLAG_HIDDEN;
        }
        return p;
    }

    private static PackageInfo createPackageInfo(String packageName, boolean enabled, boolean valid,
            boolean installed, Signature[] signatures, long updateTime, boolean hidden,
            int versionCode, boolean isSystemApp) {
        PackageInfo p = createPackageInfo(packageName, enabled, valid, installed, signatures,
                updateTime, hidden);
        p.versionCode = versionCode;
        p.applicationInfo.versionCode = versionCode;
        if (isSystemApp) p.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        return p;
    }

    private void checkPreparationPhasesForPackage(String expectedPackage, int numPreparation) {
        // Verify that onWebViewProviderChanged was called for the numPreparation'th time for the
        // expected package
        Mockito.verify(mTestSystemImpl, Mockito.times(numPreparation)).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(expectedPackage)));

        mWebViewUpdateServiceImpl.notifyRelroCreationCompleted();

        WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_SUCCESS, response.status);
        assertEquals(expectedPackage, response.packageInfo.packageName);
    }

    /**
     * The WebView preparation boot phase is run on the main thread (especially on a thread with a
     * looper) so to avoid bugs where our tests fail because a looper hasn't been attached to the
     * thread running prepareWebViewInSystemServer we run it on the main thread.
     */
    private void runWebViewBootPreparationOnMainSync() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                mWebViewUpdateServiceImpl.prepareWebViewInSystemServer();
            }
        });
    }


    // ****************
    // Tests
    // ****************


    @Test
    public void testWithSinglePackage() {
        String testPackageName = "test.package.name";
        checkCertainPackageUsedAfterWebViewBootPreparation(testPackageName,
                new WebViewProviderInfo[] {
                    new WebViewProviderInfo(testPackageName, "",
                            true /*default available*/, false /* fallback */, null)});
    }

    @Test
    public void testDefaultPackageUsedOverNonDefault() {
        String defaultPackage = "defaultPackage";
        String nonDefaultPackage = "nonDefaultPackage";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(nonDefaultPackage, "", false, false, null),
            new WebViewProviderInfo(defaultPackage, "", true, false, null)};
        checkCertainPackageUsedAfterWebViewBootPreparation(defaultPackage, packages);
    }

    @Test
    public void testSeveralRelros() {
        String singlePackage = "singlePackage";
        checkCertainPackageUsedAfterWebViewBootPreparation(
                singlePackage,
                new WebViewProviderInfo[] {
                    new WebViewProviderInfo(singlePackage, "", true /*def av*/, false, null)},
                2);
    }

    // Ensure that package with valid signatures is chosen rather than package with invalid
    // signatures.
    @Test
    public void testWithSignatures() {
        String validPackage = "valid package";
        String invalidPackage = "invalid package";

        Signature validSignature = new Signature("11");
        Signature invalidExpectedSignature = new Signature("22");
        Signature invalidPackageSignature = new Signature("33");

        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(invalidPackage, "", true, false, new String[]{
                        Base64.encodeToString(
                                invalidExpectedSignature.toByteArray(), Base64.DEFAULT)}),
            new WebViewProviderInfo(validPackage, "", true, false, new String[]{
                        Base64.encodeToString(
                                validSignature.toByteArray(), Base64.DEFAULT)})
        };
        setupWithPackages(packages, true /* fallback logic enabled */, 1 /* numRelros */,
                false /* isDebuggable */);
        mTestSystemImpl.setPackageInfo(createPackageInfo(invalidPackage, true /* enabled */,
                    true /* valid */, true /* installed */, new Signature[]{invalidPackageSignature}
                    , 0 /* updateTime */));
        mTestSystemImpl.setPackageInfo(createPackageInfo(validPackage, true /* enabled */,
                    true /* valid */, true /* installed */, new Signature[]{validSignature}
                    , 0 /* updateTime */));

        runWebViewBootPreparationOnMainSync();


        checkPreparationPhasesForPackage(validPackage, 1 /* first preparation for this package */);

        WebViewProviderInfo[] validPackages = mWebViewUpdateServiceImpl.getValidWebViewPackages();
        assertEquals(1, validPackages.length);
        assertEquals(validPackage, validPackages[0].packageName);
    }

    @Test
    public void testFailWaitingForRelro() {
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo("packagename", "", true, true, null)};
        setupWithPackages(packages);
        setEnabledAndValidPackageInfos(packages);

        runWebViewBootPreparationOnMainSync();

        Mockito.verify(mTestSystemImpl).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(packages[0].packageName)));

        // Never call notifyRelroCreation()

        WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_FAILED_WAITING_FOR_RELRO, response.status);
    }

    @Test
    public void testFailListingEmptyWebviewPackages() {
        WebViewProviderInfo[] packages = new WebViewProviderInfo[0];
        setupWithPackages(packages);
        setEnabledAndValidPackageInfos(packages);

        runWebViewBootPreparationOnMainSync();

        Mockito.verify(mTestSystemImpl, Mockito.never()).onWebViewProviderChanged(
                Matchers.anyObject());

        WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_FAILED_LISTING_WEBVIEW_PACKAGES, response.status);
        assertEquals(null, mWebViewUpdateServiceImpl.getCurrentWebViewPackage());

        // Now install a package
        String singlePackage = "singlePackage";
        packages = new WebViewProviderInfo[]{
            new WebViewProviderInfo(singlePackage, "", true, false, null)};
        setupWithPackages(packages);
        setEnabledAndValidPackageInfos(packages);

        mWebViewUpdateServiceImpl.packageStateChanged(singlePackage,
                WebViewUpdateService.PACKAGE_ADDED, TestSystemImpl.PRIMARY_USER_ID);

        checkPreparationPhasesForPackage(singlePackage, 1 /* number of finished preparations */);
        assertEquals(singlePackage,
                mWebViewUpdateServiceImpl.getCurrentWebViewPackage().packageName);

        // Remove the package again
        mTestSystemImpl.removePackageInfo(singlePackage);
        mWebViewUpdateServiceImpl.packageStateChanged(singlePackage,
                WebViewUpdateService.PACKAGE_ADDED, TestSystemImpl.PRIMARY_USER_ID);

        // Package removed - ensure our interface states that there is no package
        response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_FAILED_LISTING_WEBVIEW_PACKAGES, response.status);
        assertEquals(null, mWebViewUpdateServiceImpl.getCurrentWebViewPackage());
    }

    @Test
    public void testFailListingInvalidWebviewPackage() {
        WebViewProviderInfo wpi = new WebViewProviderInfo("package", "", true, true, null);
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {wpi};
        setupWithPackages(packages);
        mTestSystemImpl.setPackageInfo(
                createPackageInfo(wpi.packageName, true /* enabled */, false /* valid */,
                    true /* installed */));

        runWebViewBootPreparationOnMainSync();

        Mockito.verify(mTestSystemImpl, Mockito.never()).onWebViewProviderChanged(
                Matchers.anyObject());

        WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_FAILED_LISTING_WEBVIEW_PACKAGES, response.status);

        // Verify that we can recover from failing to list webview packages.
        mTestSystemImpl.setPackageInfo(
                createPackageInfo(wpi.packageName, true /* enabled */, true /* valid */,
                    true /* installed */));
        mWebViewUpdateServiceImpl.packageStateChanged(wpi.packageName,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED, TestSystemImpl.PRIMARY_USER_ID);

        checkPreparationPhasesForPackage(wpi.packageName, 1);
    }

    // Test that switching provider using changeProviderAndSetting works.
    @Test
    public void testSwitchingProvider() {
        String firstPackage = "first";
        String secondPackage = "second";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(firstPackage, "", true, false, null),
            new WebViewProviderInfo(secondPackage, "", true, false, null)};
        checkSwitchingProvider(packages, firstPackage, secondPackage);
    }

    @Test
    public void testSwitchingProviderToNonDefault() {
        String defaultPackage = "defaultPackage";
        String nonDefaultPackage = "nonDefaultPackage";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(defaultPackage, "", true, false, null),
            new WebViewProviderInfo(nonDefaultPackage, "", false, false, null)};
        checkSwitchingProvider(packages, defaultPackage, nonDefaultPackage);
    }

    private void checkSwitchingProvider(WebViewProviderInfo[] packages, String initialPackage,
            String finalPackage) {
        checkCertainPackageUsedAfterWebViewBootPreparation(initialPackage, packages);

        mWebViewUpdateServiceImpl.changeProviderAndSetting(finalPackage);
        checkPreparationPhasesForPackage(finalPackage, 1 /* first preparation for this package */);

        Mockito.verify(mTestSystemImpl).killPackageDependents(Mockito.eq(initialPackage));
    }

    // Change provider during relro creation by using changeProviderAndSetting
    @Test
    public void testSwitchingProviderDuringRelroCreation() {
        checkChangingProviderDuringRelroCreation(true);
    }

    // Change provider during relro creation by enabling a provider
    @Test
    public void testChangingProviderThroughEnablingDuringRelroCreation() {
        checkChangingProviderDuringRelroCreation(false);
    }

    private void checkChangingProviderDuringRelroCreation(boolean settingsChange) {
        String firstPackage = "first";
        String secondPackage = "second";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(firstPackage, "", true, false, null),
            new WebViewProviderInfo(secondPackage, "", true, false, null)};
        setupWithPackages(packages);
        // Have all packages be enabled, so that we can change provider however we want to
        setEnabledAndValidPackageInfos(packages);

        CountDownLatch countdown = new CountDownLatch(1);

        runWebViewBootPreparationOnMainSync();

        Mockito.verify(mTestSystemImpl).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(firstPackage)));

        assertEquals(firstPackage,
                mWebViewUpdateServiceImpl.getCurrentWebViewPackage().packageName);

        new Thread(new Runnable() {
            @Override
            public void run() {
                WebViewProviderResponse threadResponse =
                    mWebViewUpdateServiceImpl.waitForAndGetProvider();
                assertEquals(WebViewFactory.LIBLOAD_SUCCESS, threadResponse.status);
                assertEquals(secondPackage, threadResponse.packageInfo.packageName);
                // Verify that we killed the first package if we performed a settings change -
                // otherwise we had to disable the first package, in which case its dependents
                // should have been killed by the framework.
                if (settingsChange) {
                    Mockito.verify(mTestSystemImpl).killPackageDependents(Mockito.eq(firstPackage));
                }
                countdown.countDown();
            }
        }).start();
        try {
            Thread.sleep(500); // Let the new thread run / be blocked
        } catch (InterruptedException e) {
        }

        if (settingsChange) {
            mWebViewUpdateServiceImpl.changeProviderAndSetting(secondPackage);
        } else {
            // Enable the second provider
            mTestSystemImpl.setPackageInfo(createPackageInfo(secondPackage, true /* enabled */,
                        true /* valid */, true /* installed */));
            mWebViewUpdateServiceImpl.packageStateChanged(
                    secondPackage, WebViewUpdateService.PACKAGE_CHANGED, TestSystemImpl.PRIMARY_USER_ID);

            // Ensure we haven't changed package yet.
            assertEquals(firstPackage,
                    mWebViewUpdateServiceImpl.getCurrentWebViewPackage().packageName);

            // Switch provider by disabling the first one
            mTestSystemImpl.setPackageInfo(createPackageInfo(firstPackage, false /* enabled */,
                        true /* valid */, true /* installed */));
            mWebViewUpdateServiceImpl.packageStateChanged(
                    firstPackage, WebViewUpdateService.PACKAGE_CHANGED, TestSystemImpl.PRIMARY_USER_ID);
        }
        mWebViewUpdateServiceImpl.notifyRelroCreationCompleted();
        // first package done, should start on second

        Mockito.verify(mTestSystemImpl).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(secondPackage)));

        mWebViewUpdateServiceImpl.notifyRelroCreationCompleted();
        // second package done, the other thread should now be unblocked
        try {
            countdown.await();
        } catch (InterruptedException e) {
        }
    }

    @Test
    public void testRunFallbackLogicIfEnabled() {
        checkFallbackLogicBeingRun(true);
    }

    @Test
    public void testDontRunFallbackLogicIfDisabled() {
        checkFallbackLogicBeingRun(false);
    }

    private void checkFallbackLogicBeingRun(boolean fallbackLogicEnabled) {
        String primaryPackage = "primary";
        String fallbackPackage = "fallback";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(
                    primaryPackage, "", true /* default available */, false /* fallback */, null),
            new WebViewProviderInfo(
                    fallbackPackage, "", true /* default available */, true /* fallback */, null)};
        setupWithPackages(packages, fallbackLogicEnabled);
        setEnabledAndValidPackageInfos(packages);

        runWebViewBootPreparationOnMainSync();
        // Verify that we disable the fallback package if fallback logic enabled, and don't disable
        // the fallback package if that logic is disabled
        if (fallbackLogicEnabled) {
            Mockito.verify(mTestSystemImpl).uninstallAndDisablePackageForAllUsers(
                    Matchers.anyObject(), Mockito.eq(fallbackPackage));
        } else {
            Mockito.verify(mTestSystemImpl, Mockito.never()).uninstallAndDisablePackageForAllUsers(
                    Matchers.anyObject(), Matchers.anyObject());
        }
        Mockito.verify(mTestSystemImpl).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(primaryPackage)));

        // Enable fallback package
        mTestSystemImpl.setPackageInfo(createPackageInfo(fallbackPackage, true /* enabled */,
                        true /* valid */, true /* installed */));
        mWebViewUpdateServiceImpl.packageStateChanged(
                fallbackPackage, WebViewUpdateService.PACKAGE_CHANGED, TestSystemImpl.PRIMARY_USER_ID);

        if (fallbackLogicEnabled) {
            // Check that we have now disabled the fallback package twice
            Mockito.verify(mTestSystemImpl, Mockito.times(2)).uninstallAndDisablePackageForAllUsers(
                    Matchers.anyObject(), Mockito.eq(fallbackPackage));
        } else {
            // Check that we still haven't disabled any package
            Mockito.verify(mTestSystemImpl, Mockito.never()).uninstallAndDisablePackageForAllUsers(
                    Matchers.anyObject(), Matchers.anyObject());
        }
    }

    /**
     * Scenario for installing primary package when fallback enabled.
     * 1. Start with only fallback installed
     * 2. Install non-fallback
     * 3. Fallback should be disabled
     */
    @Test
    public void testInstallingNonFallbackPackage() {
        String primaryPackage = "primary";
        String fallbackPackage = "fallback";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(
                    primaryPackage, "", true /* default available */, false /* fallback */, null),
            new WebViewProviderInfo(
                    fallbackPackage, "", true /* default available */, true /* fallback */, null)};
        setupWithPackages(packages, true /* isFallbackLogicEnabled */);
        mTestSystemImpl.setPackageInfo(
                createPackageInfo(fallbackPackage, true /* enabled */ , true /* valid */,
                    true /* installed */));

        runWebViewBootPreparationOnMainSync();
        Mockito.verify(mTestSystemImpl, Mockito.never()).uninstallAndDisablePackageForAllUsers(
                Matchers.anyObject(), Matchers.anyObject());

        checkPreparationPhasesForPackage(fallbackPackage,
                1 /* first preparation for this package*/);

        // Install primary package
        mTestSystemImpl.setPackageInfo(
                createPackageInfo(primaryPackage, true /* enabled */ , true /* valid */,
                    true /* installed */));
        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED, TestSystemImpl.PRIMARY_USER_ID);

        // Verify fallback disabled, primary package used as provider, and fallback package killed
        Mockito.verify(mTestSystemImpl).uninstallAndDisablePackageForAllUsers(
                Matchers.anyObject(), Mockito.eq(fallbackPackage));
        checkPreparationPhasesForPackage(primaryPackage, 1 /* first preparation for this package*/);
        Mockito.verify(mTestSystemImpl).killPackageDependents(Mockito.eq(fallbackPackage));
    }

    @Test
    public void testFallbackChangesEnabledStateSingleUser() {
        for (PackageRemovalType removalType : REMOVAL_TYPES) {
            checkFallbackChangesEnabledState(false /* multiUser */, removalType);
        }
    }

    @Test
    public void testFallbackChangesEnabledStateMultiUser() {
        for (PackageRemovalType removalType : REMOVAL_TYPES) {
            checkFallbackChangesEnabledState(true /* multiUser */, removalType);
        }
    }

    /**
     * Represents how to remove a package during a tests (disabling it / uninstalling it / hiding
     * it).
     */
    private enum PackageRemovalType {
        UNINSTALL, DISABLE, HIDE
    }

    private PackageRemovalType[] REMOVAL_TYPES = PackageRemovalType.class.getEnumConstants();

    public void checkFallbackChangesEnabledState(boolean multiUser,
            PackageRemovalType removalType) {
        String primaryPackage = "primary";
        String fallbackPackage = "fallback";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(
                    primaryPackage, "", true /* default available */, false /* fallback */, null),
            new WebViewProviderInfo(
                    fallbackPackage, "", true /* default available */, true /* fallback */, null)};
        setupWithPackages(packages, true /* fallbackLogicEnabled */);
        int secondaryUserId = 10;
        int userIdToChangePackageFor = multiUser ? secondaryUserId : TestSystemImpl.PRIMARY_USER_ID;
        if (multiUser) {
            mTestSystemImpl.addUser(secondaryUserId);
            setEnabledAndValidPackageInfosForUser(secondaryUserId, packages);
        }
        setEnabledAndValidPackageInfosForUser(TestSystemImpl.PRIMARY_USER_ID, packages);

        runWebViewBootPreparationOnMainSync();

        // Verify fallback disabled at boot when primary package enabled
        checkEnablePackageForUserCalled(fallbackPackage, false, multiUser
                ? new int[] {TestSystemImpl.PRIMARY_USER_ID, secondaryUserId}
                : new int[] {TestSystemImpl.PRIMARY_USER_ID}, 1 /* numUsages */);

        checkPreparationPhasesForPackage(primaryPackage, 1);

        boolean enabled = !(removalType == PackageRemovalType.DISABLE);
        boolean installed = !(removalType == PackageRemovalType.UNINSTALL);
        boolean hidden = (removalType == PackageRemovalType.HIDE);
        // Disable primary package and ensure fallback becomes enabled and used
        mTestSystemImpl.setPackageInfoForUser(userIdToChangePackageFor,
                createPackageInfo(primaryPackage, enabled /* enabled */, true /* valid */,
                    installed /* installed */, null /* signature */, 0 /* updateTime */,
                    hidden /* hidden */));
        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                removalType == PackageRemovalType.DISABLE
                ? WebViewUpdateService.PACKAGE_CHANGED : WebViewUpdateService.PACKAGE_REMOVED,
                userIdToChangePackageFor); // USER ID

        checkEnablePackageForUserCalled(fallbackPackage, true, multiUser
                ? new int[] {TestSystemImpl.PRIMARY_USER_ID, secondaryUserId}
                : new int[] {TestSystemImpl.PRIMARY_USER_ID}, 1 /* numUsages */);

        checkPreparationPhasesForPackage(fallbackPackage, 1);


        // Again enable primary package and verify primary is used and fallback becomes disabled
        mTestSystemImpl.setPackageInfoForUser(userIdToChangePackageFor,
                createPackageInfo(primaryPackage, true /* enabled */, true /* valid */,
                    true /* installed */));
        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                removalType == PackageRemovalType.DISABLE
                ? WebViewUpdateService.PACKAGE_CHANGED : WebViewUpdateService.PACKAGE_ADDED,
                userIdToChangePackageFor);

        // Verify fallback is disabled a second time when primary package becomes enabled
        checkEnablePackageForUserCalled(fallbackPackage, false, multiUser
                ? new int[] {TestSystemImpl.PRIMARY_USER_ID, secondaryUserId}
                : new int[] {TestSystemImpl.PRIMARY_USER_ID}, 2 /* numUsages */);

        checkPreparationPhasesForPackage(primaryPackage, 2);
    }

    private void checkEnablePackageForUserCalled(String packageName, boolean expectEnabled,
            int[] userIds, int numUsages) {
        for (int userId : userIds) {
            Mockito.verify(mTestSystemImpl, Mockito.times(numUsages)).enablePackageForUser(
                    Mockito.eq(packageName), Mockito.eq(expectEnabled), Mockito.eq(userId));
        }
    }

    @Test
    public void testAddUserWhenFallbackLogicEnabled() {
        checkAddingNewUser(true);
    }

    @Test
    public void testAddUserWhenFallbackLogicDisabled() {
        checkAddingNewUser(false);
    }

    public void checkAddingNewUser(boolean fallbackLogicEnabled) {
        String primaryPackage = "primary";
        String fallbackPackage = "fallback";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(
                    primaryPackage, "", true /* default available */, false /* fallback */, null),
            new WebViewProviderInfo(
                    fallbackPackage, "", true /* default available */, true /* fallback */, null)};
        setupWithPackages(packages, fallbackLogicEnabled);
        setEnabledAndValidPackageInfosForUser(TestSystemImpl.PRIMARY_USER_ID, packages);
        int newUser = 100;
        mTestSystemImpl.addUser(newUser);
        setEnabledAndValidPackageInfosForUser(newUser, packages);
        mWebViewUpdateServiceImpl.handleNewUser(newUser);
        if (fallbackLogicEnabled) {
            // Verify fallback package becomes disabled for new user
            Mockito.verify(mTestSystemImpl).enablePackageForUser(
                    Mockito.eq(fallbackPackage), Mockito.eq(false) /* enable */,
                    Mockito.eq(newUser));
        } else {
            // Verify that we don't disable fallback for new user
            Mockito.verify(mTestSystemImpl, Mockito.never()).enablePackageForUser(
                    Mockito.anyObject(), Matchers.anyBoolean() /* enable */,
                    Matchers.anyInt() /* user */);
        }
    }

    /**
     * Ensures that adding a new user for which the current WebView package is uninstalled causes a
     * change of WebView provider.
     */
    @Test
    public void testAddingNewUserWithUninstalledPackage() {
        String primaryPackage = "primary";
        String fallbackPackage = "fallback";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(
                    primaryPackage, "", true /* default available */, false /* fallback */, null),
            new WebViewProviderInfo(
                    fallbackPackage, "", true /* default available */, true /* fallback */, null)};
        setupWithPackages(packages, true /* fallbackLogicEnabled */);
        setEnabledAndValidPackageInfosForUser(TestSystemImpl.PRIMARY_USER_ID, packages);
        int newUser = 100;
        mTestSystemImpl.addUser(newUser);
        // Let the primary package be uninstalled for the new user
        mTestSystemImpl.setPackageInfoForUser(newUser,
                createPackageInfo(primaryPackage, true /* enabled */, true /* valid */,
                        false /* installed */));
        mTestSystemImpl.setPackageInfoForUser(newUser,
                createPackageInfo(fallbackPackage, false /* enabled */, true /* valid */,
                        true /* installed */));
        mWebViewUpdateServiceImpl.handleNewUser(newUser);
        // Verify fallback package doesn't become disabled for the primary user.
        Mockito.verify(mTestSystemImpl, Mockito.never()).enablePackageForUser(
                Mockito.anyObject(), Mockito.eq(false) /* enable */,
                Mockito.eq(TestSystemImpl.PRIMARY_USER_ID) /* user */);
        // Verify that we enable the fallback package for the secondary user.
        Mockito.verify(mTestSystemImpl, Mockito.times(1)).enablePackageForUser(
                Mockito.eq(fallbackPackage), Mockito.eq(true) /* enable */,
                Mockito.eq(newUser) /* user */);
        checkPreparationPhasesForPackage(fallbackPackage, 1 /* numRelros */);
    }

    /**
     * Timing dependent test where we verify that the list of valid webview packages becoming empty
     * at a certain point doesn't crash us or break our state.
     */
    @Test
    public void testNotifyRelroDoesntCrashIfNoPackages() {
        String firstPackage = "first";
        String secondPackage = "second";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(firstPackage, "", true /* default available */,
                    false /* fallback */, null),
            new WebViewProviderInfo(secondPackage, "", true /* default available */,
                    false /* fallback */, null)};
        setupWithPackages(packages);
        // Add (enabled and valid) package infos for each provider
        setEnabledAndValidPackageInfos(packages);

        runWebViewBootPreparationOnMainSync();

        Mockito.verify(mTestSystemImpl).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(firstPackage)));

        // Change provider during relro creation to enter a state where we are
        // waiting for relro creation to complete just to re-run relro creation.
        // (so that in next notifyRelroCreationCompleted() call we have to list webview packages)
        mWebViewUpdateServiceImpl.changeProviderAndSetting(secondPackage);

        // Make packages invalid to cause exception to be thrown
        mTestSystemImpl.setPackageInfo(createPackageInfo(firstPackage, true /* enabled */,
                    false /* valid */, true /* installed */, null /* signatures */,
                    0 /* updateTime */));
        mTestSystemImpl.setPackageInfo(createPackageInfo(secondPackage, true /* enabled */,
                    false /* valid */, true /* installed */));

        // This shouldn't throw an exception!
        mWebViewUpdateServiceImpl.notifyRelroCreationCompleted();

        WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_FAILED_LISTING_WEBVIEW_PACKAGES, response.status);

        // Now make a package valid again and verify that we can switch back to that
        mTestSystemImpl.setPackageInfo(createPackageInfo(firstPackage, true /* enabled */,
                    true /* valid */, true /* installed */, null /* signatures */,
                    1 /* updateTime */ ));

        mWebViewUpdateServiceImpl.packageStateChanged(firstPackage,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED, TestSystemImpl.PRIMARY_USER_ID);

        // Ensure we use firstPackage
        checkPreparationPhasesForPackage(firstPackage, 2 /* second preparation for this package */);
    }

    /**
     * Verify that even if a user-chosen package is removed temporarily we start using it again when
     * it is added back.
     */
    @Test
    public void testTempRemovePackageDoesntSwitchProviderPermanently() {
        String firstPackage = "first";
        String secondPackage = "second";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(firstPackage, "", true /* default available */,
                    false /* fallback */, null),
            new WebViewProviderInfo(secondPackage, "", true /* default available */,
                    false /* fallback */, null)};
        checkCertainPackageUsedAfterWebViewBootPreparation(firstPackage, packages);

        // Explicitly use the second package
        mWebViewUpdateServiceImpl.changeProviderAndSetting(secondPackage);
        checkPreparationPhasesForPackage(secondPackage, 1 /* first time for this package */);

        // Remove second package (invalidate it) and verify that first package is used
        mTestSystemImpl.setPackageInfo(createPackageInfo(secondPackage, true /* enabled */,
                    false /* valid */, true /* installed */));
        mWebViewUpdateServiceImpl.packageStateChanged(secondPackage,
                WebViewUpdateService.PACKAGE_ADDED, TestSystemImpl.PRIMARY_USER_ID);
        checkPreparationPhasesForPackage(firstPackage, 2 /* second time for this package */);

        // Now make the second package valid again and verify that it is used again
        mTestSystemImpl.setPackageInfo(createPackageInfo(secondPackage, true /* enabled */,
                    true /* valid */, true /* installed */));
        mWebViewUpdateServiceImpl.packageStateChanged(secondPackage,
                WebViewUpdateService.PACKAGE_ADDED, TestSystemImpl.PRIMARY_USER_ID);
        checkPreparationPhasesForPackage(secondPackage, 2 /* second time for this package */);
    }

    /**
     * Ensure that we update the user-chosen setting across boots if the chosen package is no
     * longer installed and valid.
     */
    @Test
    public void testProviderSettingChangedDuringBootIfProviderNotAvailable() {
        String chosenPackage = "chosenPackage";
        String nonChosenPackage = "non-chosenPackage";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(chosenPackage, "", true /* default available */,
                    false /* fallback */, null),
            new WebViewProviderInfo(nonChosenPackage, "", true /* default available */,
                    false /* fallback */, null)};

        setupWithPackages(packages);
        // Only 'install' nonChosenPackage
        mTestSystemImpl.setPackageInfo(
                createPackageInfo(nonChosenPackage, true /* enabled */, true /* valid */, true /* installed */));

        // Set user-chosen package
        mTestSystemImpl.updateUserSetting(null, chosenPackage);

        runWebViewBootPreparationOnMainSync();

        // Verify that we switch the setting to point to the current package
        Mockito.verify(mTestSystemImpl).updateUserSetting(
                Mockito.anyObject(), Mockito.eq(nonChosenPackage));
        assertEquals(nonChosenPackage, mTestSystemImpl.getUserChosenWebViewProvider(null));

        checkPreparationPhasesForPackage(nonChosenPackage, 1);
    }

    @Test
    public void testRecoverFailedListingWebViewPackagesSettingsChange() {
        checkRecoverAfterFailListingWebviewPackages(true);
    }

    @Test
    public void testRecoverFailedListingWebViewPackagesAddedPackage() {
        checkRecoverAfterFailListingWebviewPackages(false);
    }

    /**
     * Test that we can recover correctly from failing to list WebView packages.
     * settingsChange: whether to fail during changeProviderAndSetting or packageStateChanged
     */
    public void checkRecoverAfterFailListingWebviewPackages(boolean settingsChange) {
        String firstPackage = "first";
        String secondPackage = "second";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(firstPackage, "", true /* default available */,
                    false /* fallback */, null),
            new WebViewProviderInfo(secondPackage, "", true /* default available */,
                    false /* fallback */, null)};
        checkCertainPackageUsedAfterWebViewBootPreparation(firstPackage, packages);

        // Make both packages invalid so that we fail listing WebView packages
        mTestSystemImpl.setPackageInfo(createPackageInfo(firstPackage, true /* enabled */,
                    false /* valid */, true /* installed */));
        mTestSystemImpl.setPackageInfo(createPackageInfo(secondPackage, true /* enabled */,
                    false /* valid */, true /* installed */));

        // Change package to hit the webview packages listing problem.
        if (settingsChange) {
            mWebViewUpdateServiceImpl.changeProviderAndSetting(secondPackage);
        } else {
            mWebViewUpdateServiceImpl.packageStateChanged(secondPackage,
                    WebViewUpdateService.PACKAGE_ADDED_REPLACED, TestSystemImpl.PRIMARY_USER_ID);
        }

        WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_FAILED_LISTING_WEBVIEW_PACKAGES, response.status);

        // Make second package valid and verify that we can load it again
        mTestSystemImpl.setPackageInfo(createPackageInfo(secondPackage, true /* enabled */,
                    true /* valid */, true /* installed */));

        mWebViewUpdateServiceImpl.packageStateChanged(secondPackage,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED, TestSystemImpl.PRIMARY_USER_ID);


        checkPreparationPhasesForPackage(secondPackage, 1);
    }

    @Test
    public void testDontKillIfPackageReplaced() {
        checkDontKillIfPackageRemoved(true);
    }

    @Test
    public void testDontKillIfPackageRemoved() {
        checkDontKillIfPackageRemoved(false);
    }

    public void checkDontKillIfPackageRemoved(boolean replaced) {
        String firstPackage = "first";
        String secondPackage = "second";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(firstPackage, "", true /* default available */,
                    false /* fallback */, null),
            new WebViewProviderInfo(secondPackage, "", true /* default available */,
                    false /* fallback */, null)};
        checkCertainPackageUsedAfterWebViewBootPreparation(firstPackage, packages);

        // Replace or remove the current webview package
        if (replaced) {
            mTestSystemImpl.setPackageInfo(
                    createPackageInfo(firstPackage, true /* enabled */, false /* valid */,
                        true /* installed */));
            mWebViewUpdateServiceImpl.packageStateChanged(firstPackage,
                    WebViewUpdateService.PACKAGE_ADDED_REPLACED, TestSystemImpl.PRIMARY_USER_ID);
        } else {
            mTestSystemImpl.removePackageInfo(firstPackage);
            mWebViewUpdateServiceImpl.packageStateChanged(firstPackage,
                    WebViewUpdateService.PACKAGE_REMOVED, TestSystemImpl.PRIMARY_USER_ID);
        }

        checkPreparationPhasesForPackage(secondPackage, 1);

        Mockito.verify(mTestSystemImpl, Mockito.never()).killPackageDependents(
                Mockito.anyObject());
    }

    @Test
    public void testKillIfSettingChanged() {
        String firstPackage = "first";
        String secondPackage = "second";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(firstPackage, "", true /* default available */,
                    false /* fallback */, null),
            new WebViewProviderInfo(secondPackage, "", true /* default available */,
                    false /* fallback */, null)};
        checkCertainPackageUsedAfterWebViewBootPreparation(firstPackage, packages);

        mWebViewUpdateServiceImpl.changeProviderAndSetting(secondPackage);

        checkPreparationPhasesForPackage(secondPackage, 1);

        Mockito.verify(mTestSystemImpl).killPackageDependents(Mockito.eq(firstPackage));
    }

    /**
     * Test that we kill apps using an old provider when we change the provider setting, even if the
     * new provider is not the one we intended to change to.
     */
    @Test
    public void testKillIfChangeProviderIncorrectly() {
        String firstPackage = "first";
        String secondPackage = "second";
        String thirdPackage = "third";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(firstPackage, "", true /* default available */,
                    false /* fallback */, null),
            new WebViewProviderInfo(secondPackage, "", true /* default available */,
                    false /* fallback */, null),
            new WebViewProviderInfo(thirdPackage, "", true /* default available */,
                    false /* fallback */, null)};
        setupWithPackages(packages);
        setEnabledAndValidPackageInfos(packages);

        // Start with the setting pointing to the third package
        mTestSystemImpl.updateUserSetting(null, thirdPackage);

        runWebViewBootPreparationOnMainSync();
        checkPreparationPhasesForPackage(thirdPackage, 1);

        mTestSystemImpl.setPackageInfo(
                createPackageInfo(secondPackage, true /* enabled */, false /* valid */, true /* installed */));

        // Try to switch to the invalid second package, this should result in switching to the first
        // package, since that is more preferred than the third one.
        assertEquals(firstPackage,
                mWebViewUpdateServiceImpl.changeProviderAndSetting(secondPackage));

        checkPreparationPhasesForPackage(firstPackage, 1);

        Mockito.verify(mTestSystemImpl).killPackageDependents(Mockito.eq(thirdPackage));
    }

    @Test
    public void testLowerPackageVersionNotValid() {
        checkPackageVersions(new int[]{200000} /* system version */, 100000/* candidate version */,
                false /* expected validity */);
    }

    @Test
    public void testEqualPackageVersionValid() {
        checkPackageVersions(new int[]{100000} /* system version */, 100000 /* candidate version */,
                true /* expected validity */);
    }

    @Test
    public void testGreaterPackageVersionValid() {
        checkPackageVersions(new int[]{100000} /* system versions */, 200000 /* candidate version */,
                true /* expected validity */);
    }

    @Test
    public void testLastFiveDigitsIgnored() {
        checkPackageVersions(new int[]{654321} /* system version */, 612345 /* candidate version */,
                true /* expected validity */);
    }

    @Test
    public void testMinimumSystemVersionUsedTwoDefaultsCandidateValid() {
        checkPackageVersions(new int[]{300000, 100000} /* system versions */,
                200000 /* candidate version */, true /* expected validity */);
    }

    @Test
    public void testMinimumSystemVersionUsedTwoDefaultsCandidateInvalid() {
        checkPackageVersions(new int[]{300000, 200000} /* system versions */,
                 100000 /* candidate version */, false /* expected validity */);
    }

    @Test
    public void testMinimumSystemVersionUsedSeveralDefaultsCandidateValid() {
        checkPackageVersions(new int[]{100000, 200000, 300000, 400000, 500000} /* system versions */,
                100000 /* candidate version */, true /* expected validity */);
    }

    @Test
    public void testMinimumSystemVersionUsedSeveralDefaultsCandidateInvalid() {
        checkPackageVersions(new int[]{200000, 300000, 400000, 500000, 600000} /* system versions */,
                100000 /* candidate version */, false /* expected validity */);
    }

    @Test
    public void testMinimumSystemVersionUsedFallbackIgnored() {
        checkPackageVersions(new int[]{300000, 400000, 500000, 600000, 700000} /* system versions */,
                200000 /* candidate version */, false /* expected validity */, true /* add fallback */,
                100000 /* fallback version */, false /* expected validity of fallback */);
    }

    @Test
    public void testFallbackValid() {
        checkPackageVersions(new int[]{300000, 400000, 500000, 600000, 700000} /* system versions */,
                200000/* candidate version */, false /* expected validity */, true /* add fallback */,
                300000 /* fallback version */, true /* expected validity of fallback */);
    }

    private void checkPackageVersions(int[] systemVersions, int candidateVersion,
            boolean candidateShouldBeValid) {
        checkPackageVersions(systemVersions, candidateVersion, candidateShouldBeValid,
                false, 0, false);
    }

    /**
     * Utility method for checking that package version restriction works as it should.
     * I.e. that a package with lower version than the system-default is not valid and that a
     * package with greater than or equal version code is considered valid.
     */
    private void checkPackageVersions(int[] systemVersions, int candidateVersion,
            boolean candidateShouldBeValid, boolean addFallback, int fallbackVersion,
            boolean fallbackShouldBeValid) {
        int numSystemPackages = systemVersions.length;
        int numFallbackPackages = (addFallback ? 1 : 0);
        int numPackages = systemVersions.length + 1 + numFallbackPackages;
        String candidatePackage = "candidatePackage";
        String systemPackage = "systemPackage";
        String fallbackPackage = "fallbackPackage";

        // Each package needs a valid signature since we set isDebuggable to false
        Signature signature = new Signature("11");
        String encodedSignatureString =
            Base64.encodeToString(signature.toByteArray(), Base64.DEFAULT);

        // Set up config
        // 1. candidatePackage
        // 2-N. default available non-fallback packages
        // N+1. default available fallback package
        WebViewProviderInfo[] packages = new WebViewProviderInfo[numPackages];
        packages[0] = new WebViewProviderInfo(candidatePackage, "",
                false /* available by default */, false /* fallback */,
                new String[]{encodedSignatureString});
        for(int n = 1; n < numSystemPackages + 1; n++) {
            packages[n] = new WebViewProviderInfo(systemPackage + n, "",
                    true /* available by default */, false /* fallback */,
                    new String[]{encodedSignatureString});
        }
        if (addFallback) {
            packages[packages.length-1] = new WebViewProviderInfo(fallbackPackage, "",
                    true /* available by default */, true /* fallback */,
                    new String[]{encodedSignatureString});
        }

        setupWithPackages(packages, true /* fallback logic enabled */, 1 /* numRelros */,
                false /* isDebuggable */);

        // Set package infos
        mTestSystemImpl.setPackageInfo(
                createPackageInfo(candidatePackage, true /* enabled */, true /* valid */,
                    true /* installed */, new Signature[]{signature}, 0 /* updateTime */,
                    false /* hidden */, candidateVersion, false /* isSystemApp */));
        for(int n = 1; n < numSystemPackages + 1; n++) {
            mTestSystemImpl.setPackageInfo(
                    createPackageInfo(systemPackage + n, true /* enabled */, true /* valid */,
                        true /* installed */, new Signature[]{signature}, 0 /* updateTime */,
                        false /* hidden */, systemVersions[n-1], true /* isSystemApp */));
        }
        if (addFallback) {
            mTestSystemImpl.setPackageInfo(
                    createPackageInfo(fallbackPackage, true /* enabled */, true /* valid */,
                        true /* installed */, new Signature[]{signature}, 0 /* updateTime */,
                        false /* hidden */, fallbackVersion, true /* isSystemApp */));
        }

        WebViewProviderInfo[] validPackages = mWebViewUpdateServiceImpl.getValidWebViewPackages();
        int expectedNumValidPackages = numSystemPackages;
        if (candidateShouldBeValid) {
            expectedNumValidPackages++;
        } else {
            // Ensure the candidate package is not one of the valid packages
            for(int n = 0; n < validPackages.length; n++) {
                assertFalse(candidatePackage.equals(validPackages[n].packageName));
            }
        }

        if (fallbackShouldBeValid) {
            expectedNumValidPackages += numFallbackPackages;
        } else {
            // Ensure the fallback package is not one of the valid packages
            for(int n = 0; n < validPackages.length; n++) {
                assertFalse(fallbackPackage.equals(validPackages[n].packageName));
            }
        }

        assertEquals(expectedNumValidPackages, validPackages.length);

        runWebViewBootPreparationOnMainSync();

        // The non-system package is not available by default so it shouldn't be used here
        checkPreparationPhasesForPackage(systemPackage + "1", 1);

        // Try explicitly switching to the candidate package
        String packageChange = mWebViewUpdateServiceImpl.changeProviderAndSetting(candidatePackage);
        if (candidateShouldBeValid) {
            assertEquals(candidatePackage, packageChange);
            checkPreparationPhasesForPackage(candidatePackage, 1);
        } else {
            assertEquals(systemPackage + "1", packageChange);
            // We didn't change package so the webview preparation won't run here
        }
    }

    /**
     * Ensure that the update service does use an uninstalled package when that is the only
     * package available.
     */
    @Test
    public void testWithSingleUninstalledPackage() {
        String testPackageName = "test.package.name";
        WebViewProviderInfo[] webviewPackages = new WebViewProviderInfo[] {
                new WebViewProviderInfo(testPackageName, "",
                        true /*default available*/, false /* fallback */, null)};
        setupWithPackages(webviewPackages, true /* fallback logic enabled */, 1 /* numRelros */);
        mTestSystemImpl.setPackageInfo(createPackageInfo(testPackageName, true /* enabled */,
                    true /* valid */, false /* installed */));

        runWebViewBootPreparationOnMainSync();

        checkPreparationPhasesForPackage(testPackageName, 1 /* first preparation phase */);
        // TODO(gsennton) change this logic to use the code below when we have created a functional
        // stub.
        //Mockito.verify(mTestSystemImpl, Mockito.never()).onWebViewProviderChanged(
        //        Matchers.anyObject());
        //WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        //assertEquals(WebViewFactory.LIBLOAD_FAILED_LISTING_WEBVIEW_PACKAGES, response.status);
        //assertEquals(null, mWebViewUpdateServiceImpl.getCurrentWebViewPackage());
    }

    @Test
    public void testNonhiddenPackageUserOverHidden() {
        checkVisiblePackageUserOverNonVisible(false /* multiUser*/, PackageRemovalType.HIDE);
        checkVisiblePackageUserOverNonVisible(true /* multiUser*/, PackageRemovalType.HIDE);
    }

    @Test
    public void testInstalledPackageUsedOverUninstalled() {
        checkVisiblePackageUserOverNonVisible(false /* multiUser*/, PackageRemovalType.UNINSTALL);
        checkVisiblePackageUserOverNonVisible(true /* multiUser*/, PackageRemovalType.UNINSTALL);
    }

    private void checkVisiblePackageUserOverNonVisible(boolean multiUser,
            PackageRemovalType removalType) {
        assert removalType != PackageRemovalType.DISABLE;
        boolean testUninstalled = removalType == PackageRemovalType.UNINSTALL;
        boolean testHidden = removalType == PackageRemovalType.HIDE;
        String installedPackage = "installedPackage";
        String uninstalledPackage = "uninstalledPackage";
        WebViewProviderInfo[] webviewPackages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(uninstalledPackage, "", true /* available by default */,
                    false /* fallback */, null),
            new WebViewProviderInfo(installedPackage, "", true /* available by default */,
                    false /* fallback */, null)};

        setupWithPackages(webviewPackages, true /* fallback logic enabled */, 1 /* numRelros */);
        int secondaryUserId = 5;
        if (multiUser) {
            mTestSystemImpl.addUser(secondaryUserId);
            // Install all packages for the primary user.
            setEnabledAndValidPackageInfosForUser(TestSystemImpl.PRIMARY_USER_ID, webviewPackages);
            mTestSystemImpl.setPackageInfoForUser(secondaryUserId, createPackageInfo(
                    installedPackage, true /* enabled */, true /* valid */, true /* installed */));
            // Hide or uninstall the primary package for the second user
            mTestSystemImpl.setPackageInfo(createPackageInfo(uninstalledPackage, true /* enabled */,
                    true /* valid */, (testUninstalled ? false : true) /* installed */,
                    null /* signatures */, 0 /* updateTime */, (testHidden ? true : false)));
        } else {
            mTestSystemImpl.setPackageInfo(createPackageInfo(installedPackage, true /* enabled */,
                    true /* valid */, true /* installed */));
            // Hide or uninstall the primary package
            mTestSystemImpl.setPackageInfo(createPackageInfo(uninstalledPackage, true /* enabled */,
                    true /* valid */, (testUninstalled ? false : true) /* installed */,
                    null /* signatures */, 0 /* updateTime */, (testHidden ? true : false)));
        }

        runWebViewBootPreparationOnMainSync();

        checkPreparationPhasesForPackage(installedPackage, 1 /* first preparation phase */);
    }

    @Test
    public void testCantSwitchToHiddenPackage () {
        checkCantSwitchToNonVisiblePackage(false /* true == uninstalled, false == hidden */);
    }


    @Test
    public void testCantSwitchToUninstalledPackage () {
        checkCantSwitchToNonVisiblePackage(true /* true == uninstalled, false == hidden */);
    }

    /**
     * Ensure that we won't prioritize an uninstalled (or hidden) package even if it is user-chosen.
     */
    private void checkCantSwitchToNonVisiblePackage(boolean uninstalledNotHidden) {
        boolean testUninstalled = uninstalledNotHidden;
        boolean testHidden = !uninstalledNotHidden;
        String installedPackage = "installedPackage";
        String uninstalledPackage = "uninstalledPackage";
        WebViewProviderInfo[] webviewPackages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(uninstalledPackage, "", true /* available by default */,
                    false /* fallback */, null),
            new WebViewProviderInfo(installedPackage, "", true /* available by default */,
                    false /* fallback */, null)};

        setupWithPackages(webviewPackages, true /* fallback logic enabled */, 1 /* numRelros */);
        int secondaryUserId = 412;
        mTestSystemImpl.addUser(secondaryUserId);

        // Let all packages be installed and enabled for the primary user.
        setEnabledAndValidPackageInfosForUser(TestSystemImpl.PRIMARY_USER_ID, webviewPackages);
        // Only uninstall the 'uninstalled package' for the secondary user.
        mTestSystemImpl.setPackageInfoForUser(secondaryUserId, createPackageInfo(installedPackage,
                true /* enabled */, true /* valid */, true /* installed */));
        mTestSystemImpl.setPackageInfoForUser(secondaryUserId, createPackageInfo(uninstalledPackage,
                true /* enabled */, true /* valid */, !testUninstalled /* installed */,
                null /* signatures */, 0 /* updateTime */, testHidden /* hidden */));

        runWebViewBootPreparationOnMainSync();

        checkPreparationPhasesForPackage(installedPackage, 1 /* first preparation phase */);

        // ensure that we don't switch to the uninstalled package (it will be used if it becomes
        // installed later)
        assertEquals(installedPackage,
                mWebViewUpdateServiceImpl.changeProviderAndSetting(uninstalledPackage));

        // Ensure both packages are considered valid.
        assertEquals(2, mWebViewUpdateServiceImpl.getValidWebViewPackages().length);


        // We should only have called onWebViewProviderChanged once (before calling
        // changeProviderAndSetting
        Mockito.verify(mTestSystemImpl, Mockito.times(1)).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(installedPackage)));
    }

    @Test
    public void testHiddenPackageNotPrioritizedEvenIfChosen() {
        checkNonvisiblePackageNotPrioritizedEvenIfChosen(
                false /* true == uninstalled, false == hidden */);
    }

    @Test
    public void testUninstalledPackageNotPrioritizedEvenIfChosen() {
        checkNonvisiblePackageNotPrioritizedEvenIfChosen(
                true /* true == uninstalled, false == hidden */);
    }

    public void checkNonvisiblePackageNotPrioritizedEvenIfChosen(boolean uninstalledNotHidden) {
        boolean testUninstalled = uninstalledNotHidden;
        boolean testHidden = !uninstalledNotHidden;
        String installedPackage = "installedPackage";
        String uninstalledPackage = "uninstalledPackage";
        WebViewProviderInfo[] webviewPackages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(uninstalledPackage, "", true /* available by default */,
                    false /* fallback */, null),
            new WebViewProviderInfo(installedPackage, "", true /* available by default */,
                    false /* fallback */, null)};

        setupWithPackages(webviewPackages, true /* fallback logic enabled */, 1 /* numRelros */);
        int secondaryUserId = 4;
        mTestSystemImpl.addUser(secondaryUserId);

        setEnabledAndValidPackageInfosForUser(TestSystemImpl.PRIMARY_USER_ID, webviewPackages);
        mTestSystemImpl.setPackageInfoForUser(secondaryUserId, createPackageInfo(installedPackage,
                true /* enabled */, true /* valid */, true /* installed */));
        mTestSystemImpl.setPackageInfoForUser(secondaryUserId, createPackageInfo(uninstalledPackage,
                true /* enabled */, true /* valid */,
                (testUninstalled ? false : true) /* installed */, null /* signatures */,
                0 /* updateTime */, (testHidden ? true : false) /* hidden */));

        // Start with the setting pointing to the uninstalled package
        mTestSystemImpl.updateUserSetting(null, uninstalledPackage);

        runWebViewBootPreparationOnMainSync();

        checkPreparationPhasesForPackage(installedPackage, 1 /* first preparation phase */);
    }

    @Test
    public void testFallbackEnabledIfPrimaryUninstalledSingleUser() {
        checkFallbackEnabledIfPrimaryUninstalled(false /* multiUser */);
    }

    @Test
    public void testFallbackEnabledIfPrimaryUninstalledMultiUser() {
        checkFallbackEnabledIfPrimaryUninstalled(true /* multiUser */);
    }

    /**
     * Ensures that fallback becomes enabled at boot if the primary package is uninstalled for some
     * user.
     */
    private void checkFallbackEnabledIfPrimaryUninstalled(boolean multiUser) {
        String primaryPackage = "primary";
        String fallbackPackage = "fallback";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(
                    primaryPackage, "", true /* default available */, false /* fallback */, null),
            new WebViewProviderInfo(
                    fallbackPackage, "", true /* default available */, true /* fallback */, null)};
        setupWithPackages(packages, true /* fallback logic enabled */);
        int secondaryUserId = 5;
        if (multiUser) {
            mTestSystemImpl.addUser(secondaryUserId);
            // Install all packages for the primary user.
            setEnabledAndValidPackageInfosForUser(TestSystemImpl.PRIMARY_USER_ID, packages);
            // Only install fallback package for secondary user.
            mTestSystemImpl.setPackageInfoForUser(secondaryUserId,
                    createPackageInfo(primaryPackage, true /* enabled */,
                            true /* valid */, false /* installed */));
            mTestSystemImpl.setPackageInfoForUser(secondaryUserId,
                    createPackageInfo(fallbackPackage, false /* enabled */,
                            true /* valid */, true /* installed */));
        } else {
            mTestSystemImpl.setPackageInfo(createPackageInfo(primaryPackage, true /* enabled */,
                    true /* valid */, false /* installed */));
            mTestSystemImpl.setPackageInfo(createPackageInfo(fallbackPackage, false /* enabled */,
                    true /* valid */, true /* installed */));
        }

        runWebViewBootPreparationOnMainSync();
        // Verify that we enable the fallback package
        Mockito.verify(mTestSystemImpl).enablePackageForAllUsers(
                Mockito.anyObject(), Mockito.eq(fallbackPackage), Mockito.eq(true) /* enable */);

        checkPreparationPhasesForPackage(fallbackPackage, 1 /* first preparation phase */);
    }

    @Test
    public void testPreparationRunsIffNewPackage() {
        String primaryPackage = "primary";
        String fallbackPackage = "fallback";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(
                    primaryPackage, "", true /* default available */, false /* fallback */, null),
            new WebViewProviderInfo(
                    fallbackPackage, "", true /* default available */, true /* fallback */, null)};
        setupWithPackages(packages, true /* fallback logic enabled */);
        mTestSystemImpl.setPackageInfo(createPackageInfo(primaryPackage, true /* enabled */,
                    true /* valid */, true /* installed */, null /* signatures */,
                    10 /* lastUpdateTime*/ ));
        mTestSystemImpl.setPackageInfo(createPackageInfo(fallbackPackage, true /* enabled */,
                    true /* valid */, true /* installed */));

        runWebViewBootPreparationOnMainSync();

        checkPreparationPhasesForPackage(primaryPackage, 1 /* first preparation phase */);
        Mockito.verify(mTestSystemImpl, Mockito.times(1)).enablePackageForUser(
                Mockito.eq(fallbackPackage), Mockito.eq(false) /* enable */,
                Matchers.anyInt() /* user */);


        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED, 0 /* userId */);
        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED, 1 /* userId */);
        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED, 2 /* userId */);
        // package still has the same update-time so we shouldn't run preparation here
        Mockito.verify(mTestSystemImpl, Mockito.times(1)).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(primaryPackage)));
        Mockito.verify(mTestSystemImpl, Mockito.times(1)).enablePackageForUser(
                Mockito.eq(fallbackPackage), Mockito.eq(false) /* enable */,
                Matchers.anyInt() /* user */);

        // Ensure we can still load the package
        WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_SUCCESS, response.status);
        assertEquals(primaryPackage, response.packageInfo.packageName);


        mTestSystemImpl.setPackageInfo(createPackageInfo(primaryPackage, true /* enabled */,
                    true /* valid */, true /* installed */, null /* signatures */,
                    20 /* lastUpdateTime*/ ));
        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED, 0);
        // The package has now changed - ensure that we have run the preparation phase a second time
        checkPreparationPhasesForPackage(primaryPackage, 2 /* second preparation phase */);


        mTestSystemImpl.setPackageInfo(createPackageInfo(primaryPackage, true /* enabled */,
                    true /* valid */, true /* installed */, null /* signatures */,
                    50 /* lastUpdateTime*/ ));
        // Receive intent for different user
        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED, 2);

        checkPreparationPhasesForPackage(primaryPackage, 3 /* third preparation phase */);
    }

    @Test
    public void testGetCurrentWebViewPackage() {
        PackageInfo firstPackage = createPackageInfo("first", true /* enabled */,
                        true /* valid */, true /* installed */);
        firstPackage.versionCode = 100;
        firstPackage.versionName = "first package version";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(firstPackage.packageName, "", true, false, null)};
        setupWithPackages(packages, true);
        mTestSystemImpl.setPackageInfo(firstPackage);

        runWebViewBootPreparationOnMainSync();

        Mockito.verify(mTestSystemImpl).onWebViewProviderChanged(
                Mockito.argThat(new IsPackageInfoWithName(firstPackage.packageName)));

        mWebViewUpdateServiceImpl.notifyRelroCreationCompleted();

        // Ensure the API is correct before running waitForAndGetProvider
        assertEquals(firstPackage.packageName,
                mWebViewUpdateServiceImpl.getCurrentWebViewPackage().packageName);
        assertEquals(firstPackage.versionCode,
                mWebViewUpdateServiceImpl.getCurrentWebViewPackage().versionCode);
        assertEquals(firstPackage.versionName,
                mWebViewUpdateServiceImpl.getCurrentWebViewPackage().versionName);

        WebViewProviderResponse response = mWebViewUpdateServiceImpl.waitForAndGetProvider();
        assertEquals(WebViewFactory.LIBLOAD_SUCCESS, response.status);
        assertEquals(firstPackage.packageName, response.packageInfo.packageName);

        // Ensure the API is still correct after running waitForAndGetProvider
        assertEquals(firstPackage.packageName,
                mWebViewUpdateServiceImpl.getCurrentWebViewPackage().packageName);
        assertEquals(firstPackage.versionCode,
                mWebViewUpdateServiceImpl.getCurrentWebViewPackage().versionCode);
        assertEquals(firstPackage.versionName,
                mWebViewUpdateServiceImpl.getCurrentWebViewPackage().versionName);
    }

    @Test
    public void testMultiProcessEnabledByDefault() {
        String primaryPackage = "primary";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(
                    primaryPackage, "", true /* default available */, false /* fallback */, null)};
        setupWithPackages(packages, true /* fallback logic enabled */, 1 /* numRelros */,
                          true /* debuggable */, true /* multiprocess by default */);
        mTestSystemImpl.setPackageInfo(createPackageInfo(primaryPackage, true /* enabled */,
                    true /* valid */, true /* installed */, null /* signatures */,
                    10 /* lastUpdateTime*/, false /* not hidden */, 1000 /* versionCode */,
                    false /* isSystemApp */));

        runWebViewBootPreparationOnMainSync();
        checkPreparationPhasesForPackage(primaryPackage, 1 /* first preparation phase */);

        // Check it's on by default
        assertTrue(mWebViewUpdateServiceImpl.isMultiProcessEnabled());

        // Test toggling it
        mWebViewUpdateServiceImpl.enableMultiProcess(false);
        assertFalse(mWebViewUpdateServiceImpl.isMultiProcessEnabled());
        mWebViewUpdateServiceImpl.enableMultiProcess(true);
        assertTrue(mWebViewUpdateServiceImpl.isMultiProcessEnabled());

        // Disable, then upgrade provider, which should re-enable it
        mWebViewUpdateServiceImpl.enableMultiProcess(false);
        mTestSystemImpl.setPackageInfo(createPackageInfo(primaryPackage, true /* enabled */,
                    true /* valid */, true /* installed */, null /* signatures */,
                    20 /* lastUpdateTime*/, false /* not hidden */, 2000 /* versionCode */,
                    false /* isSystemApp */));
        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED, 0);
        checkPreparationPhasesForPackage(primaryPackage, 2);
        assertTrue(mWebViewUpdateServiceImpl.isMultiProcessEnabled());
    }

    @Test
    public void testMultiProcessDisabledByDefault() {
        String primaryPackage = "primary";
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(
                    primaryPackage, "", true /* default available */, false /* fallback */, null)};
        setupWithPackages(packages, true /* fallback logic enabled */, 1 /* numRelros */,
                          true /* debuggable */, false /* not multiprocess by default */);
        mTestSystemImpl.setPackageInfo(createPackageInfo(primaryPackage, true /* enabled */,
                    true /* valid */, true /* installed */, null /* signatures */,
                    10 /* lastUpdateTime*/, false /* not hidden */, 1000 /* versionCode */,
                    false /* isSystemApp */));

        runWebViewBootPreparationOnMainSync();
        checkPreparationPhasesForPackage(primaryPackage, 1 /* first preparation phase */);

        // Check it's off by default
        assertFalse(mWebViewUpdateServiceImpl.isMultiProcessEnabled());

        // Test toggling it
        mWebViewUpdateServiceImpl.enableMultiProcess(true);
        assertTrue(mWebViewUpdateServiceImpl.isMultiProcessEnabled());
        mWebViewUpdateServiceImpl.enableMultiProcess(false);
        assertFalse(mWebViewUpdateServiceImpl.isMultiProcessEnabled());

        // Disable, then upgrade provider, which should not re-enable it
        mWebViewUpdateServiceImpl.enableMultiProcess(false);
        mTestSystemImpl.setPackageInfo(createPackageInfo(primaryPackage, true /* enabled */,
                    true /* valid */, true /* installed */, null /* signatures */,
                    20 /* lastUpdateTime*/, false /* not hidden */, 2000 /* versionCode */,
                    false /* isSystemApp */));
        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED, 0);
        checkPreparationPhasesForPackage(primaryPackage, 2);
        assertFalse(mWebViewUpdateServiceImpl.isMultiProcessEnabled());

        // Enable, then upgrade provider, which should leave it on
        mWebViewUpdateServiceImpl.enableMultiProcess(true);
        mTestSystemImpl.setPackageInfo(createPackageInfo(primaryPackage, true /* enabled */,
                    true /* valid */, true /* installed */, null /* signatures */,
                    30 /* lastUpdateTime*/, false /* not hidden */, 3000 /* versionCode */,
                    false /* isSystemApp */));
        mWebViewUpdateServiceImpl.packageStateChanged(primaryPackage,
                WebViewUpdateService.PACKAGE_ADDED_REPLACED, 0);
        checkPreparationPhasesForPackage(primaryPackage, 3);
        assertTrue(mWebViewUpdateServiceImpl.isMultiProcessEnabled());
    }

    /**
     * Ensure that packages with a targetSdkVersion targeting the current platform are valid, and
     * that packages targeting an older version are not valid.
     */
    @Test
    public void testTargetSdkVersionValidity() {
        PackageInfo newSdkPackage = createPackageInfo("newTargetSdkPackage",
            true /* enabled */, true /* valid */, true /* installed */);
        newSdkPackage.applicationInfo.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;
        PackageInfo currentSdkPackage = createPackageInfo("currentTargetSdkPackage",
            true /* enabled */, true /* valid */, true /* installed */);
        currentSdkPackage.applicationInfo.targetSdkVersion = Build.VERSION_CODES.N_MR1+1;
        PackageInfo oldSdkPackage = createPackageInfo("oldTargetSdkPackage",
            true /* enabled */, true /* valid */, true /* installed */);
        oldSdkPackage.applicationInfo.targetSdkVersion = Build.VERSION_CODES.N_MR1;

        WebViewProviderInfo newSdkProviderInfo =
                new WebViewProviderInfo(newSdkPackage.packageName, "", true, false, null);
        WebViewProviderInfo currentSdkProviderInfo =
                new WebViewProviderInfo(currentSdkPackage.packageName, "", true, false, null);
        WebViewProviderInfo[] packages = new WebViewProviderInfo[] {
            new WebViewProviderInfo(oldSdkPackage.packageName, "", true, false, null),
            currentSdkProviderInfo, newSdkProviderInfo};
        setupWithPackages(packages, true);
;
        mTestSystemImpl.setPackageInfo(newSdkPackage);
        mTestSystemImpl.setPackageInfo(currentSdkPackage);
        mTestSystemImpl.setPackageInfo(oldSdkPackage);

        assertArrayEquals(new WebViewProviderInfo[]{currentSdkProviderInfo, newSdkProviderInfo},
                mWebViewUpdateServiceImpl.getValidWebViewPackages());

        runWebViewBootPreparationOnMainSync();

        checkPreparationPhasesForPackage(currentSdkPackage.packageName,
                1 /* first preparation phase */);
    }
}
