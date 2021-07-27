package com.lironk.blelib.main;

import android.bluetooth.BluetoothProfile;

public class Utils {

    public static String bleStateToString(int state){
        switch (state){
            case BluetoothProfile.STATE_CONNECTING:
                return "Connecting";
            case BluetoothProfile.STATE_CONNECTED:
                return "Connected";
            case BluetoothProfile.STATE_DISCONNECTING:
                return "Disconnecting";
            case BluetoothProfile.STATE_DISCONNECTED:
            default:
                return "Disconnected";
        }
    }
}
