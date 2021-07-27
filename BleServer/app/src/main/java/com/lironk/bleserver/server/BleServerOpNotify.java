package com.lironk.bleserver.server;

import android.bluetooth.BluetoothDevice;

import com.lironk.blelib.main.BleOperation;

import java.util.UUID;


public class BleServerOpNotify extends BleOperation {

    private UUID mCharacteristic;
    private byte[] mByteArrValue;
    private BluetoothDevice mDevice;

    public BleServerOpNotify(UUID characteristic, byte[] value, BluetoothDevice device) {
        mCharacteristic = characteristic;
        mByteArrValue = value;
        mDevice = device;
    }

    public UUID getCharcUUID() {
        return mCharacteristic;
    }

    public byte[] getByteArrValue() {
        return mByteArrValue;
    }

    public BluetoothDevice getDevice(){
        return mDevice;
    }
}