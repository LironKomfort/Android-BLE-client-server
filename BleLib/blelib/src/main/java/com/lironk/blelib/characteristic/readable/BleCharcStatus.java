package com.lironk.blelib.characteristic.readable;


import com.lironk.blelib.enums.eBleCommand;
import com.lironk.blelib.main.BleCharacteristic;

import static com.lironk.blelib.main.BleProfile.R_STATUS;


public class BleCharcStatus extends BleCharacteristic {

    private eBleCommand mCommand;
    private byte mStatus;

    public BleCharcStatus(eBleCommand command, byte status) {
        super(R_STATUS);
        mCommand = command;
        mStatus = status;
    }

    public BleCharcStatus(byte [] data) {
        super(R_STATUS);
        mCommand = eBleCommand.getCommand(data[0]);
        mStatus = data[1];
    }

    public eBleCommand getCommand(){
        return mCommand;
    }

    public byte getStatus(){
        return mStatus;
    }

    @Override
    public byte[] serialize(){
        return new byte[]{mCommand.getCommand(), mStatus};
    }
}
