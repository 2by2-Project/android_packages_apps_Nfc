//
// Copyright (C) 2026 The 2by2 Project
// SPDX-License-Identifier: Apache-2.0
//

package com.android.nfc;

import android.util.Log;

final class FelicaBridge {
    private static final String TAG = "FelicaBridge";
    private static final long ROUTING_TIMEOUT_MS = 1500;

    private final NfcService mService;
    private final FelicaBrokerService mBroker;
    private int mOpenCount;
    private boolean mWarmupRunning;

    FelicaBridge(NfcService service) {
        mService = service;
        mBroker = new FelicaBrokerService(this);
    }

    void register() {
        FelicaBrokerService.register(mBroker);
    }

    boolean isNfcEnabled() {
        return mService.isNfcEnabled();
    }

    void setActive(boolean active) {
        int openCount = updateOpenCount(active);
        Log.i(TAG, "FeliCa bridge " + (active ? "open" : "close") + ", count=" + openCount);
        requestRouting();
    }

    boolean setActiveAndWait(boolean active) {
        int openCount = updateOpenCount(active);
        Log.i(TAG, "FeliCa bridge " + (active ? "open" : "close") + ", count=" + openCount);
        return requestRoutingAndWait();
    }

    boolean prepareRoutingAndWait() {
        return requestRoutingAndWait();
    }

    void requestRouting() {
        mService.requestFelicaBridgeRouting();
    }

    void onNfcEnabled() {
        synchronized (this) {
            if (mWarmupRunning) {
                return;
            }
            mWarmupRunning = true;
        }
        new Thread(() -> {
            try {
                if (mBroker.warmupSe()) {
                    Log.i(TAG, "NFC state-on warmup succeeded");
                } else {
                    Log.w(TAG, "NFC state-on warmup failed");
                }
            } finally {
                synchronized (FelicaBridge.this) {
                    mWarmupRunning = false;
                }
            }
        }, "FelicaNfcStateWarmup").start();
    }

    private int updateOpenCount(boolean active) {
        synchronized (this) {
            if (active) {
                mOpenCount++;
            } else if (mOpenCount > 0) {
                mOpenCount--;
            } else {
                mOpenCount = 0;
            }
            return mOpenCount;
        }
    }

    private boolean requestRoutingAndWait() {
        return mService.requestFelicaBridgeRoutingAndWait(ROUTING_TIMEOUT_MS);
    }
}
