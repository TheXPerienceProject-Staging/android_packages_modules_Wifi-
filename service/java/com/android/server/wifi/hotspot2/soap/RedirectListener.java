/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wifi.hotspot2.soap;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.util.Random;

import fi.iki.elonen.NanoHTTPD;

/**
 * Server for listening for redirect request from the OSU server to indicate the completion
 * of user input.
 *
 * A HTTP server will be started in the {@link RedirectListener#startServer} of {@link
 * RedirectListener}, so the caller will need to invoke {@link RedirectListener#stop} once the
 * redirect server no longer needed.
 */
public class RedirectListener extends NanoHTTPD {
    // 4 minutes for the maximum wait time.
    @VisibleForTesting
    static final int USER_TIMEOUT_MILLIS = 4 * 60 * 1000;

    private static final String TAG = "PasspointRedirectListener";

    private final String mPath;
    private final URL mServerUrl;
    private final Handler mStartStopHandler;
    private final Handler mHandler;
    private Runnable mTimeOutTask;
    private RedirectCallback mRedirectCallback;

    /**
     * Listener interface for handling redirect events.
     */
    public interface RedirectCallback {

        /**
         * Invoked when HTTP redirect response is received.
         */
        void onRedirectReceived();

        /**
         * Invoked when timeout occurs on receiving HTTP redirect response.
         */
        void onRedirectTimedOut();
    }

    @VisibleForTesting
    /* package */ RedirectListener(Looper looper, @Nullable Looper startStopLooper, int port)
            throws IOException {
        super(InetAddress.getLocalHost().getHostAddress(), port);

        Random rnd = new Random(System.currentTimeMillis());

        mPath = "rnd" + Integer.toString(Math.abs(rnd.nextInt()), Character.MAX_RADIX);
        mServerUrl = new URL("http", getHostname(), port, mPath);
        mHandler = new Handler(looper);
        mTimeOutTask = () -> mRedirectCallback.onRedirectTimedOut();
        if (startStopLooper == null) {
            HandlerThread redirectHandlerThread = new HandlerThread("RedirectListenerHandler");
            redirectHandlerThread.start();
            startStopLooper = redirectHandlerThread.getLooper();
        }
        mStartStopHandler = new Handler(startStopLooper);
    }

    /**
     * Create an instance of {@link RedirectListener}
     *
     * @param looper Looper on which the {@link RedirectCallback} will be called.
     * @return Instance of {@link RedirectListener}, {@code null} in any failure.
     */
    public static RedirectListener createInstance(@NonNull Looper looper) {
        RedirectListener redirectListener;
        try {
            redirectListener = new RedirectListener(looper, null,
                    new ServerSocket(0, 1, InetAddress.getLocalHost()).getLocalPort());
        } catch (IOException e) {
            Log.e(TAG, "fails to create an instance: " + e);
            return null;
        }
        return redirectListener;
    }

    /**
     * Start redirect listener
     *
     * @param callback to be notified when the redirect request is received or timed out.
     * @return {@code true} in success, {@code false} if the {@code callback} is {@code null} or the
     * server is already running.
     */
    public boolean startServer(@NonNull RedirectCallback callback) {
        if (callback == null) {
            return false;
        }

        if (isAlive()) {
            Log.e(TAG, "redirect listener is already running");
            return false;
        }
        mRedirectCallback = callback;

        mStartStopHandler.post(() -> {
            try {
                start();
            } catch (IOException e) {
                Log.e(TAG, "unable to start redirect listener: " + e);
            }
        });
        mHandler.postDelayed(mTimeOutTask, USER_TIMEOUT_MILLIS);
        return true;
    }

    /**
     * Stop redirect listener
     */
    public void stopServer() {
        if (mHandler.hasCallbacks(mTimeOutTask)) {
            mHandler.removeCallbacks(mTimeOutTask);
        }
        if (isServerAlive()) {
            mStartStopHandler.post(() -> stop());
        }
    }

    /**
     * Check if the server is alive or not.
     *
     * @return {@code true} if the server is alive.
     */
    public boolean isServerAlive() {
        return isAlive();
    }

    /**
     * Get URL to which the local redirect server listens
     *
     * @return The URL for the local redirect server.
     */
    public URL getServerUrl() {
        return mServerUrl;
    }

    @Override
    public Response serve(IHTTPSession session) {

        // Ignore all other requests except for a HTTP request that has the server url path with
        // GET method.
        if (session.getMethod() != Method.GET || !mServerUrl.getPath().equals(session.getUri())) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_HTML, "");
        }

        mHandler.removeCallbacks(mTimeOutTask);
        mRedirectCallback.onRedirectReceived();
        return newFixedLengthResponse("");
    }
}
