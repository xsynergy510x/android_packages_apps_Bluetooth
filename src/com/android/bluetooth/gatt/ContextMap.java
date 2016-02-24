/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.bluetooth.gatt;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.IInterface;
import android.os.RemoteException;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

/**
 * Helper class that keeps track of registered GATT applications.
 * This class manages application callbacks and keeps track of GATT connections.
 * @hide
 */
/*package*/ class ContextMap<T> {
    private static final String TAG = GattServiceConfig.TAG_PREFIX + "ContextMap";

    /**
     * ScanStats class helps keep track of information about scans
     * on a per application basis.
     */
    class ScanStats {
        static final int NUM_SCAN_DURATIONS_KEPT = 5;

        int scansStarted = 0;
        int scansStopped = 0;
        boolean isScanning = false;
        boolean isRegistered = false;
        long minScanTime = Long.MAX_VALUE;
        long maxScanTime = 0;
        long totalScanTime = 0;
        List<Long> lastScans = new ArrayList<Long>(NUM_SCAN_DURATIONS_KEPT + 1);
        long startTime = 0;
        long stopTime = 0;

        void startScan() {
            this.scansStarted++;
            isScanning = true;
            startTime = System.currentTimeMillis();
        }

        void stopScan() {
            this.scansStopped++;
            isScanning = false;
            stopTime = System.currentTimeMillis();
            long currTime = stopTime - startTime;

            minScanTime = Math.min(currTime, minScanTime);
            maxScanTime = Math.max(currTime, maxScanTime);
            totalScanTime += currTime;
            lastScans.add(currTime);
            if (lastScans.size() > NUM_SCAN_DURATIONS_KEPT) {
                lastScans.remove(0);
            }
        }
    }

    /**
     * Connection class helps map connection IDs to device addresses.
     */
    class Connection {
        int connId;
        String address;
        int appId;

        Connection(int connId, String address,int appId) {
            this.connId = connId;
            this.address = address;
            this.appId = appId;
        }
    }

    /**
     * Application entry mapping UUIDs to appIDs and callbacks.
     */
    class App {
        /** The UUID of the application */
        UUID uuid;

        /** The id of the application */
        int id;

        /** The package name of the application */
        String name;

        /** Application callbacks */
        T callback;

        /** Death receipient */
        private IBinder.DeathRecipient mDeathRecipient;

        /** Flag to signal that transport is congested */
        Boolean isCongested = false;

        /** Internal callback info queue, waiting to be send on congestion clear */
        private List<CallbackInfo> congestionQueue = new ArrayList<CallbackInfo>();

        /**
         * Creates a new app context.
         */
        App(UUID uuid, T callback, String name) {
            this.uuid = uuid;
            this.callback = callback;
            this.name = name;
        }

        /**
         * Link death recipient
         */
        void linkToDeath(IBinder.DeathRecipient deathRecipient) {
            try {
                IBinder binder = ((IInterface)callback).asBinder();
                binder.linkToDeath(deathRecipient, 0);
                mDeathRecipient = deathRecipient;
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to link deathRecipient for app id " + id);
            }
        }

        /**
         * Unlink death recipient
         */
        void unlinkToDeath() {
            if (mDeathRecipient != null) {
                try {
                    IBinder binder = ((IInterface)callback).asBinder();
                    binder.unlinkToDeath(mDeathRecipient,0);
                } catch (NoSuchElementException e) {
                    Log.e(TAG, "Unable to unlink deathRecipient for app id " + id);
                }
            }
        }

        void queueCallback(CallbackInfo callbackInfo) {
            congestionQueue.add(callbackInfo);
        }

        CallbackInfo popQueuedCallback() {
            if (congestionQueue.size() == 0) return null;
            return congestionQueue.remove(0);
        }
    }

    /** Our internal application list */
    List<App> mApps = new ArrayList<App>();

    /** Internal map to keep track of logging information by app name */
    HashMap<String, ScanStats> mScanStats = new HashMap<String, ScanStats>();

    /** Internal list of connected devices **/
    Set<Connection> mConnections = new HashSet<Connection>();

    /**
     * Add an entry to the application context list.
     */
    void add(UUID uuid, T callback, Context context) {
        String appName = context.getPackageManager().getNameForUid(
                             Binder.getCallingUid());
        if (appName == null) {
            // Assign an app name if one isn't found
            appName = "Unknown App (UID: " + Binder.getCallingUid() + ")";
        }
        synchronized (mApps) {
            mApps.add(new App(uuid, callback, appName));
            ScanStats scanStats = mScanStats.get(appName);
            if (scanStats == null) {
                scanStats = new ScanStats();
                mScanStats.put(appName, scanStats);
            }
            scanStats.isRegistered = true;
        }
    }

    /**
     * Remove the context for a given UUID
     */
    void remove(UUID uuid) {
        synchronized (mApps) {
            Iterator<App> i = mApps.iterator();
            while (i.hasNext()) {
                App entry = i.next();
                if (entry.uuid.equals(uuid)) {
                    entry.unlinkToDeath();
                    mScanStats.get(entry.name).isRegistered = false;
                    i.remove();
                    break;
                }
            }
        }
    }

    /**
     * Remove the context for a given application ID.
     */
    void remove(int id) {
        synchronized (mApps) {
            Iterator<App> i = mApps.iterator();
            while (i.hasNext()) {
                App entry = i.next();
                if (entry.id == id) {
                    entry.unlinkToDeath();
                    mScanStats.get(entry.name).isRegistered = false;
                    i.remove();
                    break;
                }
            }
        }
        synchronized (mConnections) {
            Iterator<Connection> i = mConnections.iterator();
            while(i.hasNext()) {
                Connection connection = i.next();
                if (connection.appId == id) {
                    i.remove();
                }
            }
        }
    }

    /**
     * Add a new connection for a given application ID.
     */
    void addConnection(int id, int connId, String address) {
        synchronized (mConnections) {
            App entry = getById(id);
            if (entry != null){
                mConnections.add(new Connection(connId, address, id));
            }
        }
    }

    /**
     * Remove a connection with the given ID.
     */
    void removeConnection(int id, int connId) {
        synchronized (mConnections) {
            Iterator<Connection> i = mConnections.iterator();
            while (i.hasNext()) {
                Connection connection = i.next();
                if (connection.connId == connId) {
                    i.remove();
                }
            }
        }
    }

    /**
     * Get an application context by ID.
     */
    App getById(int id) {
        Iterator<App> i = mApps.iterator();
        while (i.hasNext()) {
            App entry = i.next();
            if (entry.id == id) return entry;
        }
        Log.e(TAG, "Context not found for ID " + id);
        return null;
    }

    /**
     * Get an application context by UUID.
     */
    App getByUuid(UUID uuid) {
        Iterator<App> i = mApps.iterator();
        while (i.hasNext()) {
            App entry = i.next();
            if (entry.uuid.equals(uuid)) return entry;
        }
        Log.e(TAG, "Context not found for UUID " + uuid);
        return null;
    }

    /**
     * Get an application context by the calling Apps name.
     */
    App getByName(String name) {
        Iterator<App> i = mApps.iterator();
        while (i.hasNext()) {
            App entry = i.next();
            if (entry.name.equals(name)) return entry;
        }
        Log.e(TAG, "Context not found for name " + name);
        return null;
    }

    /**
     * Get Logging info by ID
     */
    ScanStats getScanStatsById(int id) {
        App temp = getById(id);
        if (temp != null) {
            return mScanStats.get(temp.name);
        }
        return null;
    }

    /**
     * Get the device addresses for all connected devices
     */
    Set<String> getConnectedDevices() {
        Set<String> addresses = new HashSet<String>();
        Iterator<Connection> i = mConnections.iterator();
        while (i.hasNext()) {
            Connection connection = i.next();
            addresses.add(connection.address);
        }
        return addresses;
    }

    /**
     * Get an application context by a connection ID.
     */
    App getByConnId(int connId) {
        Iterator<Connection> ii = mConnections.iterator();
        while (ii.hasNext()) {
            Connection connection = ii.next();
            if (connection.connId == connId){
                return getById(connection.appId);
            }
        }
        return null;
    }

    /**
     * Returns a connection ID for a given device address.
     */
    Integer connIdByAddress(int id, String address) {
        App entry = getById(id);
        if (entry == null) return null;

        Iterator<Connection> i = mConnections.iterator();
        while (i.hasNext()) {
            Connection connection = i.next();
            if (connection.address.equals(address) && connection.appId == id)
                return connection.connId;
        }
        return null;
    }

    /**
     * Returns the device address for a given connection ID.
     */
    String addressByConnId(int connId) {
        Iterator<Connection> i = mConnections.iterator();
        while (i.hasNext()) {
            Connection connection = i.next();
            if (connection.connId == connId) return connection.address;
        }
        return null;
    }

    List<Connection> getConnectionByApp(int appId) {
        List<Connection> currentConnections = new ArrayList<Connection>();
        Iterator<Connection> i = mConnections.iterator();
        while (i.hasNext()) {
            Connection connection = i.next();
            if (connection.appId == appId)
                currentConnections.add(connection);
        }
        return currentConnections;
    }

    /**
     * Erases all application context entries.
     */
    void clear() {
        synchronized (mApps) {
            Iterator<App> i = mApps.iterator();
            while (i.hasNext()) {
                App entry = i.next();
                entry.unlinkToDeath();
                i.remove();
            }
        }

        synchronized (mConnections) {
            mConnections.clear();
        }
    }

    /**
     * Returns connect device map with addr and appid
     */
    Map<Integer, String> getConnectedMap(){
        Map<Integer, String> connectedmap = new HashMap<Integer, String>();
        for(Connection conn: mConnections){
            connectedmap.put(conn.appId, conn.address);
        }
        return connectedmap;
    }

    /**
     * Logs debug information.
     */
    void dump(StringBuilder sb) {
        long currTime = System.currentTimeMillis();

        sb.append("  Entries: " + mScanStats.size() + "\n\n");

        Iterator<Map.Entry<String,ScanStats>> i = mScanStats.entrySet().iterator();
        while (i.hasNext()) {
            Map.Entry<String, ScanStats> entry = i.next();

            String name = entry.getKey();
            ScanStats scanStats = entry.getValue();

            long maxScanTime = scanStats.maxScanTime;
            long minScanTime = scanStats.minScanTime;
            long currScanTime = 0;

            if (scanStats.isScanning) {
                currScanTime = currTime - scanStats.startTime;
                minScanTime = Math.min(currScanTime, minScanTime);
                maxScanTime = Math.max(currScanTime, maxScanTime);
            }

            if (minScanTime == Long.MAX_VALUE) {
                minScanTime = 0;
            }

            long lastScan = 0;
            if (scanStats.stopTime != 0) {
                lastScan = currTime - scanStats.stopTime;
            }

            long avgScanTime = 0;
            if (scanStats.scansStarted > 0) {
                avgScanTime = (scanStats.totalScanTime + currScanTime) /
                              scanStats.scansStarted;
            }

            sb.append("  Application Name: " + name);
            if (scanStats.isRegistered) sb.append(" (Registered)");
            sb.append("\n");

            sb.append("  LE scans (started/stopped)       : " +
                      scanStats.scansStarted + " / " +
                      scanStats.scansStopped + "\n");
            sb.append("  Scan time in ms (min/max/avg)    : " +
                      minScanTime + " / " +
                      maxScanTime + " / " +
                      avgScanTime + "\n");

            sb.append("  Time since last scan ended in ms : " + lastScan + "\n");

            sb.append("  Last " + scanStats.lastScans.size() +
                      " scans in ms (oldest first): ");
            for (Long time : scanStats.lastScans) {
                sb.append(time + " ");
            }
            sb.append("\n");

            if (scanStats.isRegistered) {
                App appEntry = getByName(name);
                sb.append("  Application ID                   : " +
                          appEntry.id + "\n");
                sb.append("  UUID                             : " +
                          appEntry.uuid + "\n");

                if (scanStats.isScanning) {
                    sb.append("  Current scan duration            : " +
                              currScanTime + "\n");
                }

                List<Connection> connections = getConnectionByApp(appEntry.id);
                sb.append("  Connections: " + connections.size() + "\n");

                Iterator<Connection> ii = connections.iterator();
                while(ii.hasNext()) {
                    Connection connection = ii.next();
                    sb.append("    " + connection.connId + ": " +
                              connection.address + "\n");
                }
            }
            sb.append("\n");
        }
    }
}
