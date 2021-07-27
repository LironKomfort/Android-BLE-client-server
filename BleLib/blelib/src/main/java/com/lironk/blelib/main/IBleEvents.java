package com.lironk.blelib.main;

import java.util.UUID;

public interface IBleEvents {

    void bleConnectionStateChanged(int state);

    void bleDataReceived(UUID uuid, byte [] data);

    void bleDataSent(BleOperation operation);
}
