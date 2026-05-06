//
// Copyright (C) 2026 The 2by2 Project
// SPDX-License-Identifier: Apache-2.0
//

package com.android.nfc;

import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import com.android.nfc.dhimpl.felica.NativeFelicaSe;

import jp.project2by2.nfc.INfcFelicaBroker;

final class FelicaBrokerService extends INfcFelicaBroker.Stub {
    private static final String TAG = "FelicaBrokerService";
    private static final String SERVICE_NAME = "nfc_felica_broker";

    private static final int ERROR_NONE = 0;
    private static final int ERROR_FAILED = -99;
    private static final int ERROR_INVALID_PARAM = -10;
    private static final int ERROR_BUSY = -11;
    private static final int ERROR_NOT_AVAILABLE = -18;

    private final FelicaBridge mBridge;
    private final NativeFelicaSe mNativeFelicaSe = new NativeFelicaSe();
    private final Random mRandom = new Random();
    private final Map<Integer, Session> mSeSessions = new HashMap<>();
    private final Map<Integer, Session> mRfSessions = new HashMap<>();

    FelicaBrokerService(FelicaBridge bridge) {
        mBridge = bridge;
    }

    static void register(FelicaBrokerService service) {
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            serviceManager.getMethod("addService", String.class, IBinder.class)
                    .invoke(null, SERVICE_NAME, service);
            Log.i(TAG, "registered " + SERVICE_NAME);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.e(TAG, "failed to register " + SERVICE_NAME, e);
        }
    }

    @Override
    public Bundle openSe(String packageName, IBinder token) {
        Log.i(TAG, "openSe package=" + packageName);
        return openSeNative(token);
    }

    @Override
    public int closeSe(String packageName, int handle, IBinder token) {
        Log.i(TAG, "closeSe package=" + packageName + " handle=" + handle);
        return closeSeNative(handle, token);
    }

    @Override
    public Bundle transceiveSe(String packageName, int handle, byte[] data, int timeoutMs) {
        Log.i(TAG, "transceiveSe package=" + packageName
                + " handle=" + handle + " len=" + (data != null ? data.length : -1)
                + " timeoutMs=" + timeoutMs);
        if (data == null || data.length == 0 || !contains(mSeSessions, handle)) {
            return transceiveErrorBundle(ERROR_INVALID_PARAM);
        }
        int[] error = new int[] {ERROR_FAILED};
        byte[] response = mNativeFelicaSe.transceive(handle, data, timeoutMs, error);
        Bundle bundle = new Bundle();
        bundle.putByteArray("out", response);
        bundle.putInt("e", error[0]);
        Log.i(TAG, "transceiveSe completed handle=" + handle
                + " responseLen=" + (response != null ? response.length : -1)
                + " error=" + error[0]);
        return bundle;
    }

    @Override
    public int cancelSe(String packageName, int handle) {
        Log.i(TAG, "cancelSe package=" + packageName + " handle=" + handle);
        mNativeFelicaSe.cancel(handle);
        return ERROR_NONE;
    }

    @Override
    public int connectSe(String packageName, int handle) {
        Log.i(TAG, "connectSe package=" + packageName + " handle=" + handle);
        return contains(mSeSessions, handle) ? ERROR_NONE : ERROR_INVALID_PARAM;
    }

    @Override
    public int disconnectSe(String packageName, int handle) {
        Log.i(TAG, "disconnectSe package=" + packageName + " handle=" + handle);
        return contains(mSeSessions, handle) ? ERROR_NONE : ERROR_INVALID_PARAM;
    }

    @Override
    public Bundle openRf(String packageName, IBinder token) {
        Log.i(TAG, "openRf package=" + packageName);
        return open("RF", mRfSessions, token, true);
    }

    @Override
    public int closeRf(String packageName, int handle, IBinder token) {
        Log.i(TAG, "closeRf package=" + packageName + " handle=" + handle);
        return close("RF", mRfSessions, handle, token);
    }

    @Override
    public Bundle transceiveRf(String packageName, int handle, byte[] data, int timeoutMs) {
        Log.w(TAG, "transceiveRf is not implemented package=" + packageName
                + " handle=" + handle + " len=" + (data != null ? data.length : -1)
                + " timeoutMs=" + timeoutMs);
        return errorBundle(ERROR_FAILED);
    }

    @Override
    public int cancelRf(String packageName, int handle) {
        Log.i(TAG, "cancelRf package=" + packageName + " handle=" + handle);
        return ERROR_NONE;
    }

    @Override
    public int connectRf(String packageName, int handle, int timeoutMs) {
        Log.i(TAG, "connectRf package=" + packageName + " handle=" + handle
                + " timeoutMs=" + timeoutMs);
        return contains(mRfSessions, handle) ? ERROR_NONE : ERROR_INVALID_PARAM;
    }

    @Override
    public int disconnectRf(String packageName, int handle) {
        Log.i(TAG, "disconnectRf package=" + packageName + " handle=" + handle);
        return contains(mRfSessions, handle) ? ERROR_NONE : ERROR_INVALID_PARAM;
    }

    @Override
    public boolean enable() {
        Log.i(TAG, "enable");
        mBridge.requestRouting();
        return true;
    }

    @Override
    public boolean disable(boolean persist) {
        Log.i(TAG, "disable persist=" + persist);
        mBridge.requestRouting();
        return true;
    }

    @Override
    public int getState() {
        return mBridge.isNfcEnabled() ? NfcAdapter.STATE_ON : NfcAdapter.STATE_OFF;
    }

    @Override
    public int getRwP2pState() {
        return 0;
    }

    @Override
    public boolean setRwP2pMode(boolean enabled) {
        Log.i(TAG, "setRwP2pMode enabled=" + enabled);
        mBridge.requestRouting();
        return true;
    }

    @Override
    public void prepareSwitchedOffState() {
        Log.i(TAG, "prepareSwitchedOffState");
        mBridge.requestRouting();
    }

    private Bundle open(String kind, Map<Integer, Session> sessions, IBinder token, boolean route) {
        if (token == null) {
            Log.w(TAG, "open" + kind + " failed: token is null");
            return errorBundle(ERROR_INVALID_PARAM);
        }
        int handle;
        boolean shouldRoute = false;
        synchronized (mBridge) {
            if (!mBridge.isNfcEnabled()) {
                Log.w(TAG, "open" + kind + " failed: NFC is not enabled");
                return errorBundle(ERROR_NOT_AVAILABLE);
            }
            if (!sessions.isEmpty()) {
                for (Session session : sessions.values()) {
                    if (session.token == token) {
                        Log.i(TAG, "open" + kind + " reusing handle=" + session.handle);
                        return successBundle(session.handle);
                    }
                }
                Log.w(TAG, "open" + kind + " failed: busy");
                return errorBundle(ERROR_BUSY);
            }
            handle = nextHandle(sessions);
            Session session = new Session(handle, token, sessions);
            try {
                token.linkToDeath(session, 0);
            } catch (RemoteException e) {
                Log.e(TAG, "open" + kind + " failed: linkToDeath failed", e);
                return errorBundle(ERROR_FAILED);
            }
            sessions.put(handle, session);
            shouldRoute = route;
        }
        if (shouldRoute) {
            mBridge.setActive(true);
        }
        Log.i(TAG, "open" + kind + " succeeded handle=" + handle);
        return successBundle(handle);
    }

    private Bundle openSeNative(IBinder token) {
        if (token == null) {
            Log.w(TAG, "openSE failed: token is null");
            return errorBundle(ERROR_INVALID_PARAM);
        }

        synchronized (mBridge) {
            if (!mBridge.isNfcEnabled()) {
                Log.w(TAG, "openSE failed: NFC is not enabled");
                return errorBundle(ERROR_NOT_AVAILABLE);
            }
            if (!mSeSessions.isEmpty()) {
                for (Session session : mSeSessions.values()) {
                    if (session.token == token) {
                        Log.i(TAG, "openSE reusing handle=" + session.handle);
                        return successBundle(session.handle);
                    }
                }
                Log.w(TAG, "openSE failed: busy");
                return errorBundle(ERROR_BUSY);
            }
        }

        if (!mBridge.setActiveAndWait(true)) {
            mBridge.setActive(false);
            Log.w(TAG, "openSE failed: bridge routing failed");
            return errorBundle(ERROR_FAILED);
        }

        int handle = mNativeFelicaSe.open();
        if (handle < 0) {
            mBridge.setActive(false);
            Log.w(TAG, "openSE native failed error=" + handle);
            return errorBundle(handle);
        }

        Session session = new Session(handle, token, mSeSessions);
        try {
            token.linkToDeath(session, 0);
        } catch (RemoteException e) {
            mNativeFelicaSe.close(handle);
            mBridge.setActive(false);
            Log.e(TAG, "openSE failed: linkToDeath failed", e);
            return errorBundle(ERROR_FAILED);
        }

        synchronized (mBridge) {
            if (!mSeSessions.isEmpty()) {
                mNativeFelicaSe.close(handle);
                mBridge.setActive(false);
                return errorBundle(ERROR_BUSY);
            }
            mSeSessions.put(handle, session);
        }
        Log.i(TAG, "openSE succeeded handle=" + handle);
        return successBundle(handle);
    }

    private int closeSeNative(int handle, IBinder token) {
        synchronized (mBridge) {
            Session session = mSeSessions.get(handle);
            if (session == null || (token != null && session.token != token)) {
                Log.w(TAG, "closeSE failed: invalid handle=" + handle);
                return ERROR_INVALID_PARAM;
            }
            removeSession(mSeSessions, handle, true);
        }
        int result = mNativeFelicaSe.close(handle);
        mBridge.setActive(false);
        Log.i(TAG, "closeSE native result=" + result + " handle=" + handle);
        return result;
    }

    private int close(String kind, Map<Integer, Session> sessions, int handle, IBinder token) {
        boolean closed;
        synchronized (mBridge) {
            Session session = sessions.get(handle);
            if (session == null || (token != null && session.token != token)) {
                Log.w(TAG, "close" + kind + " failed: invalid handle=" + handle);
                return ERROR_INVALID_PARAM;
            }
            removeSession(sessions, handle, true);
            closed = true;
        }
        if (closed) {
            mBridge.setActive(false);
        }
        Log.i(TAG, "close" + kind + " succeeded handle=" + handle);
        return ERROR_NONE;
    }

    private boolean contains(Map<Integer, Session> sessions, int handle) {
        synchronized (mBridge) {
            return sessions.containsKey(handle);
        }
    }

    private int nextHandle(Map<Integer, Session> sessions) {
        int handle;
        do {
            handle = mRandom.nextInt(Integer.MAX_VALUE - 1) + 1;
        } while (sessions.containsKey(handle));
        return handle;
    }

    private void removeSession(Map<Integer, Session> sessions, int handle, boolean unlink) {
        Session session = sessions.remove(handle);
        if (session == null) {
            return;
        }
        if (unlink) {
            session.token.unlinkToDeath(session, 0);
        }
    }

    private static Bundle successBundle(int handle) {
        Bundle bundle = new Bundle();
        bundle.putInt("out", handle);
        bundle.putInt("e", ERROR_NONE);
        return bundle;
    }

    private static Bundle errorBundle(int error) {
        Bundle bundle = new Bundle();
        bundle.putInt("out", -1);
        bundle.putInt("e", error);
        return bundle;
    }

    private static Bundle transceiveErrorBundle(int error) {
        Bundle bundle = new Bundle();
        bundle.putByteArray("out", null);
        bundle.putInt("e", error);
        return bundle;
    }

    private final class Session implements IBinder.DeathRecipient {
        final int handle;
        final IBinder token;
        final Map<Integer, Session> owner;

        Session(int handle, IBinder token, Map<Integer, Session> owner) {
            this.handle = handle;
            this.token = token;
            this.owner = owner;
        }

        @Override
        public void binderDied() {
            boolean closeNativeSe = false;
            synchronized (mBridge) {
                Log.d(TAG, "FeliCa client died, closing handle " + handle);
                removeSession(owner, handle, false);
                closeNativeSe = owner == mSeSessions;
            }
            if (closeNativeSe) {
                mNativeFelicaSe.close(handle);
            }
            mBridge.setActive(false);
        }
    }
}
