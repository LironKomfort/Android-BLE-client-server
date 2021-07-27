package com.lironk.blelib.enums;

public enum eBleStatus {
    eBleStatus_Ok((byte) 0x00000001),
    eBleStatus_Error((byte) 0x00000002),
    eBleStatus_WrongState((byte) 0x00000004);

    private final byte mStatus;

    eBleStatus(byte status) {
        mStatus = status;
    }

    public byte getStatus() {
        return mStatus;
    }

    public boolean and(int type) {
        return (mStatus & type) != 0;
    }

    public static eBleStatus getStatus(byte status) {
        switch (status) {
            case 1:
                return eBleStatus_Ok;
            case 2:
                return eBleStatus_Error;
            case 3:
            default:
                return eBleStatus_WrongState;
        }
    }
}
