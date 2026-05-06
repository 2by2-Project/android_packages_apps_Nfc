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

    void requestRouting() {
        mService.requestFelicaBridgeRouting();
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
