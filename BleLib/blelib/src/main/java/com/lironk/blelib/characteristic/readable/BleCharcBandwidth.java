package com.lironk.blelib.characteristic.readable;

import com.lironk.blelib.main.BleCharacteristic;

import java.util.Arrays;

import static com.lironk.blelib.main.BleProfile.R_BANDWIDTH;

public class BleCharcBandwidth extends BleCharacteristic {

    private byte [] mData;

    public BleCharcBandwidth(int mtu) {
        super(R_BANDWIDTH);
        mData = new byte[mtu];
        Arrays.fill(mData, (byte) 1);
    }

    public BleCharcBandwidth(byte [] data){
        super(R_BANDWIDTH);
        mData = data;
    }

    public byte [] getData(){
        return mData;
    }

    @Override
    public byte[] serialize(){
        return mData;
    }
}
