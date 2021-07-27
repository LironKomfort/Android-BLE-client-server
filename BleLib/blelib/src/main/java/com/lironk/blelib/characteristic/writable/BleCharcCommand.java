package com.lironk.blelib.characteristic.writable;


import com.lironk.blelib.enums.eBleCommand;
import com.lironk.blelib.main.BleCharacteristic;

import static com.lironk.blelib.main.BleProfile.W_COMMAND;


public class BleCharcCommand extends BleCharacteristic {

    private eBleCommand mCommand;

    public BleCharcCommand(byte [] data){
        super(W_COMMAND, data);
        mCommand = eBleCommand.getCommand(data[0]);
    }

    @Override
    public byte[] serialize() {
        return new byte[]{mCommand.getCommand()};
    }

    public eBleCommand getCommand() {
        return mCommand;
    }
}
