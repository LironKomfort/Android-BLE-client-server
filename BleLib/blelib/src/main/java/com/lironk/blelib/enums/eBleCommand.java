package com.lironk.blelib.enums;

public enum eBleCommand {
    eCuBleCommand_Start((byte) 0),
    eCuBleCommand_Stop((byte) 1),
    eCuBleCommand_Get((byte) 2),
    Unknown((byte) -1);

    private final byte mCommand;

    eBleCommand(byte command) {
        mCommand = command;
    }

    public byte getCommand() {
        return mCommand;
    }

    public static eBleCommand getCommand(byte command) {
        switch (command) {
            case 0:
                return eCuBleCommand_Start;
            case 1:
                return eCuBleCommand_Stop;
            case 2:
                return eCuBleCommand_Get;
        }
        return Unknown;
    }
}
