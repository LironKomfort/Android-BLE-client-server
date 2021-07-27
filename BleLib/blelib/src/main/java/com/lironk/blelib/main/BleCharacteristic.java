package com.lironk.blelib.main;

import java.util.UUID;

public abstract class BleCharacteristic {
    private UUID mUUID;
    private byte [] mData;

    public BleCharacteristic(UUID mUUID) {
        this.mUUID = mUUID;
    }

    public BleCharacteristic(UUID UUID, byte [] data) {
        this.mUUID = UUID;
        this.mData = data;
    }

    public UUID getUUID() {
        return mUUID;
    }

    public byte[] getData() {
        return mData;
    }

    public abstract byte[] serialize();
}
